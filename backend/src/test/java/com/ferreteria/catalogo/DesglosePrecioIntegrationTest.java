package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GET /api/articulos/{id}/desglose por HTTP contra MySQL (T-218): los ejemplos de R-209 y R-210, el dolar vigente y no
 * el de la API, la base guardada contra el proveedor de hoy, la lectura del Vendedor y el aislamiento entre
 * ferreterias (regla 7). CUIT 30-78403001-1 y 30-78403002-2; casa propia de cotizacion (t218-).
 */
class DesglosePrecioIntegrationTest extends AjustesTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    private static final String SIN_LISTA =
            "Este artículo no tiene lista de proveedor. Los ajustes se usan solo con artículos que vienen de una lista.";

    private Long a;
    private Long b;
    private Long proveedorA;
    private Long proveedorB;
    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-78403001-1", "Desglose A");
        b = ferreteria("30-78403002-2", "Desglose B");
        borrarDatosDe(a, b);
        borrarCotizaciones("t218-");
        // La API va por 1100, pero el dolar VIGENTE de A es 1000 y el de B, 950.
        cotizacion("USD", "t218-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> {
            c.setCasaPrecios("t218-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t218-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t218-oficial");
            c.fijarVigente(new BigDecimal("950.0000"), "t218-oficial", LadoCotizacion.COMPRA, HACE_UN_RATO);
        });
        proveedorA = proveedor(a, "Proveedor A");
        proveedorB = proveedor(b, "Proveedor B");
        duenoA = login(usuario(a, "dueno-t218a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t218a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t218b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t218-");
        borrarDatosDe(a, b);
    }

    // ---- helpers ----

    private JsonNode desglose(String token, Long id) throws Exception {
        return getJson("/api/articulos/" + id + "/desglose", token);
    }

    private static List<String> tipos(JsonNode desglose) {
        List<String> tipos = new ArrayList<>();
        desglose.get("pasos").forEach(p -> tipos.add(p.get("tipo").asText()));
        return tipos;
    }

    private static JsonNode paso(JsonNode desglose, String tipo, int ocurrencia) {
        int vistas = 0;
        for (JsonNode paso : desglose.get("pasos")) {
            if (paso.get("tipo").asText().equals(tipo) && vistas++ == ocurrencia) {
                return paso;
            }
        }
        throw new AssertionError("No hay un paso " + tipo + " numero " + ocurrencia);
    }

    private static BigDecimal importe(JsonNode desglose, String tipo) {
        return paso(desglose, tipo, 0).get("importe").decimalValue();
    }

    // ---- R-209: ejemplos ----

    @Test
    @DisplayName("ARS con bonificacion y dos ajustes: pasos 1000 -> 900 -> 855 -> 880,65 -> 1144,845 -> 1385,2625 -> 1390")
    void arsConBonificacionYAjustes() throws Exception {
        Long id = importado(a, "D-ARS", proveedorA, "1000.00", false, valores("10", null, null),
                valores("-5", "3", null), "880.65", "1390.00");

        JsonNode d = desglose(duenoA, id);

        assertThat(d.get("tieneLista").asBoolean()).isTrue();
        assertThat(d.get("motivoSinLista").isNull()).isTrue();
        assertThat(tipos(d)).containsExactly("LISTA", "BONIFICACION", "AJUSTE", "AJUSTE", "COSTO", "MARGEN", "IVA",
                "REDONDEO", "PRECIO_FINAL");
        assertThat(importe(d, "LISTA")).isEqualByComparingTo("1000.00");
        assertThat(importe(d, "BONIFICACION")).isEqualByComparingTo("900.0000");
        assertThat(paso(d, "AJUSTE", 0).get("importe").decimalValue()).isEqualByComparingTo("855.0000");
        assertThat(paso(d, "AJUSTE", 1).get("importe").decimalValue()).isEqualByComparingTo("880.6500");
        assertThat(paso(d, "AJUSTE", 1).get("porcentaje").decimalValue()).isEqualByComparingTo("3");
        assertThat(importe(d, "COSTO")).isEqualByComparingTo("880.65");
        assertThat(importe(d, "MARGEN")).isEqualByComparingTo("1144.8450");
        assertThat(importe(d, "IVA")).isEqualByComparingTo("1385.2625");
        assertThat(importe(d, "PRECIO_FINAL")).isEqualByComparingTo("1390");
        assertThat(d.get("costoDeLista").decimalValue()).isEqualByComparingTo("880.65");
        assertThat(d.get("coincide").asBoolean()).isTrue();
        assertThat(d.get("moneda").asText()).isEqualTo("ARS");
        assertThat(d.get("cotizacion").isNull()).isTrue();
        assertThat(d.get("margen").decimalValue()).isEqualByComparingTo("30");
        assertThat(d.get("origenMargen").asText()).isEqualTo("PROPIO");
        assertThat(d.get("multiplo").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("lista con IVA incluido (1.210) y +10: sin IVA 1.000, ajuste 1.100, costo 1.100 y precio 1.740")
    void listaConIvaIncluido() throws Exception {
        Long id = importado(a, "D-IVA", proveedorA, "1210.00", true, NADA, valores("10", null, null), "1100.00",
                "1740.00");

        JsonNode d = desglose(duenoA, id);

        assertThat(tipos(d)).startsWith("LISTA", "SIN_IVA", "AJUSTE", "COSTO");
        assertThat(importe(d, "SIN_IVA")).isEqualByComparingTo("1000.0000");
        assertThat(importe(d, "AJUSTE")).isEqualByComparingTo("1100.0000");
        assertThat(d.get("listaIncluyeIva").asBoolean()).isTrue();
        assertThat(d.get("coincide").asBoolean()).isTrue();
        assertThat(importe(d, "PRECIO_FINAL")).isEqualByComparingTo("1740");
    }

    @Test
    @DisplayName("lista 333,33 con -10: el paso del ajuste muestra 299,9970 y el costo 300,00")
    void treintaYTresTreintaYTres() throws Exception {
        Long id = importado(a, "D-333", proveedorA, "333.33", false, NADA, valores("-10", null, null), "300.00",
                "480.00");

        JsonNode d = desglose(duenoA, id);

        assertThat(importe(d, "AJUSTE")).isEqualByComparingTo("299.9970");
        assertThat(importe(d, "COSTO")).isEqualByComparingTo("300.00");
        assertThat(d.get("coincide").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("USD con dolar vigente 1.000: cotizacion, costo en pesos 8.810 y precio 13.860")
    void enDolares() throws Exception {
        Long id = importado(a, "D-USD", proveedorA, "10.00", false, valores("10", null, null),
                valores("-5", "3", null), "8.81", "13860.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);

        JsonNode d = desglose(duenoA, id);

        assertThat(d.get("moneda").asText()).isEqualTo("USD");
        assertThat(d.get("cotizacion").decimalValue()).isEqualByComparingTo("1000");
        assertThat(d.get("cotizacionCongelada").asBoolean()).isFalse();
        assertThat(importe(d, "COTIZACION")).isEqualByComparingTo("8810.0000");
        assertThat(importe(d, "MARGEN")).isEqualByComparingTo("11453.0000");
        assertThat(importe(d, "PRECIO_FINAL")).isEqualByComparingTo("13860");
        assertThat(d.get("costoDeLista").decimalValue()).isEqualByComparingTo("8.81");
    }

    @Test
    @DisplayName("USD con el dolar de la API en 1.100 sin recalculo: el desglose sigue en 1.000 y en el precio guardado")
    void elDolarDeLaApiNoMueveElDesglose() throws Exception {
        Long id = importado(a, "D-API", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);

        JsonNode d = desglose(duenoA, id);

        assertThat(d.get("cotizacion").decimalValue()).isEqualByComparingTo("1000");
        assertThat(importe(d, "PRECIO_FINAL")).isEqualByComparingTo("15730");
        assertThat(d.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("cotizacion apagada y articulo USD: el dolar congelado, marcado como apagada")
    void cotizacionApagada() throws Exception {
        Long id = importado(a, "D-OFF", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);
        configuracion(a, c -> {
            c.setActiva(false);
            c.setCasaPrecios("t218-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t218-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });

        JsonNode d = desglose(duenoA, id);

        assertThat(d.get("cotizacion").decimalValue()).isEqualByComparingTo("1000");
        assertThat(d.get("cotizacionCongelada").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("alta manual y articulo sin proveedor: sin lista, con el texto unico y la cadena arranca en el costo")
    void sinLista() throws Exception {
        Long manual = manual(a, "D-MAN", "500.00", "790.00");
        Long sinProveedor = importado(a, "D-SINP", proveedorA, "1000.00", false, valores("10", null, null), NADA,
                "900.00", "1420.00");
        jdbc.update("update articulo set proveedor_id = null where id = ?", sinProveedor);

        for (Long id : List.of(manual, sinProveedor)) {
            JsonNode d = desglose(duenoA, id);

            assertThat(d.get("tieneLista").asBoolean()).isFalse();
            assertThat(d.get("motivoSinLista").asText()).isEqualTo(SIN_LISTA);
            assertThat(tipos(d).get(0)).isEqualTo("COSTO");
            assertThat(tipos(d)).doesNotContain("LISTA", "BONIFICACION", "AJUSTE");
            assertThat(d.get("coincide").isNull()).isTrue();
            assertThat(d.get("costoDeLista").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("margen del rubro (35) con origen RUBRO y margen propio 0 con origen PROPIO")
    void origenDelMargen() throws Exception {
        Long rubro = rubro(a, "Rubro desglose", "35.00");
        Long delRubro = manual(a, "D-RUB", "100.00", "170.00");
        jdbc.update("update articulo set rubro_id = ?, margen_porcentaje = null where id = ?", rubro, delRubro);
        Long conCero = manual(a, "D-CERO", "100.00", "130.00");
        jdbc.update("update articulo set margen_porcentaje = 0.00 where id = ?", conCero);

        JsonNode r = desglose(duenoA, delRubro);
        JsonNode c = desglose(duenoA, conCero);

        assertThat(r.get("margen").decimalValue()).isEqualByComparingTo("35");
        assertThat(r.get("origenMargen").asText()).isEqualTo("RUBRO");
        assertThat(c.get("margen").decimalValue()).isEqualByComparingTo("0");
        assertThat(c.get("origenMargen").asText()).isEqualTo("PROPIO");
    }

    // ---- R-210: costo de la lista contra el guardado ----

    @Test
    @DisplayName("coincide, +10 % masivo, costo a mano y bonificacion guardada 10 contra proveedor 12")
    void costoDeListaContraGuardado() throws Exception {
        Long coincide = importado(a, "D-C1", proveedorA, "1000.00", false, valores("10", null, null), NADA,
                "900.00", "1420.00");
        Long masivo = importado(a, "D-C2", proveedorA, "1000.00", false, valores("10", null, null), NADA,
                "990.00", "1560.00");
        Long aMano = importado(a, "D-C3", proveedorA, "1000.00", false, valores("10", null, null), NADA,
                "950.00", "1500.00");
        jdbc.update("update proveedor set bonificacion1 = 12 where id = ?", proveedorA);

        assertThat(desglose(duenoA, coincide).get("coincide").asBoolean()).isTrue();
        JsonNode m = desglose(duenoA, masivo);
        assertThat(m.get("coincide").asBoolean()).isFalse();
        assertThat(m.get("costoDeLista").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(m.get("costoGuardado").decimalValue()).isEqualByComparingTo("990.00");
        JsonNode x = desglose(duenoA, aMano);
        assertThat(x.get("coincide").asBoolean()).isFalse();
        assertThat(x.get("costoDeLista").decimalValue()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("la base guardada manda: el proveedor que pasa a 'no incluye IVA' y a bonificacion 20 no mueve el desglose")
    void baseGuardadaContraProveedorDeHoy() throws Exception {
        Long id = importado(a, "D-BASE", proveedorA, "1210.00", true, NADA, valores("10", null, null), "1100.00",
                "1740.00");
        jdbc.update("update proveedor set lista_incluye_iva = false, bonificacion1 = 20 where id = ?", proveedorA);

        JsonNode d = desglose(duenoA, id);

        assertThat(d.get("listaIncluyeIva").asBoolean()).isTrue();
        assertThat(d.get("bonificacion1").isNull()).isTrue();
        assertThat(importe(d, "SIN_IVA")).isEqualByComparingTo("1000.0000");
        assertThat(d.get("costoDeLista").decimalValue()).isEqualByComparingTo("1100.00");
        assertThat(d.get("coincide").asBoolean()).isTrue();
    }

    // ---- R-211: acceso y aislamiento ----

    @Test
    @DisplayName("el Vendedor ve el mismo desglose que el Dueno (solo lectura)")
    void elVendedorLoVe() throws Exception {
        Long id = importado(a, "D-VEN", proveedorA, "1000.00", false, valores("10", null, null),
                valores("-5", "3", null), "880.65", "1390.00");

        assertThat(desglose(vendedorA, id)).isEqualTo(desglose(duenoA, id));
    }

    @Test
    @DisplayName("aislamiento: un articulo de B pedido por el Dueno de A da 404 sin datos; inexistente 404; sin token 401")
    void aislamiento() throws Exception {
        Long deB = importado(b, "D-AJENO", proveedorB, "1000.00", false, valores("77", null, null), NADA, "230.00",
                "360.00");

        getResult("/api/articulos/" + deB + "/desglose", duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")))
                .andExpect(jsonPath("$.pasos").doesNotExist())
                .andExpect(jsonPath("$.bonificacion1").doesNotExist());
        getResult("/api/articulos/" + deB + "/desglose", vendedorA).andExpect(status().isNotFound());
        getResult("/api/articulos/999999999/desglose", duenoA).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/articulos/" + deB + "/desglose"))
                .andExpect(status().isUnauthorized());
        // El de B lo sigue viendo B.
        assertThat(desglose(duenoB, deB).get("bonificacion1").decimalValue()).isEqualByComparingTo("77");
    }

    @Test
    @DisplayName("dos ferreterias con dolar 1.000 y 950 ven cada una el suyo, y el rubro ajeno no aparece")
    void cadaFerreteriaVeSuDolar() throws Exception {
        Long deA = importado(a, "D-DOL-A", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        Long deB = importado(b, "D-DOL-B", proveedorB, "10.00", false, NADA, NADA, "10.00", "14950.00");
        jdbc.update("update articulo set moneda = 'USD' where id in (?, ?)", deA, deB);
        Long rubroB = rubro(b, "Rubro de B", "80.00");
        jdbc.update("update articulo set rubro_id = ?, margen_porcentaje = null where id = ?", rubroB, deB);

        JsonNode vistoPorA = desglose(duenoA, deA);
        JsonNode vistoPorB = desglose(duenoB, deB);

        assertThat(vistoPorA.get("cotizacion").decimalValue()).isEqualByComparingTo("1000");
        assertThat(vistoPorA.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");
        assertThat(vistoPorA.get("margen").decimalValue()).isEqualByComparingTo("30");
        // B pide SU articulo en dolares y ve SU dolar (950) y su rubro (80), no los de A.
        assertThat(vistoPorB.get("cotizacion").decimalValue()).isEqualByComparingTo("950");
        assertThat(vistoPorB.get("precioFinal").decimalValue()).isEqualByComparingTo("14950");
        assertThat(vistoPorB.get("margen").decimalValue()).isEqualByComparingTo("80");
        assertThat(vistoPorB.get("origenMargen").asText()).isEqualTo("RUBRO");
        assertThat(importe(vistoPorB, "COTIZACION")).isEqualByComparingTo("9500.0000");
    }
}
