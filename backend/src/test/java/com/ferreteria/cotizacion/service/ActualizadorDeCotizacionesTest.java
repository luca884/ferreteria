package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** El orden principal a respaldo, con fuentes falsas y un repositorio en memoria: no hay red ni base. */
class ActualizadorDeCotizacionesTest {

    private static final Instant AHORA = Instant.parse("2026-10-16T11:30:00Z");
    private static final Instant MERCADO = Instant.parse("2026-10-16T11:00:00Z");

    /** Una fuente falsa que cuenta cuantas veces la consultaron. */
    private static final class FuenteFalsa implements FuenteDeCotizaciones {
        private final String nombre;
        private final Supplier<List<CotizacionLeida>> respuesta;
        int consultas;

        FuenteFalsa(String nombre, Supplier<List<CotizacionLeida>> respuesta) {
            this.nombre = nombre;
            this.respuesta = respuesta;
        }

        @Override
        public String nombre() {
            return nombre;
        }

        @Override
        public List<CotizacionLeida> leer() {
            consultas++;
            return respuesta.get();
        }
    }

    private final Map<String, Cotizacion> almacen = new HashMap<>();
    private final CotizacionRepository repositorio = mock(CotizacionRepository.class);
    private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);

    @BeforeEach
    void repositorioEnMemoria() {
        when(repositorio.findByMonedaAndCasa(any(), any())).thenAnswer(
                i -> Optional.ofNullable(almacen.get(i.getArgument(0) + ":" + i.getArgument(1))));
        when(repositorio.save(any())).thenAnswer(i -> {
            Cotizacion c = i.getArgument(0);
            almacen.put(c.getMoneda() + ":" + c.getCasa(), c);
            return c;
        });
    }

    private ActualizadorDeCotizaciones actualizador(FuenteDeCotizaciones... fuentes) {
        return new ActualizadorDeCotizaciones(List.of(fuentes), repositorio,
                TransactionOperations.withoutTransaction(), reloj);
    }

    private static CotizacionLeida oficial(String compra, String venta) {
        return new CotizacionLeida("USD", "oficial", "Oficial", new BigDecimal(compra), new BigDecimal(venta), MERCADO);
    }

    private static List<CotizacionLeida> falla() {
        throw new FuenteNoDisponibleException("caida");
    }

    @Test
    @DisplayName("si la principal responde, guarda con fuente DOLARAPI y no consulta el respaldo")
    void principalOk() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(oficial("950", "1000")));
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("948", "998")));

        Optional<String> usada = actualizador(principal, respaldo).actualizar();

        assertThat(usada).contains("DOLARAPI");
        Cotizacion guardada = almacen.get("USD:oficial");
        assertThat(guardada.getCompra()).isEqualByComparingTo("950");
        assertThat(guardada.getVenta()).isEqualByComparingTo("1000");
        assertThat(guardada.getFuente()).isEqualTo("DOLARAPI");
        assertThat(principal.consultas).isEqualTo(1);
        assertThat(respaldo.consultas).isZero();
    }

    @Test
    @DisplayName("si la principal falla y el respaldo responde 948/998, guarda con fuente BLUELYTICS")
    void principalFallaYRespaldoOk() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", ActualizadorDeCotizacionesTest::falla);
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("948", "998")));

        Optional<String> usada = actualizador(principal, respaldo).actualizar();

        assertThat(usada).contains("BLUELYTICS");
        Cotizacion guardada = almacen.get("USD:oficial");
        assertThat(guardada.getCompra()).isEqualByComparingTo("948");
        assertThat(guardada.getVenta()).isEqualByComparingTo("998");
        assertThat(guardada.getFuente()).isEqualTo("BLUELYTICS");
        assertThat(principal.consultas).isEqualTo(1);
        assertThat(respaldo.consultas).isEqualTo(1);
    }

    @Test
    @DisplayName("si la principal devuelve una lista vacia, pasa al respaldo")
    void principalVaciaPasaAlRespaldo() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", List::of);
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("948", "998")));

        assertThat(actualizador(principal, respaldo).actualizar()).contains("BLUELYTICS");
        assertThat(almacen.get("USD:oficial").getFuente()).isEqualTo("BLUELYTICS");
    }

    @Test
    @DisplayName("una excepcion cualquiera de la principal tambien lleva al respaldo")
    void errorInesperadoDeLaPrincipal() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> {
            throw new IllegalStateException("bug del adaptador");
        });
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("948", "998")));

        assertThat(actualizador(principal, respaldo).actualizar()).contains("BLUELYTICS");
    }

    @Test
    @DisplayName("si las dos fallan no lanza, devuelve vacio y no toca el valor guardado")
    void lasDosFallan() {
        Cotizacion previa = new Cotizacion("USD", "oficial", "Oficial", new BigDecimal("900"), new BigDecimal("910"),
                "DOLARAPI", MERCADO.minusSeconds(7200), AHORA.minusSeconds(7200));
        almacen.put("USD:oficial", previa);
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", ActualizadorDeCotizacionesTest::falla);
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", ActualizadorDeCotizacionesTest::falla);

        Optional<String> usada = actualizador(principal, respaldo).actualizar();

        assertThat(usada).isEmpty();
        assertThat(almacen).hasSize(1);
        Cotizacion igual = almacen.get("USD:oficial");
        assertThat(igual.getVenta()).isEqualByComparingTo("910");
        assertThat(igual.getFuente()).isEqualTo("DOLARAPI");
        assertThat(igual.getObtenidaEn()).isEqualTo(AHORA.minusSeconds(7200));
        assertThat(principal.consultas).isEqualTo(1);
        assertThat(respaldo.consultas).isEqualTo(1);
    }

    @Test
    @DisplayName("si las dos devuelven listas vacias tampoco toca nada")
    void lasDosVacias() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", List::of);
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", List::of);

        assertThat(actualizador(principal, respaldo).actualizar()).isEmpty();
        assertThat(almacen).isEmpty();
    }

    @Test
    @DisplayName("obtenida_en sale del reloj inyectado y actualizada_en conserva la fecha del mercado")
    void fechasDelGuardado() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(oficial("950", "1000")));

        actualizador(principal).actualizar();

        Cotizacion guardada = almacen.get("USD:oficial");
        assertThat(guardada.getObtenidaEn()).isEqualTo(AHORA);
        assertThat(guardada.getActualizadaEn()).isEqualTo(MERCADO);
    }

    @Test
    @DisplayName("si la fuente no informa fecha de mercado, actualizada_en toma el momento de la respuesta")
    void sinFechaDeMercado() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(
                new CotizacionLeida("USD", "oficial", "Oficial", new BigDecimal("950"), new BigDecimal("1000"), null)));

        actualizador(principal).actualizar();

        assertThat(almacen.get("USD:oficial").getActualizadaEn()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("un tipo ya guardado se actualiza en el mismo registro; uno nuevo se agrega")
    void actualizaYAgrega() {
        Cotizacion previa = new Cotizacion("USD", "oficial", "Oficial", new BigDecimal("900"), new BigDecimal("910"),
                "DOLARAPI", MERCADO.minusSeconds(3600), AHORA.minusSeconds(3600));
        almacen.put("USD:oficial", previa);
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(
                oficial("950", "1000"),
                new CotizacionLeida("USD", "blue", "Blue", new BigDecimal("1180"), new BigDecimal("1200"), MERCADO)));

        actualizador(principal).actualizar();

        assertThat(almacen).containsOnlyKeys("USD:oficial", "USD:blue");
        assertThat(almacen.get("USD:oficial")).isSameAs(previa);
        assertThat(previa.getVenta()).isEqualByComparingTo("1000");
        assertThat(previa.getObtenidaEn()).isEqualTo(AHORA);
        assertThat(almacen.get("USD:blue").getVenta()).isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("un valor que no entra en DECIMAL(15,4) invalida la fuente entera y se prueba el respaldo (H09)")
    void valorQueNoEntraPasaAlRespaldo() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(
                oficial("950", "1000"),
                new CotizacionLeida("USD", "blue", "Blue", new BigDecimal("1180"), new BigDecimal("123456789012"),
                        MERCADO)));
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("948", "998")));

        Optional<String> usada = actualizador(principal, respaldo).actualizar();

        assertThat(usada).contains("BLUELYTICS");
        assertThat(almacen).containsOnlyKeys("USD:oficial");
        assertThat(almacen.get("USD:oficial").getVenta()).isEqualByComparingTo("998");
        assertThat(respaldo.consultas).isEqualTo(1);
    }

    @Test
    @DisplayName("si las dos fuentes traen valores que no entran, no guarda nada")
    void ningunaFuenteConValoresQueEntran() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(oficial("950", "999999999999")));
        FuenteFalsa respaldo = new FuenteFalsa("BLUELYTICS", () -> List.of(oficial("-1E+20", "998")));

        Optional<String> usada = actualizador(principal, respaldo).actualizar();

        assertThat(usada).isEmpty();
        assertThat(almacen).isEmpty();
    }

    @Test
    @DisplayName("el maximo que entra (11 enteros) se guarda; los decimales de mas los redondea la base")
    void elMaximoQueEntraSeGuarda() {
        FuenteFalsa principal = new FuenteFalsa("DOLARAPI", () -> List.of(oficial("99999999999.9999", "1000.123456")));

        assertThat(actualizador(principal).actualizar()).contains("DOLARAPI");
    }
}
