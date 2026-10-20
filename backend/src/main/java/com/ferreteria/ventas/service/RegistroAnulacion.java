package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoAnulacion;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ReciboRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anula el recibo de una cobranza en UNA transacción (CC-056..CC-062, CC-065, CC-066, CC-085): marca el recibo ANULADO con
 * quién, cuándo, en qué caja y por qué, y le pide a {@code clientes} el contra-asiento (imputaciones a no vigentes, una
 * {@code ANULACION} en el libro y el saldo sube en el total). Ventas orquesta recibo, caja, rol, estado y motivo; clientes es el
 * único escritor del libro, de las imputaciones y del saldo y se defiende solo.
 *
 * <p><b>Orden canónico de locks</b> (el mismo prefijo que cobrar y vender, para que no haya ciclos):
 * <pre>
 * caja (S, FOR SHARE) → CLIENTE (X, FOR UPDATE nativo) → [el PRIMER SELECT plano: el recibo] → UPDATE recibo (saveAndFlush)
 *   → imputaciones del recibo por PK asc (FOR UPDATE) → UPDATE imputacion.vigente → INSERT ANULACION → UPDATE cliente.saldo
 * </pre>
 * No toma el numerador de recibos (no consume número): dos anulaciones de clientes distintos solo comparten la caja en S. La caja
 * ORIGINAL del recibo (posiblemente cerrada) no se lee ni se bloquea: el recibo guarda la caja abierta de hoy como caja de la
 * anulación.
 *
 * <p><b>Regla estructural</b> (la misma de {@link RegistroCobranza}): NINGUNA llamada a repositorio ni SELECT plano antes de tomar
 * al cliente, salvo la caja (lectura bloqueante). La foto REPEATABLE READ de InnoDB nace con la PRIMERA lectura plana: si el
 * recibo se leyera antes del lock del cliente, una segunda anulación del mismo recibo que esperó el turno vería EMITIDO con la foto
 * vieja y daría un 500 (IllegalStateException del puerto) en vez del 409. Por eso la resolución recibo → cliente se hace ANTES,
 * en {@link #clienteDelRecibo} (otra transacción de solo lectura, sin locks, porque {@code recibo.cliente_id} es inmutable), y el
 * recibo se vuelve a leer ACÁ, con el cliente ya en X. {@code RegistroAnulacionTest} fija el orden con un {@code InOrder} y con
 * la verificación de que nada se tocó antes del cliente; {@code AnulacionConcurrenciaIntegrationTest} y la traza SQL lo fijan
 * contra MySQL real.
 *
 * <p>Compatibilidad con {@link RegistroVenta} y {@link RegistroCobranza}: mismo prefijo caja(S) → cliente(X); ninguna toma
 * presupuesto, remito ni stock, y la anulación tampoco numerador. Contra el cierre de caja: el cierre toma la caja en X y nunca
 * toma al cliente, así que espera a las anulaciones en vuelo (la tienen en S) y las que llegan después ven la caja cerrada
 * (CAJA_CERRADA); el resumen del cierre, leído DESPUÉS del lock X, ve toda anulación comprometida.
 *
 * <p>El que llama ({@link AnulacionService}) no tiene transacción: valida el cuerpo, resuelve el cliente y reintenta los
 * deadlocks. Si algo falla, la transacción entera se revierte (recibo EMITIDO, imputaciones vigentes, sin contra-asiento).
 */
@Service
public class RegistroAnulacion {

    static final String MENSAJE_RECIBO_INEXISTENTE = "Recibo no encontrado";
    static final String MENSAJE_MOTIVO_CORTO = "Escribí el motivo de la anulación (al menos 5 letras).";
    static final String MENSAJE_MOTIVO_LARGO = "El motivo admite hasta 200 caracteres.";
    static final int MOTIVO_MINIMO = 5;
    static final int MOTIVO_MAXIMO = 200;

    private final CajaService cajaService;
    private final ClienteDelDocumento clienteDelDocumento;
    private final ReciboRepository reciboRepository;
    private final CuentaCorrienteParaCobranza cuentaCorriente;
    private final CajaRepository cajaRepository;
    private final Clock reloj;

    public RegistroAnulacion(CajaService cajaService, ClienteDelDocumento clienteDelDocumento, ReciboRepository reciboRepository,
            CuentaCorrienteParaCobranza cuentaCorriente, CajaRepository cajaRepository, Clock reloj) {
        this.cajaService = cajaService;
        this.clienteDelDocumento = clienteDelDocumento;
        this.reciboRepository = reciboRepository;
        this.cuentaCorriente = cuentaCorriente;
        this.cajaRepository = cajaRepository;
        this.reloj = reloj;
    }

    /**
     * El motivo de la anulación recortado (CC-056, S-1): al menos {@value #MOTIVO_MINIMO} caracteres tras recortar los extremos
     * (no se exigen letras: «12345» vale) y como máximo {@value #MOTIVO_MAXIMO}. Si no, 400 con la clave {@code motivo}. Puro:
     * no toca la base.
     */
    public static String validarMotivo(String motivo) {
        String recortado = motivo == null ? "" : motivo.trim();
        if (recortado.length() < MOTIVO_MINIMO) {
            throw new DatosInvalidosException(Map.of("motivo", MENSAJE_MOTIVO_CORTO));
        }
        if (recortado.length() > MOTIVO_MAXIMO) {
            throw new DatosInvalidosException(Map.of("motivo", MENSAJE_MOTIVO_LARGO));
        }
        return recortado;
    }

    /**
     * El cliente del recibo (CC-085), leído en una transacción PROPIA de solo lectura y sin locks: así no fija la foto de la
     * transacción que anula. Seguro porque el cliente de un recibo no cambia nunca y se re-verifica bajo lock en
     * {@link #registrar}. Un recibo inexistente o de otra ferretería es 404.
     */
    @Transactional(readOnly = true)
    public Long clienteDelRecibo(Long reciboId) {
        return reciboRepository.clienteDeScoped(reciboId).orElseThrow(() -> new ResourceNotFoundException(MENSAJE_RECIBO_INEXISTENTE));
    }

    /**
     * Anula el recibo, o lanza el rechazo que corresponda (caja cerrada o de otro día 422, cliente desactivado 422, recibo ya
     * anulado 409). Va en el orden del Javadoc de la clase. {@code motivo} llega ya validado por la fachada.
     */
    @Transactional
    public CobroRegistrado registrar(Long reciboId, Long clienteId, String motivo, Long usuarioId) {
        // CC-058: sin caja abierta del día no se anula. Primer lock (S): el cierre espera a esta anulación.
        Long cajaId = cajaService.cajaParaAnular();
        // CC-059: el cliente se TOMA acá (X), antes de cualquier lectura plana. Ajeno 404, desactivado 422.
        clienteDelDocumento.resolverParaAnulacion(clienteId);

        // Recién ahora el primer SELECT plano: con el cliente en X la foto ve toda anulación o cobranza que confirmó antes.
        Recibo recibo = reciboRepository.findScoped(reciboId)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_RECIBO_INEXISTENTE));
        if (!clienteId.equals(recibo.getClienteId())) {
            throw new IllegalStateException("El recibo " + reciboId + " no es del cliente " + clienteId + ".");
        }
        if (recibo.isAnulado()) {
            throw yaAnulado(recibo);
        }

        recibo.anular(usuarioId, LocalDateTime.now(reloj), cajaId, motivo);
        // El flush fija acá la posición del lock X del recibo (antes que las imputaciones), en vez de dejarlo al auto-flush.
        reciboRepository.saveAndFlush(recibo);

        cuentaCorriente.asentarAnulacion(new AsientoAnulacion(clienteId, recibo.getId(), recibo.getNumero(), recibo.getTotal(),
                recibo.getMotivoAnulacion(), usuarioId));

        // Los nombres se leen al final (lecturas planas inocuas, posteriores a todos los locks).
        List<ImputacionAsentada> originales = cuentaCorriente.imputacionesDeRecibo(recibo.getId());
        Map<Long, String> nombres = nombres(recibo.getUsuarioId(), usuarioId);
        return new CobroRegistrado(recibo, originales, nombres.get(recibo.getUsuarioId()), false, nombres.get(usuarioId));
    }

    /**
     * Si el recibo está anulado, lanza el 409 con quién y cuándo; si no (o no existe), vuelve sin error. La fachada la usa, en una
     * transacción NUEVA, para traducir el choque del UNIQUE de la anulación (CC-061): nunca 500.
     */
    @Transactional(readOnly = true)
    public void rechazoSiAnulado(Long reciboId) {
        TenantContext.requireFerreteriaId();
        reciboRepository.findScoped(reciboId).filter(Recibo::isAnulado).ifPresent(recibo -> {
            throw yaAnulado(recibo);
        });
    }

    private CobranzaRechazadaException yaAnulado(Recibo recibo) {
        String nombre = nombres(recibo.getUsuarioAnulacionId()).get(recibo.getUsuarioAnulacionId());
        return CobranzaRechazadaException.reciboYaAnulado(nombre, recibo.getFechaAnulacion());
    }

    private Map<Long, String> nombres(Long... usuarios) {
        Set<Long> ids = new HashSet<>(List.of(usuarios));
        return cajaRepository.nombresScoped(ids);
    }
}
