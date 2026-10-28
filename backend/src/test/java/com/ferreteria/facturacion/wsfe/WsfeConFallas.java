package com.ferreteria.facturacion.wsfe;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Decorador del puerto que inyecta fallas, SOLO para tests (en {@code main} no hay nada inyectable). Cada falla programada
 * la consume el siguiente {@code FECAESolicitar}, en el orden en que se programaron; sin fallas pendientes, todo pasa al
 * servicio real. {@code FECompUltimoAutorizado} y {@code FECompConsultar} pasan siempre.
 */
public final class WsfeConFallas implements ServicioWsfe {

    /** Lo que le puede pasar a un {@code FECAESolicitar}. */
    public enum Falla {
        /** Timeout de conexión: el pedido no llegó y ARCA no autorizó nada. */
        NO_LLEGA,
        /** Timeout de lectura sin que ARCA lo procesara: no autorizó nada, pero quien llama no lo sabe. */
        TIMEOUT_ANTES_DE_AUTORIZAR,
        /** Timeout de lectura con el comprobante ya autorizado: la respuesta se perdió. */
        TIMEOUT_DESPUES_DE_AUTORIZAR,
        /** HTTP 502 sin que ARCA lo procesara. */
        ERROR_502_ANTES_DE_AUTORIZAR,
        /** HTTP 502 con el comprobante ya autorizado. */
        ERROR_502_DESPUES_DE_AUTORIZAR
    }

    private record Programada(Falla falla, List<Obs> rechazo) {
    }

    private final ServicioWsfe real;
    private final Queue<Programada> programadas = new ConcurrentLinkedQueue<>();

    public WsfeConFallas(ServicioWsfe real) {
        this.real = real;
    }

    /** El siguiente {@code FECAESolicitar} sin falla programada termina así. */
    public WsfeConFallas programar(Falla falla) {
        programadas.add(new Programada(falla, null));
        return this;
    }

    /**
     * El siguiente {@code FECAESolicitar} sin falla programada vuelve rechazado (R, sin CAE) con esas observaciones en el
     * detalle, como en los ejemplos del manual (p. 83 y 86), sin llegar al servicio real: el número no se consume.
     */
    public WsfeConFallas rechazarConObservaciones(Obs... observaciones) {
        programadas.add(new Programada(null, List.of(observaciones)));
        return this;
    }

    @Override
    public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth auth, int ptoVta, int cbteTipo) {
        return real.feCompUltimoAutorizado(auth, ptoVta, cbteTipo);
    }

    @Override
    public FeCaeResponse feCaeSolicitar(Auth auth, FeCaeReq feCaeReq) {
        Programada siguiente = programadas.poll();
        if (siguiente == null) {
            return real.feCaeSolicitar(auth, feCaeReq);
        }
        if (siguiente.rechazo() != null) {
            return rechazo(auth, feCaeReq, siguiente.rechazo());
        }
        switch (siguiente.falla()) {
            case NO_LLEGA -> throw new PedidoNoLlegoException("Timeout de conexión con ARCA (falla inyectada).");
            case TIMEOUT_ANTES_DE_AUTORIZAR -> throw new ResultadoInciertoException(
                    "Timeout de lectura; ARCA no llegó a procesarlo (falla inyectada).");
            case ERROR_502_ANTES_DE_AUTORIZAR -> throw new ResultadoInciertoException(
                    "HTTP 502 de ARCA; no llegó a procesarlo (falla inyectada).");
            case TIMEOUT_DESPUES_DE_AUTORIZAR -> {
                real.feCaeSolicitar(auth, feCaeReq);
                throw new ResultadoInciertoException("Timeout de lectura; ARCA ya lo había autorizado (falla inyectada).");
            }
            case ERROR_502_DESPUES_DE_AUTORIZAR -> {
                real.feCaeSolicitar(auth, feCaeReq);
                throw new ResultadoInciertoException("HTTP 502 de ARCA; ya lo había autorizado (falla inyectada).");
            }
            default -> throw new IllegalStateException("Falla sin programar: " + siguiente.falla());
        }
    }

    @Override
    public FeCompConsultarResponse feCompConsultar(Auth auth, int cbteTipo, int ptoVta, long cbteNro) {
        return real.feCompConsultar(auth, cbteTipo, ptoVta, cbteNro);
    }

    private static FeCaeResponse rechazo(Auth auth, FeCaeReq pedido, List<Obs> observaciones) {
        FeCaeDetRequest det = pedido.feDetReq();
        // La respuesta inventada no tiene hora de proceso.
        FeCabResp cabecera = new FeCabResp(auth.cuit(), pedido.feCabReq().ptoVta(), pedido.feCabReq().cbteTipo(), null, 1,
                Resultado.R);
        FeCaeDetResponse detalle = new FeCaeDetResponse(det.concepto(), det.docTipo(), det.docNro(), det.cbteDesde(),
                det.cbteHasta(), det.cbteFch(), Resultado.R, null, null, observaciones);
        return new FeCaeResponse(cabecera, detalle, List.of());
    }
}
