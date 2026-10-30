package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoNota;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.ClienteDesactivadoException;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.FacturaImpedidaException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ViaDeAnulacion;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Anula una venta en UNA transacción (CU-08 puntos 2 a 5; cu-08-nota-de-credito C1 y C3): da de alta su nota de crédito
 * (NOTA_CREDITO asociada a la venta, con su número interno), devuelve el stock y, del lado fiscal, da de alta el registro
 * PENDIENTE de la NC (la venta tiene su factura AUTORIZADO) o cierra sin CAE lo que tenga la venta (la anulación interna,
 * con motivo). Si la venta cargó algo a cuenta corriente, la nota lo acredita en la cuenta del cliente (cu-08-nc-cuenta-corriente
 * S3, S6: las dos vías igual), por el puerto de clientes. No toma la caja ni escribe pagos (D11: la caja no cambia).
 *
 * <p><b>Orden canónico de locks</b> (el prefijo de la venta y del «Reintentar»):
 * <pre>
 * CLIENTE (X, solo si la venta tiene uno) → la VENTA (X, FOR UPDATE: el comprobante) → [primeras lecturas planas: la nota,
 *   los pagos y los renglones de la venta] → registro fiscal vigente de la venta (X, tomarParaAnular; config_arca en S si
 *   está AUTORIZADO) → numerador (X) → comprobante de la nota → artículos/stock (X, id asc) → renglones de la nota
 *   → [solo con pagos a cuenta corriente] la cuenta del cliente (el cliente ya está en X: partidas de la venta, id asc →
 *   lo imputado FOR UPDATE → imputación y libro → saldo) → registro fiscal de la NC, o el de la venta a SIN_FACTURA
 * </pre>
 * El asiento de la cuenta va después del stock, como en la venta a cuenta corriente (stock → libro), y dentro de clientes
 * sigue el orden de la cobranza. La cobranza y la venta del mismo cliente esperan en la fila del cliente (paso 1): no hay
 * ciclo con ellas (verificado en cu-08-nc-cuenta-corriente T4 con {@code AnulacionDeVentaIntegrationTest}).
 * La nota no lleva caja, así que no toma la caja en S: el cierre de caja no la espera ni la ve. El stock va antes que los
 * renglones de la nota, como en la venta: así los locks X de los artículos se toman antes que los S de las FK de los
 * renglones. Queda un ciclo posible con «Reintentar» sobre una venta sin cliente: ese toma el registro rechazado en X
 * y el INSERT del registro nuevo pide la venta en S (la FK compuesta de V32), mientras la anulación toma la venta en X y
 * después el registro. InnoDB corta el deadlock y las dos fachadas reintentan (verificado en T7 con
 * {@code AnulacionDeVentaConcurrenciaIntegrationTest}).
 *
 * <p><b>Regla estructural</b> (la de {@link RegistroAnulacion}): ninguna lectura plana antes de tomar la venta. La foto
 * REPEATABLE READ de InnoDB nace con la primera lectura plana: si la nota se buscara antes del lock de la venta, una
 * segunda anulación que esperó el turno no vería la nota de la primera y chocaría con el UNIQUE en vez de dar el 409. Por
 * eso el cliente de la venta se resuelve antes, en {@link #clienteDeLaVenta} (otra transacción de solo lectura, sin locks,
 * porque el cliente de una venta no cambia), y la venta se vuelve a leer acá con lock. El choque del UNIQUE
 * ({@code uk_comprobante_asociado}) igual lo traduce la fachada ({@link AnulacionDeVenta}) con {@link #rechazoSiAnulada}.
 */
@Service
public class RegistroAnulacionDeVenta {

    static final int LARGO_MOTIVO = 200;
    static final String MOTIVO_VACIO = "Contá por qué se anula la venta.";
    static final String MOTIVO_LARGO = "El motivo tiene hasta 200 caracteres.";

    /** La nota que quedó guardada y si es la NC con CAE (hay que pedirlo después del commit) o la anulación interna. */
    public record NotaRegistrada(long notaId, boolean conCae) {}

    private final ClienteDelDocumento clienteDelDocumento;
    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final NumeradorComprobanteRepository numeradorRepository;
    private final StockService stockService;
    private final FacturacionParaVenta facturacion;
    private final CajaRepository cajaRepository;
    private final CuentaCorrienteParaVenta cuentaCorriente;
    private final Clock reloj;

    public RegistroAnulacionDeVenta(ClienteDelDocumento clienteDelDocumento, ComprobanteRepository comprobanteRepository,
            ItemComprobanteRepository itemRepository, PagoRepository pagoRepository,
            NumeradorComprobanteRepository numeradorRepository, StockService stockService,
            FacturacionParaVenta facturacion, CajaRepository cajaRepository, CuentaCorrienteParaVenta cuentaCorriente,
            Clock reloj) {
        this.clienteDelDocumento = clienteDelDocumento;
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.numeradorRepository = numeradorRepository;
        this.stockService = stockService;
        this.facturacion = facturacion;
        this.cajaRepository = cajaRepository;
        this.cuentaCorriente = cuentaCorriente;
        this.reloj = reloj;
    }

    /**
     * El cliente de la venta (null si se vendió sin cliente), leído en una transacción PROPIA de solo lectura y sin locks:
     * así no fija la foto de la que anula. Una venta de otra ferretería o que no existe es 404 «Venta no encontrada»; una
     * nota de crédito, 409 «Una nota de crédito no se anula.».
     */
    @Transactional(readOnly = true)
    public Long clienteDeLaVenta(Long ventaId) {
        return anulable(comprobanteRepository.findScoped(ventaId)
                .orElseThrow(() -> new ResourceNotFoundException(VentaGestion.MENSAJE_NO_ENCONTRADA)))
                .getClienteId();
    }

    /**
     * Anula la venta, o lanza el rechazo que corresponda sin guardar nada: ya anulada 409, la factura en envío o de otro
     * ambiente 409, el motivo de la anulación interna vacío o largo 400 y, si la venta cargó algo a cuenta corriente, el
     * cliente desactivado 422 «El cliente está desactivado.» (S8; sin cuenta corriente se anula igual). Va en el orden del
     * Javadoc de la clase. {@code motivo} solo vale en la anulación interna: en la NC se ignora (S13).
     */
    @Transactional
    public NotaRegistrada registrar(Long ventaId, Long clienteId, String motivo, long usuarioId) {
        TenantContext.requireFerreteriaId();
        // 1. El cliente (X), en el mismo lugar que la venta y el «Reintentar»: antes de cualquier lectura plana.
        if (clienteId != null) {
            clienteDelDocumento.tomarParaAnularVenta(clienteId);
        }
        // 2. La venta (X): una segunda anulación de la misma venta espera acá.
        Comprobante venta = anulable(comprobanteRepository.paraAnularScoped(ventaId)
                .orElseThrow(() -> new ResourceNotFoundException(VentaGestion.MENSAJE_NO_ENCONTRADA)));
        if (!Objects.equals(clienteId, venta.getClienteId())) {
            throw new IllegalStateException("La venta " + ventaId + " no es del cliente " + clienteId + ".");
        }

        // Recién ahora las lecturas planas: con la venta en X la foto ve la nota de cualquier anulación que confirmó antes.
        rechazoSiAnulada(ventaId);
        // Solo la parte a cuenta corriente se acredita (S3); la de otros medios sigue D11: no se toca.
        List<CargoPago> aCuenta = pagoRepository.deComprobanteScoped(ventaId).stream()
                .filter(p -> p.getMedio() == MedioPago.CUENTA_CORRIENTE)
                .map(p -> new CargoPago(p.getId(), p.getMonto()))
                .toList();
        List<ItemComprobante> vendidos = itemRepository.deComprobanteScoped(ventaId);

        // 3. El registro fiscal vigente de la venta (X): decide la NC o la anulación interna.
        ViaDeAnulacion via;
        try {
            via = facturacion.tomarParaAnular(ventaId);
        } catch (FacturaImpedidaException e) {
            throw FacturaDeLaVenta.rechazo(e);
        }
        boolean conCae = via == ViaDeAnulacion.NOTA_DE_CREDITO;
        // S13: la NC no lleva motivo; la anulación interna lo exige, antes de escribir nada.
        String motivoInterno = conCae ? null : validarMotivo(motivo);

        // 4. El numerador (X) y un solo instante para la nota y sus movimientos de stock.
        long numero = numeradorRepository.siguienteScoped();
        LocalDateTime fecha = LocalDateTime.ofInstant(reloj.instant(), HoraArgentina.ZONA);
        // 5. La nota: el INSERT choca con uk_comprobante_asociado si otra anulación de la venta se coló (la fachada da 409).
        Comprobante nota = comprobanteRepository.saveAndFlush(Comprobante.notaDeCredito(venta, numero,
                UUID.randomUUID().toString(), usuarioId, fecha, motivoInterno));

        // 6. El stock (X por artículo, id ascendente): vuelve todo lo vendido, también lo que salió por el remito (S5).
        stockService.registrarNotaDeCredito(nota.getId(), usuarioId, fecha, vendidos.stream()
                .map(i -> new SalidaStock(i.getArticuloId(), i.getCantidad()))
                .toList());
        itemRepository.saveAll(vendidos.stream().map(i -> ItemComprobante.copiaParaLaNota(i, nota.getId())).toList());

        // 7. La cuenta corriente (S3, S6), solo si la venta cargó algo: la nota acredita esos pagos, se imputa a la venta y
        //    el resto queda a favor. Un cliente desactivado lo frena (S8) y el rollback deshace la nota y el stock.
        if (!aCuenta.isEmpty()) {
            try {
                cuentaCorriente.asentarNotaDeCredito(new AsientoNota(clienteId, nota.getId(), nota.getNumero(),
                        referencia(nota), ventaId, aCuenta, usuarioId));
            } catch (ClienteDesactivadoException e) {
                throw new ReglaDeNegocioException(ClienteDesactivadoException.MENSAJE);
            }
        }

        // 8. Lo fiscal, al final: el alta de la NC (el CAE se pide después del commit) o la venta a SIN_FACTURA.
        if (conCae) {
            facturacion.registrarNotaDeCredito(nota.getId(), ventaId);
        } else {
            facturacion.cerrarSinCae(ventaId, motivoInterno, usuarioId);
        }
        return new NotaRegistrada(nota.getId(), conCae);
    }

    /**
     * Como la nombra el libro de la cuenta corriente y el stock: «Nota de crédito N.º n», o «Anulación N.º n» para la
     * anulación interna (la nota con motivo).
     */
    static String referencia(Comprobante nota) {
        return (nota.getMotivoAnulacion() != null ? "Anulación N.º " : "Nota de crédito N.º ") + nota.getNumero();
    }

    /**
     * Si la venta ya tiene su nota, lanza el 409 con quién y cuándo; si no, vuelve sin error. La fachada la usa también en
     * una transacción NUEVA para traducir el choque del UNIQUE de la nota: nunca 500.
     */
    @Transactional(readOnly = true)
    public void rechazoSiAnulada(Long ventaId) {
        comprobanteRepository.notasDeScoped(List.of(ventaId)).stream().findFirst().ifPresent(nota -> {
            String usuario = cajaRepository.nombresScoped(List.of(nota.getUsuarioId())).get(nota.getUsuarioId());
            throw VentaRechazadaException.ventaYaAnulada(usuario, nota.getFecha());
        });
    }

    /**
     * El motivo de la anulación interna sin espacios de más: obligatorio y de hasta {@value #LARGO_MOTIVO} caracteres (los
     * textos de S13). Si no, 400 en {@code motivo}.
     */
    static String validarMotivo(String motivo) {
        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_VACIO));
        }
        if (limpio.codePointCount(0, limpio.length()) > LARGO_MOTIVO) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_LARGO));
        }
        return limpio;
    }

    /** Solo una venta se anula: una nota de crédito es 409, y cualquier otro tipo, 404. */
    private static Comprobante anulable(Comprobante comprobante) {
        if (comprobante.getTipo() == TipoComprobante.NOTA_CREDITO) {
            throw VentaRechazadaException.notaDeCreditoNoSeAnula();
        }
        if (comprobante.getTipo() != TipoComprobante.VENTA) {
            throw new ResourceNotFoundException(VentaGestion.MENSAJE_NO_ENCONTRADA);
        }
        return comprobante;
    }
}
