package com.ferreteria.facturacion.reintento;

import com.ferreteria.facturacion.model.ClaveDeSerie;
import com.ferreteria.facturacion.motor.Atencion;
import com.ferreteria.facturacion.motor.MotorFiscal;
import com.ferreteria.facturacion.motor.PasosFiscales;
import com.ferreteria.facturacion.motor.PoliticaDeReintento;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * El job de reintento de los pendientes de CAE (rama 7, cu-07-contingencias S2, S7 y C5). Una pasada recorre las
 * ferreterías activas y, de cada una, sus series con trabajo ({@link PasosFiscales#seriesConTrabajo}: con algún registro en
 * trámite, sin un backoff vigente y sin alertas, que resuelve soporte), en orden FIFO. Cada serie la atiende el motor
 * ({@link MotorFiscal#atender(com.ferreteria.facturacion.model.Ambiente, int, int, Instant)}): la toma con el lease de la
 * base o, si la tiene otro (la venta u otra JVM), la saltea (OCUPADA); resuelve primero el envío en duda, manda los
 * pendientes en FIFO y corta en el primer error transitorio. Cómo terminó va al backoff de la serie
 * ({@link PasosFiscales#registrarPasada}). Un pendiente que ya no entra en la ventana de fechas lo pasa el motor a
 * FUERA_DE_FECHA, y la serie deja de tener trabajo por él (S7).
 *
 * <p>La pasada tiene un tope ({@link PoliticaDeReintento#topePasada()}) desde que empieza, medido con el {@code Clock}: se
 * mira antes de cada ferretería y de cada serie, y el motor lo mira entre un registro y el siguiente. Sin tiempo, la pasada
 * corta y lo que falta queda para la siguiente. También corta, antes de tomar otra ferretería u otra serie, cuando quien la
 * corre avisa que se está apagando ({@link ProgramacionDelReintento}).
 *
 * <p>Molde de tenant de {@code RecalculoDiarioJob}: recorre las ferreterías con {@code FerreteriaRepository}, que no es de
 * tenant, y por cada una fija su {@code TenantContext} y lo limpia en un finally; después todo es {@code *Scoped}, así que
 * no hace falta {@code @UnscopedTenantAccess}. Lo que falla en una serie o en una ferretería queda en el log y no frena a
 * las demás. No es transaccional: cada paso del motor va en su transacción corta, y la llamada a ARCA, sin ninguna. No
 * agrega locks propios: el motor serializa cada serie con el lease de la base, que vale entre las dos JVM de un deploy.
 *
 * <p>No tiene {@code @Scheduled}: lo corre {@link ProgramacionDelReintento} en su propio hilo, y los tests lo llaman directo.
 */
@Component
public class ReintentoFiscalJob {

    private static final Logger log = LoggerFactory.getLogger(ReintentoFiscalJob.class);

    private final FerreteriaRepository ferreterias;
    private final PasosFiscales pasos;
    private final MotorFiscal motor;
    private final PoliticaDeReintento politica;
    private final Clock reloj;

    public ReintentoFiscalJob(FerreteriaRepository ferreterias, PasosFiscales pasos, MotorFiscal motor,
            PoliticaDeReintento politica, Clock reloj) {
        this.ferreterias = ferreterias;
        this.pasos = pasos;
        this.motor = motor;
        this.politica = politica;
        this.reloj = reloj;
    }

    /** Una pasada hasta su tope, sin apagado: la que llaman los tests. */
    public void pasada() {
        pasada(() -> true);
    }

    /**
     * Una pasada que, antes de tomar otra ferretería u otra serie, pregunta si sigue y si le queda tiempo: si no, corta ahí y
     * lo que falta queda para la próxima. Lo que ya tomó (la serie que está atendiendo el motor) termina igual. Nada sale de
     * acá: lo que falla queda en el log.
     *
     * @param sigue false cuando la aplicación se está apagando
     */
    public void pasada(BooleanSupplier sigue) {
        new Pasada(sigue, reloj.instant().plus(politica.topePasada())).correr();
    }

    /** Una pasada: hasta cuándo puede tomar trabajo, y si ya cortó. */
    private final class Pasada {

        private final BooleanSupplier sigue;
        private final Instant limite;
        private boolean cortada;

        Pasada(BooleanSupplier sigue, Instant limite) {
            this.sigue = sigue;
            this.limite = limite;
        }

        void correr() {
            try {
                for (Long ferreteriaId : ferreterias.idsActivas()) {
                    if (!puedeSeguir()) {
                        return;
                    }
                    atender(ferreteriaId);
                }
            } catch (RuntimeException e) {
                log.error("Falló la pasada del reintento fiscal: lo que falta sigue en la próxima.", e);
            }
        }

        /** Las series con trabajo de la ferretería, con su {@code TenantContext}. */
        private void atender(Long ferreteriaId) {
            TenantContext.setCurrentFerreteriaId(ferreteriaId);
            try {
                for (ClaveDeSerie serie : pasos.seriesConTrabajo(reloj.instant())) {
                    if (!puedeSeguir()) {
                        return;
                    }
                    atender(ferreteriaId, serie);
                }
            } catch (RuntimeException e) {
                log.error("Falló el reintento fiscal de la ferretería {}: sigue en la próxima pasada.", ferreteriaId, e);
            } finally {
                TenantContext.clear();
            }
        }

        /** Una serie: la atiende el motor hasta el límite de la pasada, y cómo terminó va a su backoff. */
        private void atender(Long ferreteriaId, ClaveDeSerie serie) {
            try {
                Atencion atencion = motor.atender(serie.ambiente(), serie.puntoVenta(), serie.cbteTipo(), limite);
                pasos.registrarPasada(serie, atencion, reloj.instant());
                log.info("Reintento fiscal de la ferretería {}, serie {} {}-{}: {}.", ferreteriaId, serie.ambiente(),
                        serie.puntoVenta(), serie.cbteTipo(), atencion);
            } catch (RuntimeException e) {
                log.error("Falló el reintento fiscal de la ferretería {}, serie {} {}-{}: sigue en la próxima pasada.",
                        ferreteriaId, serie.ambiente(), serie.puntoVenta(), serie.cbteTipo(), e);
            }
        }

        /** Si puede tomar otra ferretería u otra serie: no se está apagando y le queda tiempo. Corta una sola vez. */
        private boolean puedeSeguir() {
            if (cortada) {
                return false;
            }
            if (!sigue.getAsBoolean()) {
                log.info("Apagado: la pasada del reintento fiscal no toma otra serie; lo que quedó en vuelo lo retoma la "
                        + "próxima pasada.");
                cortada = true;
            } else if (!reloj.instant().isBefore(limite)) {
                log.info("La pasada del reintento fiscal llegó a su tope ({}): lo que falta sigue en la próxima.",
                        politica.topePasada());
                cortada = true;
            }
            return !cortada;
        }
    }
}
