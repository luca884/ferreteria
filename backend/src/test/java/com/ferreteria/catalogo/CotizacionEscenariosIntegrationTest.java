package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.service.ActualizadorDeCotizaciones;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Escenarios que cruzan el refresco de la cotizacion, la configuracion, «Recalcular ahora» y las ventas (T-033).
 * Ferreteria propia (CUIT 30-77060001-1). Usa las casas t33- salvo en el PUT de la configuracion, que valida contra el
 * catalogo y necesita las reales: ahi siembra «oficial» y lo borra al terminar.
 */
class CotizacionEscenariosIntegrationTest extends CotizacionTestBase {

    /** La hora real: GET /api/cotizacion mide la antiguedad con el reloj del sistema, asi que las fechas se arman con ella. */
    private final Instant ahora = Instant.now();
    private final Instant haceUnRato = ahora.minusSeconds(600);
    private final Instant haceDosHoras = ahora.minusSeconds(7200);

    @Autowired
    TransactionOperations transaccion;

    private Long a;
    private String duenoA;
    private String vendedorA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77060001-1", "Escenarios A");
        borrarCotizaciones("t33-");
        jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        duenoA = login(usuario(a, "dueno-t33a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t33a@test.local", "VENDEDOR"));
        cajaAbiertaHoy(a); // CU-10: sin caja abierta no se vende
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t33-");
        jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        borrarDatosDe(a);
    }

    private static FuenteDeCotizaciones fuente(String nombre, CotizacionLeida... leidas) {
        return new FuenteDeCotizaciones() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public List<CotizacionLeida> leer() {
                return List.of(leidas);
            }
        };
    }

    private static FuenteDeCotizaciones fuenteCaida(String nombre) {
        return new FuenteDeCotizaciones() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public List<CotizacionLeida> leer() {
                throw new FuenteNoDisponibleException("caida");
            }
        };
    }

    private ActualizadorDeCotizaciones actualizador(FuenteDeCotizaciones... fuentes) {
        return new ActualizadorDeCotizaciones(List.of(fuentes), cotizaciones, transaccion,
                Clock.fixed(ahora, ZoneOffset.UTC));
    }

    private CotizacionLeida leida(String casa, String compra, String venta) {
        return new CotizacionLeida("USD", casa, "Prueba " + casa, new BigDecimal(compra), new BigDecimal(venta), ahora);
    }

    // ---- R-008 ----

    @Test
    @DisplayName("R-008: el refresco sube el dolar a 1100: la cotizacion mostrada es 1100 y el articulo sigue en 15.730")
    void elRefrescoNoTocaPrecios() throws Exception {
        cotizacion("USD", "t33-oficial", "950", "1000", haceUnRato);
        configuracion(a, c -> {
            c.setCasaPrecios("t33-oficial");
            c.setVisibles(List.of("USD:t33-oficial"));
            c.fijarVigente(new BigDecimal("1000.0000"), "t33-oficial", LadoCotizacion.VENTA, haceUnRato);
        });
        Long usd = articuloUsd(a, "T33-USD", "10.00", "15730.00");

        actualizador(fuente("DOLARAPI", leida("t33-oficial", "1090", "1100"))).actualizar();

        JsonNode mostrada = getJson("/api/cotizacion", duenoA);
        assertThat(mostrada.get("visibles").get(0).get("venta").decimalValue()).isEqualByComparingTo("1100");
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
    }

    // ---- R-028 ----

    @Test
    @DisplayName("R-028: cambiar el dolar de precios a oficial compra no cambia el precio; «Recalcular ahora» lo pasa a 14.950")
    void elCambioDelDolarDePreciosEsperaAlRecalculo() throws Exception {
        cotizacion("USD", "oficial", "950", "1000", haceUnRato);
        configuracion(a, c -> c.fijarVigente(new BigDecimal("1000.0000"), "oficial", LadoCotizacion.VENTA, haceUnRato));
        Long usd = articuloUsd(a, "T33-USD", "10.00", "15730.00");

        putJson("/api/configuracion-cotizacion", duenoA, """
                {"activa":true,"dolarPrecios":"USD:oficial","ladoPrecios":"COMPRA","visibles":["USD:oficial"],"horaRecalculo":"08:00"}
                """).andExpect(status().isOk());

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();

        postJson("/api/cotizacion/recalculo", duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));

        assertThat(precioDe(usd)).isEqualByComparingTo("14950");
        assertThat(historialDe(usd)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("COTIZACION");
            assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo("15730");
            assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo("14950");
        });
    }

    // ---- R-029 ----

    @Test
    @DisplayName("R-029: con MEP guardado en 1050 y las dos fuentes sin MEP, el recalculo usa 1050 y no cambia al oficial")
    void unTipoQueNadieTraeConservaSuValor() throws Exception {
        cotizacion("USD", "t33-mep", "1040", "1050", haceDosHoras);
        cotizacion("USD", "t33-oficial", "950", "1000", haceDosHoras);
        configuracion(a, c -> {
            c.setCasaPrecios("t33-mep");
            c.setVisibles(List.of("USD:t33-oficial", "USD:t33-mep"));
        });
        Long usd = articuloUsd(a, "T33-USD", "10.00", "10000.00");

        // DolarApi caida y Bluelytics responde solo el oficial (998), sin MEP.
        actualizador(fuenteCaida("DOLARAPI"), fuente("BLUELYTICS", leida("t33-oficial", "948", "998"))).actualizar();

        assertThat(cotizaciones.findByMonedaAndCasa("USD", "t33-oficial").orElseThrow().getVenta())
                .isEqualByComparingTo("998");
        assertThat(cotizaciones.findByMonedaAndCasa("USD", "t33-mep").orElseThrow().getVenta())
                .isEqualByComparingTo("1050");

        postJson("/api/cotizacion/recalculo", duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cotizacion", is(1050.0)));

        // 10 x 1050 = 10500; x 1,30 = 13650; x 1,21 = 16516,50 -> 16520 (con el oficial 998 daria 15700)
        assertThat(precioDe(usd)).isEqualByComparingTo("16520");

        // El menu avisa de la antiguedad de ESE tipo, no del oficial.
        JsonNode visibles = getJson("/api/cotizacion", duenoA).get("visibles");
        assertThat(porCodigo(visibles, "USD:t33-mep").get("desactualizada").asBoolean()).isTrue();
        assertThat(porCodigo(visibles, "USD:t33-oficial").get("desactualizada").asBoolean()).isFalse();
    }

    private static JsonNode porCodigo(JsonNode visibles, String codigo) {
        for (JsonNode visible : visibles) {
            if (codigo.equals(visible.get("codigo").asText())) {
                return visible;
            }
        }
        throw new AssertionError("No esta " + codigo + " en " + visibles);
    }

    // ---- R-034 ----

    @Test
    @DisplayName("R-034: con un ticket armado antes, «Recalcular ahora» responde 200 y el cobro viejo da PRECIOS_CAMBIADOS con el precio nuevo")
    void unTicketAbiertoNoFrenaElRecalculo() throws Exception {
        cotizacion("USD", "t33-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> c.setCasaPrecios("t33-oficial"));
        Long usd = articuloUsd(a, "T33-USD", "10.00", "15730.00");
        jdbc.update("update articulo set stock_actual = 10 where id = ?", usd);
        // El ticket se arma en el navegador con el precio que se veia: 15.730.

        postJson("/api/cotizacion/recalculo", duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");

        Map<String, Object> venta = new LinkedHashMap<>();
        venta.put("clave", UUID.randomUUID().toString());
        venta.put("renglones", List.of(Map.of(
                "articuloId", usd, "cantidad", new BigDecimal("1"), "precioUnitario", new BigDecimal("15730.00"))));
        venta.put("totalMostrado", new BigDecimal("15730.00"));
        venta.put("medioPago", "EFECTIVO");

        String cuerpo = postJson("/api/ventas", vendedorA, objectMapper.writeValueAsString(venta))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode rechazo = objectMapper.readTree(cuerpo);
        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("articulos").get(0).get("precioMostrado").decimalValue()).isEqualByComparingTo("15730");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("17310");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("17310");
        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, a)).isZero();
    }
}
