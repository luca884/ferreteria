package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1007 (solo dev y test): el AJUSTE «Stock inicial demo» de V1003 se insertaba sin fecha y tomaba el
 * DEFAULT CURRENT_TIMESTAMP de MySQL, que corre en UTC; con la JVM fuera de Buenos Aires quedaba unas 3 horas en el futuro
 * y la pantalla de movimientos lo mostraba arriba de las ventas del dia. Tiene que ser anterior a cualquier movimiento real.
 */
class StockInicialDemoIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("el Stock inicial demo de los articulos demo es anterior a ahora (hora de Buenos Aires)")
    void elStockInicialDemoEsAnteriorAAhora() throws Exception {
        LocalDateTime ahora = HoraArgentina.ahora();

        JsonNode items = getJson("/api/articulos/1/movimientos-stock", login("dueno@tornillo.demo")).get("items");

        JsonNode inicial = items.get(items.size() - 1);
        assertThat(inicial.get("motivo").asText()).isEqualTo("Stock inicial demo");
        assertThat(LocalDateTime.parse(inicial.get("fecha").asText())).isBefore(ahora.minusDays(1));
    }

    @Test
    @DisplayName("los siete AJUSTE demo quedan con la misma fecha fija del pasado, leida en la base")
    void todosLosAjustesDemoTienenLaFechaFija() {
        List<String> fechas = jdbc.queryForList("select distinct date_format(fecha, '%Y-%m-%d %H:%i:%s')"
                + " from movimiento_stock where motivo = 'Stock inicial demo' and tipo = 'AJUSTE'"
                + " and articulo_id between 1 and 8", String.class);

        assertThat(fechas).containsExactly("2026-01-01 12:00:00");
    }
}
