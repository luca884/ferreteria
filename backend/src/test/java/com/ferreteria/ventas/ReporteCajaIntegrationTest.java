package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reporte de ingresos, gastos y retiros de la caja (G73), entre fechas y por tipo. Solo el Dueno. Entran los movimientos
 * de las cajas cerradas y de la abierta.
 *
 * <p>El escenario de cada test: en la primera caja el Vendedor carga un ingreso de 1000 («Cambio») y un gasto de 150
 * («Cadete») y el Dueno un retiro de 100 («Banco»); se cierra, se abre otra y el Dueno carga un ingreso de 50
 * («Propina») en la abierta.
 */
class ReporteCajaIntegrationTest extends VentasTestBase {

    private static final String URL = "/api/reportes/caja/movimientos";

    private Tienda t;
    private String dueno;
    private long cambio;
    private long cadete;
    private long banco;
    private long propina;
    private LocalDate hoy;

    @BeforeEach
    void escenario() throws Exception {
        hoy = HoraArgentina.hoy();
        t = tienda();
        dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        long primera = jdbc.queryForObject("select id from caja where ferreteria_id = ?", Long.class, t.id());
        cambio = cargar(primera, vendedor, "INGRESO", "1000.00", "Cambio");
        cadete = cargar(primera, vendedor, "GASTO", "150.00", "Cadete");
        banco = cargar(primera, dueno, "RETIRO", "100.00", "Banco");
        postJson("/api/cajas/" + primera + "/cierre", vendedor, "{\"efectivoContado\": 750.00}")
                .andExpect(status().isOk());
        long segunda = json(postJson("/api/cajas", dueno, "{\"efectivoInicial\": 750.00}")
                .andExpect(status().isCreated())).get("id").asLong();
        propina = cargar(segunda, dueno, "INGRESO", "50.00", "Propina");
    }

