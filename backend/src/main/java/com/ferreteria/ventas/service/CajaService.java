package com.ferreteria.ventas.service;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.EstadoCaja;
import com.ferreteria.ventas.model.ImporteCaja;
import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.ResumenCaja;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.ReciboRepository;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Caja diaria (CU-10). Una sola caja abierta por ferreteria, la usan todos los usuarios. Sin caja abierta del dia no se
 * vende ni se cargan movimientos; la de otro dia solo se puede cerrar. El cierre es ciego: el que cierra manda el
 * contado y recien ahi ve el esperado y la diferencia. Ningun metodo de este servicio le muestra los totales de una caja
 * abierta a quien no sea el Dueno: eso lo garantiza el controlador al exponer {@link #detalle(Long)} solo al Dueno.
 * La hora sale del reloj inyectado (Buenos Aires), asi el "dia" de la caja se puede fijar en los tests.
 */
@Service
public class CajaService {

    static final int TAMANO_MAXIMO = 100;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    private final CajaRepository cajaRepository;
    private final MovimientoCajaRepository movimientoRepository;
    private final PagoRepository pagoRepository;
    private final ReciboRepository reciboRepository;
    private final Clock reloj;

    public CajaService(CajaRepository cajaRepository, MovimientoCajaRepository movimientoRepository,
            PagoRepository pagoRepository, ReciboRepository reciboRepository, Clock reloj) {
        this.cajaRepository = cajaRepository;
        this.movimientoRepository = movimientoRepository;
        this.pagoRepository = pagoRepository;
        this.reciboRepository = reciboRepository;
        this.reloj = reloj;
    }

    /** La caja abierta (de hoy o de otro dia) o ninguna, y el efectivo que se propone para abrir. Sin totales. */
    @Transactional(readOnly = true)
    public CajaVigente vigente() {
        BigDecimal propuesto = cajaRepository.ultimoContadoScoped().orElse(BigDecimal.ZERO);
        return cajaRepository.abiertaScoped()
                .map(caja -> new CajaVigente(caja, caja.estado(hoy()), nombre(caja.getUsuarioAperturaId()), propuesto))
                .orElseGet(() -> new CajaVigente(null, null, null, propuesto));
    }

    /**
     * Abre la caja de la ferreteria con el efectivo inicial (cero o mas). Con una abierta, de cualquier usuario o dia,
     * da 409. Si dos la abren a la vez, el UNIQUE de la base deja una sola y el otro recibe el mismo 409.
     */
    @Transactional
    public CajaVigente abrir(BigDecimal efectivoInicial, Long usuarioId) {
        ImporteCaja.validarEfectivo("efectivo inicial", efectivoInicial).ifPresent(mensaje -> {
            throw new DatosInvalidosException(Map.of("efectivoInicial", mensaje));
        });
        if (cajaRepository.abiertaScoped().isPresent()) {
            throw yaHayUnaAbierta();
        }
        BigDecimal contadoAnterior = cajaRepository.ultimoContadoScoped().orElse(null);
        Caja caja = Caja.abrir(TenantContext.requireFerreteriaId(), usuarioId, efectivoInicial, contadoAnterior, ahora());
        try {
            caja = cajaRepository.saveAndFlush(caja);
        } catch (DataIntegrityViolationException e) {
            if (ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                throw yaHayUnaAbierta();
            }
            throw e;
        }
        return new CajaVigente(caja, caja.estado(hoy()), nombre(usuarioId), contadoAnterior);
    }

    /**
     * Ingreso, gasto o retiro en la caja abierta del dia. El retiro es solo del Dueno (403 antes de mirar la caja). La
     * caja ajena da 404; la cerrada o la de otro dia, 422; los datos invalidos, 400 con un mensaje por campo.
     */
    @Transactional
    public MovimientoRegistrado registrarMovimiento(Long cajaId, TipoMovimientoCaja tipo, BigDecimal importe,
            String motivo, AuthUser usuario) {
        if (tipo == TipoMovimientoCaja.RETIRO && usuario.rol() != Rol.DUENO) {
            throw new AccessDeniedException("El retiro es solo del Dueño");
        }
        Caja caja = cajaRepository.paraMovimientoScoped(cajaId)
                .orElseThrow(() -> new ResourceNotFoundException("Caja no encontrada"));
        switch (caja.estado(hoy())) {
            case CERRADA -> throw new ReglaDeNegocioException("La caja está cerrada: no admite movimientos.");
            case DE_OTRO_DIA -> throw new ReglaDeNegocioException("La caja del " + dia(caja)
                    + " sigue abierta. Cerrala antes de cargar movimientos.");
            case ABIERTA -> { }
        }
        Map<String, String> errores = MovimientoCaja.validar(tipo, importe, motivo);
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
        MovimientoCaja movimiento = movimientoRepository.save(MovimientoCaja.de(TenantContext.requireFerreteriaId(),
                caja.getId(), tipo, importe, motivo, usuario.userId(), ahora()));
        return new MovimientoRegistrado(movimiento, nombre(usuario.userId()));
    }

    /**
     * Cierre ciego: toma la caja con lock exclusivo (espera a las ventas en curso), calcula los totales y guarda el
     * contado, el esperado y la diferencia. Se permite cerrar la caja de otro dia. Cerrada da 409: es inmutable.
     */
    @Transactional
    public DetalleCaja cerrar(Long cajaId, BigDecimal efectivoContado, Long usuarioId) {
        Caja caja = cajaRepository.paraCerrarScoped(cajaId)
                .orElseThrow(() -> new ResourceNotFoundException("Caja no encontrada"));
        if (!caja.estaAbierta()) {
            throw new ConflictException("Esta caja ya está cerrada.");
        }
        ImporteCaja.validarEfectivo("efectivo contado", efectivoContado).ifPresent(mensaje -> {
            throw new DatosInvalidosException(Map.of("efectivoContado", mensaje));
        });
        ResumenCaja resumen = resumen(caja);
        caja.cerrar(usuarioId, efectivoContado, resumen.efectivoEsperado(), ahora());
        return detalle(caja, resumen);
    }

    /** La caja con sus totales y movimientos: en vivo si esta abierta (sin contado), el cierre si esta cerrada. */
    @Transactional(readOnly = true)
    public DetalleCaja detalle(Long cajaId) {
        Caja caja = cajaRepository.findScoped(cajaId)
                .orElseThrow(() -> new ResourceNotFoundException("Caja no encontrada"));
        return detalle(caja, resumen(caja));
    }

    /** Las cajas cerradas, mas nuevas primero. */
    @Transactional(readOnly = true)
    public HistorialCajas cerradas(int page, int size) {
        Page<Caja> pagina = cajaRepository.cerradasScoped(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
        Set<Long> usuarios = new HashSet<>();
        pagina.forEach(c -> {
            usuarios.add(c.getUsuarioAperturaId());
            usuarios.add(c.getUsuarioCierreId());
        });
        usuarios.remove(null);
        return new HistorialCajas(pagina, cajaRepository.nombresScoped(usuarios));
    }

    /**
     * La caja en la que se cobra una venta (CU-10): la abierta hoy, tomada con lock compartido para que el cierre espere
     * a esta venta. Sin caja abierta, CAJA_CERRADA; con la de un dia anterior, CAJA_DE_OTRO_DIA. Corre dentro de la
     * transaccion de la venta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long cajaParaVender() {
        Caja caja = cajaAbiertaCompartida().orElseThrow(() ->
                new VentaRechazadaException(Motivo.CAJA_CERRADA, "Abrí la caja para vender.", List.of()));
        if (caja.estado(hoy()) == EstadoCaja.DE_OTRO_DIA) {
            throw new VentaRechazadaException(Motivo.CAJA_DE_OTRO_DIA,
                    "La caja del " + dia(caja) + " sigue abierta. Cerrala antes de vender.", List.of());
        }
        return caja.getId();
    }

    /**
     * La caja en la que se cobra una cuenta corriente (CC-035, CC-054): la misma que para vender (abierta hoy, lock compartido
     * hasta el commit del cobro, así el cierre espera a esta cobranza y el recibo entra en su esperado). Sin caja abierta,
     * CAJA_CERRADA; con la de un día anterior, CAJA_DE_OTRO_DIA. Es el primer lock de la cobranza (caja S → cliente X → ...).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long cajaParaCobrar() {
        Caja caja = cajaAbiertaCompartida().orElseThrow(() ->
                new CobranzaRechazadaException(CobranzaRechazadaException.Motivo.CAJA_CERRADA, "Abrí la caja para cobrar."));
        if (caja.estado(hoy()) == EstadoCaja.DE_OTRO_DIA) {
            throw new CobranzaRechazadaException(CobranzaRechazadaException.Motivo.CAJA_DE_OTRO_DIA,
                    "La caja del " + dia(caja) + " sigue abierta. Cerrala antes de cobrar.");
        }
        return caja.getId();
    }

    /**
     * La caja en la que se ANULA un cobro (CC-058, CC-085): la misma caja abierta de hoy, con el mismo lock compartido (así el
     * cierre espera a esta anulación y la absorbe en su resumen; una anulación que llega después recibe CAJA_CERRADA). Es el
     * primer lock de la anulación (caja S → cliente X → ...). La caja ORIGINAL del recibo (posiblemente cerrada) no se lee ni se
     * bloquea. Sin caja abierta, CAJA_CERRADA; con la de un día anterior, CAJA_DE_OTRO_DIA.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long cajaParaAnular() {
        Caja caja = cajaAbiertaCompartida().orElseThrow(() ->
                new CobranzaRechazadaException(CobranzaRechazadaException.Motivo.CAJA_CERRADA, "Abrí la caja para anular."));
        if (caja.estado(hoy()) == EstadoCaja.DE_OTRO_DIA) {
            throw new CobranzaRechazadaException(CobranzaRechazadaException.Motivo.CAJA_DE_OTRO_DIA,
                    "La caja del " + dia(caja) + " sigue abierta. Cerrala antes de anular.");
        }
        return caja.getId();
    }

    /** La caja abierta de la ferretería con lock compartido (FOR SHARE): lo comparten vender, cobrar y anular. */
    private Optional<Caja> cajaAbiertaCompartida() {
        return cajaRepository.abiertaParaVenderScoped();
    }

    private ResumenCaja resumen(Caja caja) {
        return ResumenCaja.de(caja.getEfectivoInicial(), pagoRepository.totalesDeCajaScoped(caja.getId()),
                reciboRepository.totalesDeCajaScoped(caja.getId()), reciboRepository.totalesAnuladosDeCajaScoped(caja.getId()),
                movimientoRepository.totalesScoped(caja.getId()));
    }

    private DetalleCaja detalle(Caja caja, ResumenCaja resumen) {
        List<MovimientoCaja> movimientos = movimientoRepository.deCajaScoped(caja.getId());
        Set<Long> usuarios = new HashSet<>();
        Stream.concat(Stream.of(caja.getUsuarioAperturaId(), caja.getUsuarioCierreId()),
                        movimientos.stream().map(MovimientoCaja::getUsuarioId))
                .filter(Objects::nonNull)
                .forEach(usuarios::add);
        return new DetalleCaja(caja, caja.estado(hoy()), resumen, movimientos, cajaRepository.nombresScoped(usuarios));
    }

    private String nombre(Long usuarioId) {
        return cajaRepository.nombresScoped(Set.of(usuarioId)).get(usuarioId);
    }

    private static ConflictException yaHayUnaAbierta() {
        return new ConflictException("Ya hay una caja abierta.");
    }

    private static String dia(Caja caja) {
        return caja.getFechaApertura().format(DIA);
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    private LocalDateTime ahora() {
        return LocalDateTime.now(reloj);
    }
}
