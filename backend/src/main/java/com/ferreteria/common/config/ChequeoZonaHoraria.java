package com.ferreteria.common.config;

import com.ferreteria.common.HoraArgentina;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Al terminar de arrancar, avisa con un WARN si la zona de la JVM no es la de Buenos Aires ({@link HoraArgentina#ZONA}). Las
 * fechas que se guardan dependen de que la zona no cambie entre un deploy y el siguiente (ver docs/spec.md, «Despliegue: zona horaria»):
 * este chequeo solo avisa, NUNCA frena el arranque. Compara por id: un alias de la misma zona ({@code America/Buenos_Aires})
 * da un aviso de más, aceptado a cambio de no depender de reglas de la zona.
 */
@Component
public class ChequeoZonaHoraria {

    private static final Logger log = LoggerFactory.getLogger(ChequeoZonaHoraria.class);

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        verificar(ZoneId.systemDefault());
    }

    /** WARN si la zona de la JVM difiere (por id) de la de la aplicación; nunca lanza. */
    void verificar(ZoneId zonaDeLaJvm) {
        String esperada = HoraArgentina.ZONA.getId();
        if (!esperada.equals(zonaDeLaJvm.getId())) {
            log.warn("La zona horaria de la JVM ({}) no coincide con la de la aplicación ({}). Arrancá con TZ={} o "
                    + "-Duser.timezone={}.", zonaDeLaJvm.getId(), esperada, esperada, esperada);
        }
    }
}
