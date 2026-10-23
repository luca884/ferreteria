package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.NumeradorReciboRepository;
import com.ferreteria.ventas.repository.ReciboRepository;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registra una cobranza de cuenta corriente en UNA transacción (CC-027, CC-029, CC-033..CC-038, CC-041). Primero valida la
 * forma sin tocar nada y recién después escribe, siempre en el mismo orden de locks (ADR-2 del design de tramo 3a):
 * <pre>
 * caja (S, FOR SHARE) → CLIENTE (X, FOR UPDATE nativo) → [repetida: el PRIMER SELECT plano] → numerador_recibo (X)
 *   → recibo → partidas (por id asc, FOR UPDATE) → imputaciones del cliente de los comprobantes imputados (FOR UPDATE)
 *   → imputaciones nuevas → libro (un COBRO) + saldo
 * </pre>
 * Las cuatro últimas líneas las hace {@code CuentaCorrienteParaCobranza.asentarCobro}.
 *
 * <p><b>Regla estructural</b>: NINGUNA llamada a repositorio ni SELECT plano antes de tomar al cliente (salvo la caja, que es
 * una lectura bloqueante). El motivo es el snapshot REPEATABLE READ de InnoDB: la foto de la transacción se fija con la PRIMERA
 * lectura consistente (SELECT plano); las lecturas con FOR SHARE / FOR UPDATE son lecturas ACTUALES y no la fijan. Si algo
 * plano corriera antes del lock del cliente, la foto sería anterior al commit de la cobranza que acabamos de esperar, y esta
 * sobre-imputaría. Por eso la caja y el cliente van primero, y {@link #buscarRepetida} (el recibo de la clave) y el nombre del
 * cobrador, después. {@code RegistroCobranzaTest} fija el orden con un {@code InOrder} y con la verificación de que nada se tocó
 * antes del cliente; {@code CobranzaConcurrenciaIntegrationTest} lo fija contra MySQL real (si alguien mueve un SELECT plano
 * antes del lock, esa prueba falla).
 *
 * <p><b>Compatibilidad con {@link RegistroVenta}</b> (caja S → presupuesto X → remito X → CLIENTE X → numerador_comprobante X →
 * ...): el cliente va siempre antes de cualquier numerador; la cobranza no toma presupuesto, remito, stock ni
 * {@code numerador_comprobante}; los numeradores son tablas distintas, así que una cobranza y una venta de clientes distintos
 * solo comparten la caja en S. Mismo cliente: se serializan en el lock del cliente. Sin ciclos. Contra el cierre de caja: el
 * cierre toma la caja en X y nunca toma al cliente, así que espera a las cobranzas en vuelo (que la tienen en S) y las que
 * llegan después ven la caja cerrada; el resumen del cierre, leído DESPUÉS del lock X, ve todos los recibos comprometidos.
 *
 * <p><b>Fase 5 (facturación)</b>: insertar recibo, imputación o libro toma un lock compartido sobre la fila padre del
 * comprobante por la FK. Cuando la facturación haga UPDATE de {@code comprobante} (estado), DEBE tomar al cliente ANTES que el
 * comprobante, o habrá un ciclo con esa FK. Toda operación futura que mueva la cuenta (anulación, ajuste, imputar saldo a
 * favor) también toma al cliente antes que cualquier lectura plana.
 *
 * <p>El que llama ({@link CobranzaService}) no tiene transacción: resuelve la carrera de la clave y reintenta los deadlocks.
 * Si algo falla después del numerador, la transacción entera se revierte: el número de recibo no se consume (sin huecos).
 */
@Service
public class RegistroCobranza {

    static final String MENSAJE_MEDIO = "Elegí efectivo o transferencia";
    static final String MENSAJE_COMPROBANTE_REPETIDO = "Cada comprobante va una sola vez.";
    static final String MENSAJE_DEUDA_DOBLE = "Elegí el comprobante o el Recargo, no los dos.";
    static final String MENSAJE_SUMA_MAYOR = "Lo imputado supera el total del recibo.";
    static final String MENSAJE_OTRO_CONTENIDO = "Este cobro ya se registró con otros datos";
    static final String MENSAJE_RECIBO_INEXISTENTE = "Recibo no encontrado";
    static final String MENSAJE_EXCEDE_DEUDA = "La deuda de algún comprobante cambió. Revisá la imputación y confirmá de nuevo.";

    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final CajaService cajaService;
    private final ClienteDelDocumento clienteDelDocumento;
    private final NumeradorReciboRepository numeradorRepository;
    private final ReciboRepository reciboRepository;
    private final CuentaCorrienteParaCobranza cuentaCorriente;
    private final CajaRepository cajaRepository;
    private final Clock reloj;

    public RegistroCobranza(CajaService cajaService, ClienteDelDocumento clienteDelDocumento,
            NumeradorReciboRepository numeradorRepository, ReciboRepository reciboRepository,
            CuentaCorrienteParaCobranza cuentaCorriente, CajaRepository cajaRepository, Clock reloj) {
        this.cajaService = cajaService;
        this.clienteDelDocumento = clienteDelDocumento;
        this.numeradorRepository = numeradorRepository;
        this.reciboRepository = reciboRepository;
        this.cuentaCorriente = cuentaCorriente;
        this.cajaRepository = cajaRepository;
        this.reloj = reloj;
    }

    /**
     * Registra la cobranza, o devuelve la que ya tiene esta clave (200 en vez de 201). Va en el orden del Javadoc de la clase.
     *
     * @throws DatosInvalidosException (400) si el pedido está mal formado, antes de tocar nada
     * @throws CobranzaRechazadaException caja cerrada o de otro día (422), clave con otro contenido (422), o la deuda de
     *     algún comprobante cambió (409, con las deudas actuales)
     */
    @Transactional
    public CobroRegistrado registrar(CobranzaPedido pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        validarForma(pedido);

        // CU-10: sin caja abierta del día no se cobra. Primer lock (S): el cierre espera a esta cobranza.
        Long cajaId = cajaService.cajaParaCobrar();
        // CC-034: el cliente se TOMA acá (X), antes de cualquier lectura plana y de cualquier numerador. Ajeno 404, desactivado 422.
        ClienteDelDocumento.Elegido cliente = clienteDelDocumento.resolverParaCobranza(pedido.clienteId());

        // Recién ahora el primer SELECT plano: con el cliente en X la foto ve todo lo que confirmó cualquier cobranza o venta previa.
        Optional<CobroRegistrado> yaRegistrada = buscarRepetida(pedido);
        if (yaRegistrada.isPresent()) {
            return yaRegistrada.get();
        }

        long numero = numeradorRepository.siguienteScoped();
        Recibo recibo = reciboRepository.save(Recibo.emitir(ferreteriaId, numero, pedido.clave(), cliente.id(), cliente.nombre(),
                cajaId, usuarioId, LocalDateTime.now(reloj), pedido.medio(), pedido.total()));

        List<ImputacionAsentada> imputadas;
        try {
            imputadas = cuentaCorriente.asentarCobro(new AsientoCobro(cliente.id(), recibo.getId(), recibo.getNumero(),
                    pedido.total(), pedido.imputaciones(), usuarioId));
        } catch (ImputacionInvalidaException e) {
            // Se revierte todo (recibo, numerador): el rechazo no consume número.
            throw new CobranzaRechazadaException(Motivo.IMPUTACION_EXCEDE_DEUDA, MENSAJE_EXCEDE_DEUDA, e.deudas());
        }
        return new CobroRegistrado(recibo, imputadas, nombreDe(usuarioId), false);
    }

    /**
     * El cobro que ya tiene la clave del pedido en la ferretería del request, si lo hay (CC-038). Solo lee y NO toma ningún
     * lock: la fachada la llama como pre-chequeo en una transacción propia (devuelve 200 aunque la caja haya cerrado o el
     * cliente se haya desactivado) y después de perder la carrera de la clave única. Con el mismo contenido es el cobro
     * original; con otro, CLAVE_CON_OTRO_CONTENIDO.
     */
    @Transactional(readOnly = true)
    public Optional<CobroRegistrado> repetida(CobranzaPedido pedido) {
        TenantContext.requireFerreteriaId();
        validarForma(pedido);
        return buscarRepetida(pedido);
    }

    /**
     * El recibo con su imputación, para verlo o reimprimirlo (GET). Solo lee, sin locks, en una transacción de solo lectura (una
     * foto): dice lo mismo que la cobranza repetida. Un recibo de otra ferretería o inexistente es 404.
     */
    @Transactional(readOnly = true)
    public CobroRegistrado recibo(Long id) {
        Recibo recibo = reciboRepository.findScoped(id).orElseThrow(() -> new ResourceNotFoundException(MENSAJE_RECIBO_INEXISTENTE));
        return cobroExistente(recibo, cuentaCorriente.imputacionesDeRecibo(recibo.getId()));
    }

    private Optional<CobroRegistrado> buscarRepetida(CobranzaPedido pedido) {
        Optional<Recibo> existente = reciboRepository.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        Recibo recibo = existente.get();
        List<ImputacionAsentada> asentadas = cuentaCorriente.imputacionesDeRecibo(recibo.getId());
        if (!pedido.mismoContenido(recibo, asentadas)) {
            throw new CobranzaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO, MENSAJE_OTRO_CONTENIDO);
        }
        return Optional.of(cobroExistente(recibo, asentadas));
    }

    /**
     * Un cobro ya registrado (repetida o GET): el recibo tal como está AHORA. Si fue anulado (CC-070) lleva también quién lo anuló,
     * y las imputaciones son las originales: la repetida devuelve el recibo ANULADO, nunca uno nuevo.
     */
    private CobroRegistrado cobroExistente(Recibo recibo, List<ImputacionAsentada> asentadas) {
        if (!recibo.isAnulado()) {
            return new CobroRegistrado(recibo, asentadas, nombreDe(recibo.getUsuarioId()), true);
        }
        Map<Long, String> nombres = cajaRepository.nombresScoped(new HashSet<>(List.of(recibo.getUsuarioId(), recibo.getUsuarioAnulacionId())));
        return new CobroRegistrado(recibo, asentadas, nombres.get(recibo.getUsuarioId()), true,
                nombres.get(recibo.getUsuarioAnulacionId()));
    }

    /** El nombre de quien cobró (lectura plana, posterior a los locks: inocua). */
    private String nombreDe(Long usuarioId) {
        return cajaRepository.nombresScoped(Set.of(usuarioId)).get(usuarioId);
    }

    /**
     * Paso 0: la forma del pedido, sin base de datos, con todos los errores juntos (uno por campo). Imputaciones vacías es
     * válido (todo «a cuenta»). Un error en {@code imputaciones} (comprobante repetido o suma mayor al total) gana el primero.
     */
    private static void validarForma(CobranzaPedido pedido) {
        Map<String, String> errores = new LinkedHashMap<>();
        if (pedido.clave() == null || !UUID.matcher(pedido.clave()).matches()) {
            errores.put("clave", "La clave del cobro no es válida.");
        }
        if (pedido.clienteId() == null) {
            errores.put("clienteId", "Elegí el cliente a cobrar.");
        }
        if (pedido.medio() != MedioPago.EFECTIVO && pedido.medio() != MedioPago.TRANSFERENCIA) {
            errores.put("medio", MENSAJE_MEDIO);
        }
        boolean totalValido = false;
        if (pedido.total() == null || pedido.total().signum() <= 0) {
            errores.put("total", "El total tiene que ser mayor que cero.");
        } else if (decimalesDeMas(pedido.total())) {
            errores.put("total", "El total admite hasta 2 decimales.");
        } else if (pedido.total().compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0) {
            errores.put("total", "El total supera el máximo permitido.");
        } else {
            totalValido = true;
        }

        BigDecimal suma = BigDecimal.ZERO;
        boolean renglonesValidos = true;
        Set<Long> comprobantesVistos = new HashSet<>();
        Set<Long> recargosVistos = new HashSet<>();
        boolean repetido = false;
        List<Imputar> renglones = pedido.imputaciones();
        for (int i = 0; i < renglones.size(); i++) {
            Imputar renglon = renglones.get(i);
            // GDS-20: cada renglón va a un comprobante O a un Recargo (ajusteId), nunca a los dos ni a ninguno.
            if (renglon.comprobanteId() == null && renglon.ajusteId() == null) {
                errores.put("imputaciones[" + i + "].comprobanteId", "Elegí el comprobante.");
                renglonesValidos = false;
            } else if (renglon.comprobanteId() != null && renglon.ajusteId() != null) {
                errores.put("imputaciones[" + i + "].ajusteId", MENSAJE_DEUDA_DOBLE);
                renglonesValidos = false;
            } else if (!(renglon.comprobanteId() != null ? comprobantesVistos : recargosVistos)
                    .add(renglon.comprobanteId() != null ? renglon.comprobanteId() : renglon.ajusteId())) {
                repetido = true;
            }
            if (renglon.importe() == null || renglon.importe().signum() <= 0) {
                errores.put("imputaciones[" + i + "].importe", "El importe tiene que ser mayor que cero.");
                renglonesValidos = false;
            } else if (decimalesDeMas(renglon.importe())) {
                errores.put("imputaciones[" + i + "].importe", "El importe admite hasta 2 decimales.");
                renglonesValidos = false;
            } else {
                suma = suma.add(renglon.importe());
            }
        }
        if (repetido) {
            errores.put("imputaciones", MENSAJE_COMPROBANTE_REPETIDO);
        } else if (totalValido && renglonesValidos && suma.compareTo(pedido.total()) > 0) {
            errores.put("imputaciones", MENSAJE_SUMA_MAYOR);
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** Más de 2 decimales de verdad: 100.1000 (ceros de más) no cuenta. */
    private static boolean decimalesDeMas(BigDecimal importe) {
        return importe.stripTrailingZeros().scale() > 2;
    }
}
