package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * C6 y S5: la configuración de ARCA ({@code app.facturacion.arca}) está en el yml, con los valores de C6, y se valida al
 * arrancar contra el presupuesto del motor: dos llamadas (WSAA y WSFE) más la espera del ticket tienen que entrar en
 * {@code app.facturacion.motor.presupuesto-llamada}, y el lease del ticket tiene que durar más que una llamada. Si no, la
 * aplicación no arranca. Sin base: solo la configuración, con el {@code application.yml} real.
 */
class ConfiguracionArcaTest {

    /**
     * La configuración de ARCA con el {@code application.yml} del classpath y la conversión de Spring Boot (la que pone
     * {@code SpringApplication}, con «5s» a {@code Duration} en un {@code @Value}), como arranca la aplicación.
     */
    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(c -> c.getBeanFactory().setConversionService(new ApplicationConversionService()))
            .withUserConfiguration(ConfiguracionArca.class);

    @Test
    @DisplayName("el application.yml trae los valores de C6, y con el presupuesto de 60 s arranca")
    void losValoresDeC6() {
        contexto.run(c -> {
            assertThat(c).hasNotFailed();
            PropiedadesArca arca = c.getBean(PropiedadesArca.class);
            assertThat(arca.wsaa()).isEqualTo(new PropiedadesArca.Urls(
                    URI.create("https://wsaahomo.afip.gov.ar/ws/services/LoginCms"),
                    URI.create("https://wsaa.afip.gov.ar/ws/services/LoginCms")));
            assertThat(arca.wsfe()).isEqualTo(new PropiedadesArca.Urls(
                    URI.create("https://wswhomo.afip.gov.ar/wsfev1/service.asmx"),
                    URI.create("https://servicios1.afip.gov.ar/wsfev1/service.asmx")));
            assertThat(arca.timeoutConexion()).isEqualTo(Duration.ofSeconds(5));
            assertThat(arca.timeoutLectura()).isEqualTo(Duration.ofSeconds(20));
            assertThat(arca.lapsoTa()).isEqualTo(new PropiedadesArca.Lapsos(Duration.ofMinutes(10), Duration.ofMinutes(2),
                    Duration.ofMinutes(10)));
            assertThat(arca.margenRenovacion()).isEqualTo(Duration.ofMinutes(30));
            assertThat(arca.esperaWsaa()).isEqualTo(Duration.ofSeconds(60));
            assertThat(arca.esperaTaVigente()).isEqualTo(Duration.ofMinutes(10));
            assertThat(arca.esperaErrorSistema()).isEqualTo(Duration.ofMinutes(30));
            assertThat(arca.esperaTicket()).isEqualTo(Duration.ofSeconds(10));
            assertThat(arca.leaseTicket()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @Test
    @DisplayName("si 2 × (conexión + lectura) + espera del ticket no entra en el presupuesto del motor, no arranca")
    void elPresupuestoNoAlcanza() {
        contexto.withPropertyValues("app.facturacion.motor.presupuesto-llamada=59s").run(c -> assertThat(c).getFailure()
                .rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.facturacion.motor.presupuesto-llamada = 59s")
                .hasMessageContaining("2 × (5s + 20s) + 10s = 60s"));
        contexto.withPropertyValues("app.facturacion.arca.timeout-lectura=21s").run(c -> assertThat(c).getFailure()
                .rootCause().hasMessageContaining("2 × (5s + 21s) + 10s = 62s"));
    }

    @Test
    @DisplayName("si el lease del ticket no dura más que una llamada (conexión + lectura), no arranca")
    void elLeaseDelTicketNoCubreUnaLlamada() {
        contexto.withPropertyValues("app.facturacion.arca.lease-ticket=25s").run(c -> assertThat(c).getFailure()
                .rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.facturacion.arca.lease-ticket = 25s")
                .hasMessageContaining("5s + 20s = 25s"));
        contexto.withPropertyValues("app.facturacion.arca.lease-ticket=26s").run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    @DisplayName("sin una propiedad de ARCA no arranca, y el error la nombra")
    void faltaUnaPropiedad() {
        contexto.withPropertyValues("app.facturacion.arca.espera-ticket=").run(c -> assertThat(c).getFailure()
                .rootCause().hasMessageContaining("app.facturacion.arca.espera-ticket"));
        contexto.withPropertyValues("app.facturacion.arca.wsfe.produccion=").run(c -> assertThat(c).getFailure()
                .rootCause().hasMessageContaining("app.facturacion.arca.wsfe.produccion"));
    }
}
