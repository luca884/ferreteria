package com.ferreteria.facturacion.simulador;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabReq;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El simulador de WSFEv1 ({@value CodigoManual#VERSION_MANUAL}): numera, autoriza y consulta como ARCA, sin efecto
 * fiscal, y solo atiende el ambiente {@link Ambiente#SIMULADOR}. Su estado (el último autorizado de cada serie y cada
 * comprobante autorizado con todos sus campos) vive en las tablas de V33, de la ferretería del request
 * ({@code TenantContext}), y sobrevive a un reinicio.
 *
 * <p>Cada operación es como una llamada remota: espera la latencia configurada sin tener nada abierto y después corre en una
 * transacción propia ({@code REQUIRES_NEW}), así lo que autoriza no depende de la transacción de quien llama. Las
 * autorizaciones de una misma serie se serializan con el lock de su fila. Las validaciones están en {@link ReglasWsfe}.
 *
 * <p>Inferido (el manual no lo dice): el CAE son 14 dígitos al azar, como los de los ejemplos; {@code CAEFchVto} es la
 * CbteFch más 10 días, como en los cuatro ejemplos del manual; {@code FECompUltimoAutorizado} de una serie sin comprobantes
 * da 0; {@code FECompConsultar} de un comprobante que no existe da el error 602. Nada de esto es inyectable: las fallas
 * (timeouts, 502, rechazos) se simulan solo en los tests, con un decorador del puerto.
 */
@Service
public class SimuladorWsfe implements ServicioWsfe {

    /** ARCA trabaja en hora de Buenos Aires: de ahí salen la fecha de envío (N) y la de proceso. */
    private static final ZoneId ZONA_ARCA = HoraArgentina.ZONA;

    /** Inferido de los ejemplos del manual (p. 79, 82, 88 y 90): el CAE vence a los 10 días de la CbteFch. */
    private static final int DIAS_DE_VIGENCIA_DEL_CAE = 10;

    /** Los arrays van como texto JSON; mapper propio, así el formato guardado no depende de la configuración web. */
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final TypeReference<List<AlicIva>> LISTA_IVA = new TypeReference<>() {
    };
    private static final TypeReference<List<CbteAsoc>> LISTA_ASOCIADOS = new TypeReference<>() {
    };
    private static final TypeReference<List<Obs>> LISTA_OBSERVACIONES = new TypeReference<>() {
    };

    private final SerieSimuladaRepository series;
    private final ComprobanteSimuladoRepository comprobantes;
    private final TransactionTemplate escritura;
    private final TransactionTemplate lectura;
    private final Clock reloj;
    private final Duration latencia;
    private final ReglasWsfe reglas;

    public SimuladorWsfe(SerieSimuladaRepository series, ComprobanteSimuladoRepository comprobantes,
            PlatformTransactionManager transacciones, Clock reloj,
            @Value("${app.facturacion.simulador.latencia}") Duration latencia,
            @Value("${app.facturacion.simulador.tope-rg4444}") BigDecimal topeRg4444,
            @Value("${app.facturacion.simulador.minimo-fce}") BigDecimal minimoFce) {
        this.series = series;
        this.comprobantes = comprobantes;
        this.escritura = new TransactionTemplate(transacciones);
        this.escritura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lectura.setReadOnly(true);
        this.reloj = reloj;
        this.latencia = latencia;
        this.reglas = new ReglasWsfe(topeRg4444, minimoFce);
    }

    Duration latencia() {
        return latencia;
    }

    @Override
    public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth auth, int ptoVta, int cbteTipo) {
        atiende(auth);
        esperar();
        long ultimo = lectura.execute(estado -> series.buscarScoped(auth.cuit(), ptoVta, cbteTipo)
                .map(SerieSimulada::getUltimoNro).orElse(0L));
        return new FeCompUltimoAutorizadoResponse(ptoVta, cbteTipo, ultimo, List.of());
    }

    @Override
    public FeCaeResponse feCaeSolicitar(Auth auth, FeCaeReq feCaeReq) {
        atiende(auth);
        esperar();
        return escritura.execute(estado -> autorizar(auth.cuit(), feCaeReq));
    }

    @Override
    public FeCompConsultarResponse feCompConsultar(Auth auth, int cbteTipo, int ptoVta, long cbteNro) {
        atiende(auth);
        esperar();
        return lectura.execute(estado -> comprobantes.buscarScoped(auth.cuit(), ptoVta, cbteTipo, cbteNro)
                .map(comprobante -> new FeCompConsultarResponse(resultGet(ptoVta, cbteTipo, comprobante), List.of()))
                .orElseGet(() -> new FeCompConsultarResponse(null,
                        List.of(new Err(CodigoManual.E602.codigo(), CodigoManual.E602.texto())))));
    }

    /** Lo simulado nunca llega a ARCA real, y lo real nunca se simula: el simulador rechaza todo lo que no es SIMULADOR. */
    private static void atiende(Auth auth) {
        if (auth.ambiente() != Ambiente.SIMULADOR) {
            throw new IllegalArgumentException("El simulador de WSFEv1 solo atiende el ambiente SIMULADOR: un pedido de "
                    + auth.ambiente() + " va a ARCA.");
        }
    }

    private void esperar() {
        if (latencia.isZero() || latencia.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latencia.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PedidoNoLlegoException("Se cortó la espera del simulador de WSFEv1 antes de mandar el pedido.", e);
        }
    }

    private FeCaeResponse autorizar(long cuit, FeCaeReq pedido) {
        Instant proceso = reloj.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate hoy = LocalDate.ofInstant(proceso, ZONA_ARCA);
        FeCabReq cabecera = pedido.feCabReq();
        FeCaeDetRequest det = pedido.feDetReq();

        SerieSimulada serie = series.bloquearScoped(cuit, cabecera.ptoVta(), cabecera.cbteTipo());
        ReglasWsfe.Veredicto veredicto = reglas.evaluar(cabecera.cbteTipo(), det, hoy, serie.getUltimoNro(),
                serie.getUltimaCbteFch(), asociado -> impTotalDe(cuit, asociado));
        if (!veredicto.errores().isEmpty()) {
            // Ejemplo 3 del manual (p. 84): un error del pedido va en Errors, con R en la cabecera y sin detalle.
            return new FeCaeResponse(cabecera(cuit, cabecera, proceso, Resultado.R), null, veredicto.errores());
        }
        if (veredicto.rechazado()) {
            // Ejemplo 4 del manual (p. 86): el rechazo del comprobante va en sus Observaciones, con R y sin CAE.
            return respuesta(cuit, cabecera, det, det.cbteFch(), proceso, null, null, veredicto.rechazos());
        }

        LocalDate cbteFch = det.cbteFch() != null ? det.cbteFch() : hoy;
        String cae = String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 100_000_000_000_000L));
        LocalDate vencimiento = cbteFch.plusDays(DIAS_DE_VIGENCIA_DEL_CAE);
        PeriodoAsoc periodo = det.periodoAsoc();
        comprobantes.save(new ComprobanteSimulado(serie.getFerreteriaId(), serie.getId(), det.cbteDesde(), det.concepto(),
                det.docTipo(), det.docNro(), cbteFch, det.impTotal(), det.impTotConc(), det.impNeto(), det.impOpEx(),
                det.impTrib(), det.impIva(), det.monId(), det.monCotiz(), det.condicionIvaReceptorId(), json(det.iva()),
                json(det.cbtesAsoc()), periodo == null ? null : periodo.fchDesde(),
                periodo == null ? null : periodo.fchHasta(), cae, vencimiento, json(veredicto.observaciones()), proceso));
        serie.registrarAutorizado(det.cbteDesde(), cbteFch);
        return respuesta(cuit, cabecera, det, cbteFch, proceso, cae, vencimiento, veredicto.observaciones());
    }

    /** El ImpTotal de un comprobante asociado, si el simulador lo autorizó (su CUIT, o la emisora si no la trae). */
    private Optional<BigDecimal> impTotalDe(long cuitEmisor, CbteAsoc asociado) {
        long cuit = cuitEmisor;
        if (asociado.cuit() != null) {
            if (!asociado.cuit().matches("\\d{11}")) {
                return Optional.empty();
            }
            cuit = Long.parseLong(asociado.cuit());
        }
        return comprobantes.buscarScoped(cuit, asociado.ptoVta(), asociado.tipo(), asociado.nro())
                .map(ComprobanteSimulado::getImpTotal);
    }

    /** La respuesta con detalle: aprobada (con CAE) si trae CAE, rechazada (R, con los rechazos como Obs) si no. */
    private static FeCaeResponse respuesta(long cuit, FeCabReq cabecera, FeCaeDetRequest det, LocalDate cbteFch,
            Instant proceso, String cae, LocalDate vencimiento, List<Obs> observaciones) {
        Resultado resultado = cae != null ? Resultado.A : Resultado.R;
        FeCaeDetResponse detResp = new FeCaeDetResponse(det.concepto(), det.docTipo(), det.docNro(), det.cbteDesde(),
                det.cbteHasta(), cbteFch, resultado, cae, vencimiento, observaciones);
        return new FeCaeResponse(cabecera(cuit, cabecera, proceso, resultado), detResp, List.of());
    }

    private static FeCabResp cabecera(long cuit, FeCabReq cabecera, Instant proceso, Resultado resultado) {
        return new FeCabResp(cuit, cabecera.ptoVta(), cabecera.cbteTipo(), LocalDateTime.ofInstant(proceso, ZONA_ARCA),
                cabecera.cantReg(), resultado);
    }

    private static ResultGet resultGet(int ptoVta, int cbteTipo, ComprobanteSimulado c) {
        PeriodoAsoc periodo = c.getPeriodoAsocDesde() == null ? null
                : new PeriodoAsoc(c.getPeriodoAsocDesde(), c.getPeriodoAsocHasta());
        FeCaeDetRequest detalle = new FeCaeDetRequest(c.getConcepto(), c.getDocTipo(), c.getDocNro(), c.getCbteNro(),
                c.getCbteNro(), c.getCbteFch(), c.getImpTotal(), c.getImpTotConc(), c.getImpNeto(), c.getImpOpEx(),
                c.getImpTrib(), c.getImpIva(), c.getMonId(), c.getMonCotiz(), c.getCondicionIvaReceptorId(),
                leer(c.getIva(), LISTA_IVA), leer(c.getCbtesAsoc(), LISTA_ASOCIADOS), periodo);
        return new ResultGet(ptoVta, cbteTipo, detalle, Resultado.A, c.getCae(), "CAE", c.getCaeFchVto(),
                LocalDateTime.ofInstant(c.getFchProceso(), ZONA_ARCA), leer(c.getObservaciones(), LISTA_OBSERVACIONES));
    }

    private static String json(List<?> valores) {
        try {
            return JSON.writeValueAsString(valores);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar un array del comprobante simulado.", e);
        }
    }

    private static <T> List<T> leer(String json, TypeReference<List<T>> tipo) {
        try {
            return JSON.readValue(json, tipo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo leer un array del comprobante simulado.", e);
        }
    }
}
