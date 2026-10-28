package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.facturacion.wsfe.WsfeConFallas;
import com.ferreteria.support.BarreraDeLocks.Hito;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * El puerto que ve el motor en sus tests: el simulador real detrás de {@link WsfeConFallas} (las fallas programadas), más dos
 * cosas que el decorador no tiene. Cada una la consume el siguiente {@code FECAESolicitar}, en este orden:
 * <ol>
 *   <li>una pausa ({@link #pausarElSiguienteFeCaeSolicitar()}): la llamada avisa que llegó y espera a que el test la suelte,
 *       ANTES de pasar al simulador (que abre su propia transacción): mientras tanto se ve qué tiene tomado el motor;</li>
 *   <li>un rechazo en la cabecera ({@link #rechazarEnLaCabecera}): R en la cabecera, con {@code Errors} y sin detalle,
 *       como el ejemplo 3 del manual (p. 84), sin llegar al simulador.</li>
 * </ol>
 * Además, una excepción sin traducir ({@link #lanzarEnElSiguienteFeCaeSolicitar}), como la de un adaptador que no la
 * convierte en las del puerto: la lanza el siguiente {@code FECAESolicitar}, después de la pausa y sin llegar al simulador.
 * {@code FECompUltimoAutorizado} y {@code FECompConsultar} pasan siempre al simulador.
 */
public final class WsfeDePrueba implements ServicioWsfe {

    /** Una llamada detenida: avisa cuando llegó y sigue cuando el test la suelta. */
    public static final class Pausa {
        private final Hito llego = new Hito();
        private final Hito soltada = new Hito();

        public void esperarQueLlegue() {
            llego.esperar();
        }

        public void soltar() {
            soltada.avisar();
        }
    }

    private final ServicioWsfe simulador;
    private final Queue<Pausa> pausas = new ConcurrentLinkedQueue<>();
    private final Queue<List<Err>> rechazosEnLaCabecera = new ConcurrentLinkedQueue<>();
    private final Queue<RuntimeException> excepciones = new ConcurrentLinkedQueue<>();
    private final AtomicInteger solicitudes = new AtomicInteger();
    private volatile WsfeConFallas fallas;

    public WsfeDePrueba(ServicioWsfe simulador) {
        this.simulador = simulador;
        reiniciar();
    }

    /** Sin fallas, pausas, rechazos ni excepciones pendientes, y la cuenta de pedidos en 0. */
    public void reiniciar() {
        fallas = new WsfeConFallas(simulador);
        pausas.clear();
        rechazosEnLaCabecera.clear();
        excepciones.clear();
        solicitudes.set(0);
    }

    /** Las fallas programables ({@code programar}, {@code rechazarConObservaciones}). */
    public WsfeConFallas fallas() {
        return fallas;
    }

    public Pausa pausarElSiguienteFeCaeSolicitar() {
        Pausa pausa = new Pausa();
        pausas.add(pausa);
        return pausa;
    }

    public void rechazarEnLaCabecera(Err... errores) {
        rechazosEnLaCabecera.add(List.of(errores));
    }

    /** El siguiente {@code FECAESolicitar} lanza esa excepción, sin llegar al simulador. */
    public void lanzarEnElSiguienteFeCaeSolicitar(RuntimeException excepcion) {
        excepciones.add(excepcion);
    }

    /** Cuántos {@code FECAESolicitar} llegaron a este puerto. */
    public int solicitudes() {
        return solicitudes.get();
    }

    @Override
    public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth auth, int ptoVta, int cbteTipo) {
        return fallas.feCompUltimoAutorizado(auth, ptoVta, cbteTipo);
    }

    @Override
    public FeCaeResponse feCaeSolicitar(Auth auth, FeCaeReq feCaeReq) {
        solicitudes.incrementAndGet();
        Pausa pausa = pausas.poll();
        if (pausa != null) {
            pausa.llego.avisar();
            pausa.soltada.esperar();
        }
        RuntimeException excepcion = excepciones.poll();
        if (excepcion != null) {
            throw excepcion;
        }
        List<Err> errores = rechazosEnLaCabecera.poll();
        if (errores != null) {
            FeCabResp cabecera = new FeCabResp(auth.cuit(), feCaeReq.feCabReq().ptoVta(), feCaeReq.feCabReq().cbteTipo(),
                    null, 1, Resultado.R);
            return new FeCaeResponse(cabecera, null, errores);
        }
        return fallas.feCaeSolicitar(auth, feCaeReq);
    }

    @Override
    public FeCompConsultarResponse feCompConsultar(Auth auth, int cbteTipo, int ptoVta, long cbteNro) {
        return fallas.feCompConsultar(auth, cbteTipo, ptoVta, cbteNro);
    }
}
