package com.ferreteria.facturacion.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * El ejecutor propio de {@code facturacion}: los hilos en que la venta pide su CAE después del commit
 * ({@code FacturacionParaVenta#autorizar}), sin transacción ni conexión tomada. La venta espera el resultado un rato
 * acotado ({@code app.facturacion.venta.espera}); lo que no termina a tiempo sigue en estos hilos.
 *
 * <p>Tamaño fijo ({@code app.facturacion.venta.hilos}) con una cola acotada ({@code app.facturacion.venta.cola}): con todo
 * ocupado, el pedido se rechaza y la venta responde con el estado que haya, sin esperar. Al apagar, deja de aceptar pedidos y
 * espera a los que están en curso hasta {@code app.facturacion.venta.espera-al-apagar}: así una llamada a ARCA en vuelo
 * termina y guarda su resultado en vez de quedar en verificación.
 *
 * <p>Ojo: en Spring Boot 3.3 el {@code applicationTaskExecutor} se autoconfigura solo si no hay ningún {@code Executor} en el
 * contexto, así que con este bean no existe. Hoy nadie lo usa (sin {@code @Async} ni controllers asincrónicos); quien los
 * sume tiene que declarar su propio ejecutor y no usar este.
 */
@Configuration
public class EjecutorFiscalConfig {

    /** El nombre del bean, para pedirlo con {@code @Qualifier}. */
    public static final String EJECUTOR_FISCAL = "ejecutorFiscal";

    @Bean(EJECUTOR_FISCAL)
    public ThreadPoolTaskExecutor ejecutorFiscal(@Value("${app.facturacion.venta.hilos}") int hilos,
            @Value("${app.facturacion.venta.cola}") int cola,
            @Value("${app.facturacion.venta.espera-al-apagar}") Duration esperaAlApagar) {
        ThreadPoolTaskExecutor ejecutor = new ThreadPoolTaskExecutor();
        ejecutor.setCorePoolSize(hilos);
        ejecutor.setMaxPoolSize(hilos);
        ejecutor.setQueueCapacity(cola);
        ejecutor.setThreadNamePrefix("facturacion-");
        ejecutor.setWaitForTasksToCompleteOnShutdown(true);
        ejecutor.setAwaitTerminationMillis(esperaAlApagar.toMillis());
        return ejecutor;
    }
}