    private long cargar(long caja, String token, String tipo, String importe, String motivo) throws Exception {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("tipo", tipo);
        cuerpo.put("importe", new BigDecimal(importe));
        cuerpo.put("motivo", motivo);
        return json(postJson("/api/cajas/" + caja + "/movimientos", token, objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    /**
     * Cambia la fecha de un movimiento a esa hora de Buenos Aires, guardada como Hibernate ({@code jdbc.time_zone: UTC}):
     * la hora local de la JVM pasada a UTC. Cruda, se leeria corrida y no probaria el corte del dia.
     */
    private void fechar(long movimiento, LocalDateTime hora) {
        jdbc.update("update movimiento_caja set fecha = ? where id = ?", hora.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), movimiento);
    }

    private JsonNode reporte(String parametros) throws Exception {
        return getJson(URL + "?" + parametros, dueno);
    }

    private String hoyAHoy() {
        return "desde=" + hoy + "&hasta=" + hoy;
    }

    private static BigDecimal bd(JsonNode nodo) {
        return nodo.decimalValue();
    }

    @Test
    @DisplayName("trae los movimientos de la caja cerrada y de la abierta, del mas viejo al mas nuevo, con el nombre del usuario")
    void filasDeTodasLasCajas() throws Exception {
        JsonNode r = reporte(hoyAHoy());

        assertThat(r.get("desde").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("hasta").asText()).isEqualTo(hoy.toString());
        JsonNode filas = r.get("filas");
        assertThat(filas).hasSize(4);
        assertThat(filas.get(0).get("id").asLong()).isEqualTo(cambio);
        assertThat(filas.get(1).get("id").asLong()).isEqualTo(cadete);
        assertThat(filas.get(2).get("id").asLong()).isEqualTo(banco);
        assertThat(filas.get(3).get("id").asLong()).isEqualTo(propina);
        assertThat(filas.get(0).get("tipo").asText()).isEqualTo("INGRESO");
        assertThat(filas.get(0).get("motivo").asText()).isEqualTo("Cambio");
        assertThat(filas.get(0).get("usuario").asText()).isEqualTo("Vendedor Ventas");
        assertThat(bd(filas.get(0).get("importe"))).isEqualByComparingTo("1000.00");
        assertThat(filas.get(0).get("fecha").asText()).startsWith(hoy.toString());
        assertThat(filas.get(2).get("tipo").asText()).isEqualTo("RETIRO");
        assertThat(filas.get(2).get("usuario").asText()).isEqualTo("Dueno Ventas");
        assertThat(filas.get(3).get("motivo").asText()).isEqualTo("Propina");
    }

    @Test
    @DisplayName("los totales suman las filas por tipo")
    void totalesPorTipo() throws Exception {
        JsonNode totales = reporte(hoyAHoy()).get("totales");

        assertThat(bd(totales.get("INGRESO"))).isEqualByComparingTo("1050.00");
        assertThat(bd(totales.get("GASTO"))).isEqualByComparingTo("150.00");
        assertThat(bd(totales.get("RETIRO"))).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("con un tipo trae solo ese tipo y los otros totales valen 0")
    void filtroPorTipo() throws Exception {
        JsonNode r = reporte(hoyAHoy() + "&tipo=GASTO");

        assertThat(r.get("filas")).hasSize(1);
        assertThat(r.get("filas").get(0).get("id").asLong()).isEqualTo(cadete);
        assertThat(bd(r.get("totales").get("GASTO"))).isEqualByComparingTo("150.00");
        assertThat(bd(r.get("totales").get("INGRESO"))).isEqualByComparingTo("0");
        assertThat(bd(r.get("totales").get("RETIRO"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un dia sin movimientos da filas vacias y los tres totales en 0")
    void sinMovimientos() throws Exception {
        LocalDate otro = hoy.minusDays(30);
        JsonNode r = reporte("desde=" + otro + "&hasta=" + otro);

        assertThat(r.get("filas")).isEmpty();
        assertThat(bd(r.get("totales").get("INGRESO"))).isEqualByComparingTo("0");
        assertThat(bd(r.get("totales").get("GASTO"))).isEqualByComparingTo("0");
        assertThat(bd(r.get("totales").get("RETIRO"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("sin fechas el reporte es el de hoy")
    void sinFechasEsHoy() throws Exception {
        JsonNode r = getJson(URL, dueno);

        assertThat(r.get("desde").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("hasta").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("filas")).hasSize(4);
    }

    @Test
    @DisplayName("el rango incluye el dia «hasta» completo y deja afuera lo de antes de «desde»")
    void rangoDeFechas() throws Exception {
        fechar(cadete, hoy.minusDays(1).atTime(23, 59, 59));

        assertThat(reporte(hoyAHoy()).get("filas")).hasSize(3);
        JsonNode ayer = reporte("desde=" + hoy.minusDays(1) + "&hasta=" + hoy.minusDays(1));
        assertThat(ayer.get("filas")).hasSize(1);
        assertThat(ayer.get("filas").get(0).get("id").asLong()).isEqualTo(cadete);
        assertThat(bd(ayer.get("totales").get("GASTO"))).isEqualByComparingTo("150.00");
        assertThat(reporte("desde=" + hoy.minusDays(1) + "&hasta=" + hoy).get("filas")).hasSize(4);
    }

    @Test
    @DisplayName("otra ferreteria no ve estos movimientos ni los suma (regla 7)")
    void aislamientoPorFerreteria() throws Exception {
        Tienda otra = tienda();
        String duenoOtra = login(otra.emailDueno());
        long cajaOtra = jdbc.queryForObject("select id from caja where ferreteria_id = ?", Long.class, otra.id());
        cargar(cajaOtra, duenoOtra, "INGRESO", "10.00", "Cambio chico");

        JsonNode suyo = getJson(URL + "?" + hoyAHoy(), duenoOtra);
        assertThat(suyo.get("filas")).hasSize(1);
        assertThat(suyo.get("filas").get(0).get("motivo").asText()).isEqualTo("Cambio chico");
        assertThat(bd(suyo.get("totales").get("INGRESO"))).isEqualByComparingTo("10.00");
        assertThat(bd(suyo.get("totales").get("GASTO"))).isEqualByComparingTo("0");
        assertThat(reporte(hoyAHoy()).get("filas")).hasSize(4);
    }

    @Test
    @DisplayName("solo el Dueño: el Vendedor recibe 403")
    void soloElDueno() throws Exception {
        getResult(URL + "?" + hoyAHoy(), login(t.emailVendedor())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("fechas o tipo invalidos: «hasta» antes de «desde», mas de 366 dias o un tipo desconocido dan 400")
    void parametrosInvalidos() throws Exception {
        getResult(URL + "?desde=" + hoy + "&hasta=" + hoy.minusDays(1), dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.hasta").value("La fecha «Hasta» no puede ser anterior a «Desde»."));
        getResult(URL + "?desde=" + hoy.minusDays(366) + "&hasta=" + hoy, dueno)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.desde").value("El reporte admite hasta 366 días."));
        getResult(URL + "?desde=" + hoy.minusDays(365) + "&hasta=" + hoy, dueno).andExpect(status().isOk());
        getResult(URL + "?desde=ayer", dueno).andExpect(status().isBadRequest());
        getResult(URL + "?" + hoyAHoy() + "&tipo=PRESTAMO", dueno).andExpect(status().isBadRequest());
    }
}
