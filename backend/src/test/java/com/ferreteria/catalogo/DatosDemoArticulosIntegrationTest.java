package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.PrecioCalculator;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datos demo de db/dev-data/V1001 (articulos y proveedores), ya cargados en la base de los tests.
 * Los ids 1 a 8 son los sembrados; los demas tests crean filas con ids mayores, asi que las
 * aserciones filtran por esos ids y no cuentan toda la tabla.
 */
class DatosDemoArticulosIntegrationTest extends IntegrationTestBase {

    private static final String IDS_DEMO = "(1, 2, 3, 4, 5, 6, 7, 8)";

    /** Los 8 articulos sembrados; falla si faltan, para que los bucles de abajo no pasen en vacio. */
    private List<Map<String, Object>> articulosDemo() {
        List<Map<String, Object>> articulos =
                jdbc.queryForList("select * from articulo where id in " + IDS_DEMO + " order by id");
        assertThat(articulos).hasSize(8);
        return articulos;
    }

    @Test
    void hayOchoArticulosDemoYTresProveedoresDemo() {
        assertThat(articulosDemo()).hasSize(8);
        assertThat(jdbc.queryForObject("select count(*) from proveedor where id in (1, 2, 3)", Long.class))
                .isEqualTo(3L);
        // Dos ferreterias: 5 articulos de El Tornillo (1) y 3 de La Tuerca (2).
        assertThat(jdbc.queryForObject(
                "select count(*) from articulo where id in " + IDS_DEMO + " and ferreteria_id = 1", Long.class))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "select count(*) from articulo where id in " + IDS_DEMO + " and ferreteria_id = 2", Long.class))
                .isEqualTo(3L);
    }

    /** Los precios sembrados salen de la misma formula que usa la aplicacion, no de otra copia. */
    @Test
    void losPreciosSembradosCoincidenConPrecioCalculator() {
        for (Map<String, Object> articulo : articulosDemo()) {
            long ferreteriaId = ((Number) articulo.get("ferreteria_id")).longValue();
            BigDecimal margenDefecto = jdbc.queryForObject(
                    "select margen_defecto_porcentaje from ferreteria where id = ?", BigDecimal.class, ferreteriaId);
            BigDecimal multiplo = jdbc.queryForObject(
                    "select redondeo_multiplo from ferreteria where id = ?", BigDecimal.class, ferreteriaId);
            BigDecimal margenRubro = articulo.get("rubro_id") == null ? null : jdbc.queryForObject(
                    "select margen_porcentaje from rubro where id = ?", BigDecimal.class, articulo.get("rubro_id"));

            BigDecimal margen = PrecioCalculator.margenEfectivo(
                    (BigDecimal) articulo.get("margen_porcentaje"), margenRubro, margenDefecto);
            BigDecimal esperado = PrecioCalculator.precioFinal(
                    (BigDecimal) articulo.get("costo"), margen,
                    AlicuotaIva.valueOf((String) articulo.get("alicuota_iva")), multiplo);

            assertThat((BigDecimal) articulo.get("precio_final"))
                    .as("precio_final de %s", articulo.get("codigo_interno"))
                    .isEqualByComparingTo(esperado);
        }
    }

    /** Valores calculados a mano (costo x margen x IVA, sube al multiplo de 10) para no depender solo del calculador. */
    @Test
    void losPreciosSembradosSonLosCalculadosAMano() {
        Map<Long, String> esperados = Map.of(
                1L, "170.00",   // 100 x 1,35 x 1,21 = 163.35
                2L, "80.00",    // 40 x 1,50 x 1,21 = 72.60
                3L, "13560.00", // 8000 x 1,40 x 1,21 = 13552
                4L, "790.00",   // 500 x 1,30 x 1,21 = 786.50
                5L, "5090.00",  // 3000 x 1,40 x 1,21 = 5082
                6L, "47190.00", // 30000 x 1,30 x 1,21 = 47190 (ya es multiplo)
                7L, "3940.00",  // 2500 x 1,30 x 1,21 = 3932.50
                8L, "80.00");   // 50 x 1,30 x 1,21 = 78.65
        esperados.forEach((id, precio) -> assertThat(
                jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, id))
                .as("articulo %s", id).isEqualByComparingTo(precio));
    }

    @Test
    void soloElArticuloCincoEstaInactivoYElCuatroEsPorMetroSinRubro() {
        List<Map<String, Object>> inactivos = jdbc.queryForList(
                "select id from articulo where id in " + IDS_DEMO + " and activo = false");
        assertThat(inactivos).hasSize(1);
        assertThat(((Number) inactivos.get(0).get("id")).longValue()).isEqualTo(5L);

        Map<String, Object> cable = jdbc.queryForMap("select * from articulo where id = 4");
        assertThat(cable.get("unidad")).isEqualTo("METRO");
        assertThat(cable.get("rubro_id")).isNull();
        // El articulo 2 tiene margen propio; el 1 hereda del rubro.
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = 2", BigDecimal.class))
                .isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = 1", BigDecimal.class))
                .isNull();
    }

    @Test
    void elCodigoTor001SeRepiteEntreFerreteriasYCadaUnaVeSoloElSuyo() throws Exception {
        assertThat(jdbc.queryForObject(
                "select count(*) from articulo where codigo_interno = 'TOR-001' and id in " + IDS_DEMO, Long.class))
                .isEqualTo(2L);

        JsonNode deA = getJson("/api/articulos?q=TOR-001", login("dueno@tornillo.demo"));
        JsonNode deB = getJson("/api/articulos?q=TOR-001", login("dueno@tuerca.demo"));

        assertThat(deA.get("items")).hasSize(1);
        assertThat(deA.get("items").get(0).get("id").asLong()).isEqualTo(1L);
        assertThat(deB.get("items")).hasSize(1);
        assertThat(deB.get("items").get(0).get("id").asLong()).isEqualTo(8L);
    }

    @Test
    void cadaArticuloDemoTieneUnSoloHistorialManualDelDuenoDeSuFerreteria() {
        for (Map<String, Object> articulo : articulosDemo()) {
            long id = ((Number) articulo.get("id")).longValue();
            List<Map<String, Object>> historial = jdbc.queryForList(
                    "select h.*, u.rol as rol_usuario, u.ferreteria_id as ferreteria_usuario"
                            + " from historial_precio h join usuario u on u.id = h.usuario_id where h.articulo_id = ?", id);

            // Otros tests pueden editar un articulo demo y sumar historial: las entradas MANUAL
            // iniciales son las de costo anterior nulo, y debe haber exactamente una.
            List<Map<String, Object>> iniciales = historial.stream()
                    .filter(h -> h.get("costo_anterior") == null).toList();
            assertThat(iniciales).as("historial inicial del articulo %s", id).hasSize(1);

            Map<String, Object> fila = iniciales.get(0);
            assertThat(fila.get("origen")).isEqualTo("MANUAL");
            assertThat(fila.get("precio_anterior")).isNull();
            assertThat((BigDecimal) fila.get("costo_nuevo")).isEqualByComparingTo((BigDecimal) articulo.get("costo"));
            assertThat((BigDecimal) fila.get("precio_nuevo"))
                    .isEqualByComparingTo((BigDecimal) articulo.get("precio_final"));
            assertThat(fila.get("rol_usuario")).isEqualTo("DUENO");
            assertThat(((Number) fila.get("ferreteria_id")).longValue())
                    .isEqualTo(((Number) articulo.get("ferreteria_id")).longValue());
            assertThat(((Number) fila.get("ferreteria_usuario")).longValue())
                    .isEqualTo(((Number) articulo.get("ferreteria_id")).longValue());
        }
    }
}
