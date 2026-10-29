package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.support.RelojControlableConfig;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * El contexto de los tests del motor: el reloj controlable ({@link RelojControlableConfig}), que ven el motor y el simulador,
 * y un {@link WsfePorAmbiente} principal que, para SIMULADOR, devuelve {@link WsfeDePrueba} (el simulador real con fallas y
 * pausas). El de {@code main} sigue existiendo; el motor recibe este.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RelojControlableConfig.class)
public class MotorDePruebaConfig {

    @Bean
    public WsfeDePrueba wsfeDePrueba(@Qualifier("simuladorWsfe") ServicioWsfe simulador) {
        return new WsfeDePrueba(simulador);
    }

    @Bean
    @Primary
    public WsfePorAmbiente wsfePorAmbienteDePrueba(WsfeDePrueba wsfe) {
        return new WsfePorAmbiente(Map.of(Ambiente.SIMULADOR, wsfe));
    }
}
