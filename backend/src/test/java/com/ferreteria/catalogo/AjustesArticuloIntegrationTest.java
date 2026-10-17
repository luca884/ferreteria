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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * PUT /api/articulos/{id}/ajustes por HTTP contra MySQL (T-220 y T-221): los ejemplos de R-212, la base guardada
 * contra el proveedor de hoy, la validacion, los tres 409, el 403 del Vendedor antes de leer el cuerpo y el
 * aislamiento entre ferreterias (regla 7). CUIT 30-78404001-1 y 30-78404002-2; casa propia de cotizacion (t220-).
 *
 * <p>Con IVA 21 %, margen 30 % y multiplo 10: costo 855 da 1.350; 880,65 da 1.390; 900 da 1.420; 1.100 da 1.740.
 */
class AjustesArticuloIntegrationTest extends AjustesTestBase {

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
        a = ferreteria("30-78404001-1", "Ajustes A");
        b = ferreteria("30-78404002-2", "Ajustes B");
        borrarDatosDe(a, b);
        borrarCotizaciones("t220-");
        cotizacion("USD", "t220-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> {
            c.setCasaPrecios("t220-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t220-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t220-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t220-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        proveedorA = proveedor(a, "Proveedor A");
        proveedorB = proveedor(b, "Proveedor B");
        duenoA = login(usuario(a, "dueno-t220a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t220a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t220b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t220-");
        borrarDatosDe(a, b);
    }

    // ---- helpers ----

    private ResultActions editar(String token, Long id, String json) throws Exception {
        return putJson("/api/articulos/" + id + "/ajustes", token, json);
    }

    private JsonNode editarOk(String token, Long id, String json) throws Exception {
        return objectMapper.readTree(editar(token, id, json).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String ajustes(String a1, String a2, String a3) {
        return "{\"ajuste1\":" + a1 + ",\"ajuste2\":" + a2 + ",\"ajuste3\":" + a3 + "}";
    }

    private static String ajustes(String a1, String a2, String a3, boolean confirma) {
        return "{\"ajuste1\":" + a1 + ",\"ajuste2\":" + a2 + ",\"ajuste3\":" + a3 + ",\"confirmaPisarCosto\":"
                + confirma + "}";
    }

    /** Lista 1.000, bonificacion 10, ajustes -5 y +3: costo 880,65 y precio 1.390. */
    private Long conDosAjustes(Long ferreteria, Long proveedor, String codigo) {
        return importado(ferreteria, codigo, proveedor, "1000.00", false, valores("10", null, null),
                valores("-5", "3", null), "880.65", "1390.00");
    }

    /** Lista 1.000, bonificacion 10, sin ajustes y el costo dado (900 coincide con la lista). */
    private Long conCosto(String codigo, String costo, String precio) {
        return importado(a, codigo, proveedorA, "1000.00", false, valores("10", null, null), NADA, costo, precio);
    }

    private Map<String, Object> historialUnico(Long id) {
        var filas = historialDe(id);
        assertThat(filas).hasSize(1);
        return filas.get(0);
    }

    private void assertHistorialManual(Long id, String costoAntes, String costoDespues, String precioAntes,
                                       String precioDespues) {
        Map<String, Object> h = historialUnico(id);
        assertThat(h.get("origen")).isEqualTo("MANUAL");
        assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t220a@test.local"));
        assertThat((BigDecimal) h.get("costo_anterior")).isEqualByComparingTo(costoAntes);
        assertThat((BigDecimal) h.get("costo_nuevo")).isEqualByComparingTo(costoDespues);
        assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo(precioAntes);
        assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo(precioDespues);
    }

    // ---- R-212: ejemplos ----

    @Test
    @DisplayName("quitar el +3: costo 880,65 -> 855,00, precio 1.390 -> 1.350 y una entrada MANUAL con el Dueno")
    void quitarUnAjuste() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-QUITAR");

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1350");
        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("ajuste2").isNull()).isTrue();
        assertHistorialManual(id, "880.65", "855.00", "1390", "1350");
    }

    @Test
    @DisplayName("agregar -5 a un articulo sin ajustes: costo 900,00 -> 855,00 y precio 1.420 -> 1.350")
    void agregarUnAjuste() throws Exception {
        Long id = conCosto("AJ-AGREGAR", "900.00", "1420.00");

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1350");
        assertHistorialManual(id, "900.00", "855.00", "1420", "1350");
    }

    @Test
    @DisplayName("cambiar el +3 por +5: costo 897,75 y precio 1.420")
    void cambiarUnAjuste() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-CAMBIAR");

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "5", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("897.75");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1420");
    }

    @Test
    @DisplayName("otra posicion (+3 y -5) se guarda, el costo sigue en 880,65 y no hay historial")
    void otraPosicion() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-POS");

        JsonNode r = editarOk(duenoA, id, ajustes("3", "-5", "null"));

        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.get("ajuste2").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("880.65");
        assertThat(historialDelArticulo(id)).isZero();
    }

    @Test
    @DisplayName("guardar -5,00 sobre -5 es 200 sin cambio y sin historial")
    void igualesNoEscribenNada() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-IGUAL");
        Map<String, Object> antes = filaDe(id);

        editarOk(duenoA, id, ajustes("-5.00", "3.00", "null"));

        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();
    }

    @Test
    @DisplayName("lista con IVA incluido (1.210) y +10: costo 1.100,00 y precio 1.740, tambien si el proveedor paso a 'no incluye'")
    void listaConIvaIncluido() throws Exception {
        Long id = importado(a, "AJ-IVA", proveedorA, "1210.00", true, NADA, NADA, "1000.00", "1580.00");
        jdbc.update("update proveedor set lista_incluye_iva = false where id = ?", proveedorA);

        JsonNode r = editarOk(duenoA, id, ajustes("10", "null", "null"));

        // No salta a 1.331,00: se usa la base guardada en el articulo.
        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("1100.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1740");
        assertThat(r.get("listaIncluyeIva").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("el articulo importado usa su base guardada y no la del proveedor de hoy (lote 3 pendiente)")
    void usaLaBaseGuardadaYNoLaDelProveedor() throws Exception {
        Long id = conCosto("AJ-BASE", "900.00", "1420.00");
        // El proveedor cambia despues de la importacion: la lista pasa a incluir IVA y la bonificacion a 50.
        jdbc.update("update proveedor set lista_incluye_iva = true, bonificacion1 = 50 where id = ?", proveedorA);

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(r.get("bonificacion1").decimalValue()).isEqualByComparingTo("10");
        assertThat(r.get("listaIncluyeIva").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("un PUT del articulo que lo pasa a otro proveedor con bonificacion 20 no mueve la base: +10 sigue dando 1.100,00")
    void cambiarDeProveedorNoMueveLaBase() throws Exception {
        Long id = importado(a, "AJ-OTROPROV", proveedorA, "1210.00", true, NADA, NADA, "1000.00", "1580.00");
        Long otro = proveedor(a, "Otro proveedor");
        jdbc.update("update proveedor set bonificacion1 = 20, lista_incluye_iva = false where id = ?", otro);
        String put = "{\"codigoInterno\":\"AJ-OTROPROV\",\"descripcion\":\"Articulo AJ-OTROPROV\",\"unidad\":\"UNIDAD\","
                + "\"costo\":1000.00,\"margenPorcentaje\":30.00,\"proveedorId\":" + otro + "}";
        putJson("/api/articulos/" + id, duenoA, put).andExpect(status().isOk());

        JsonNode r = editarOk(duenoA, id, ajustes("10", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("1100.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1740");
    }

    @Test
    @DisplayName("USD con -10: costo US$ 9,00 y precio $ 14.160, con historial MANUAL en dolares")
    void enDolares() throws Exception {
        Long id = importado(a, "AJ-USD", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);

        JsonNode r = editarOk(duenoA, id, ajustes("-10", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("14160");
        assertThat(historialUnico(id).get("moneda")).isEqualTo("USD");
    }

    @Test
    @DisplayName("USD con la cotizacion apagada: se permite con el dolar congelado")
    void dolaresConCotizacionApagada() throws Exception {
        Long id = importado(a, "AJ-USDOFF", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);
        configuracion(a, c -> {
            c.setActiva(false);
            c.setCasaPrecios("t220-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t220-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });

        JsonNode r = editarOk(duenoA, id, ajustes("-10", "null", "null"));

        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("14160");
    }

    @Test
    @DisplayName("un costo que da 0,00 (lista 0,01 con -99,99) se guarda")
    void costoCero() throws Exception {
        Long id = importado(a, "AJ-CERO", proveedorA, "0.01", false, NADA, NADA, "0.01", "10.00");

        JsonNode r = editarOk(duenoA, id, ajustes("-99.99", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("un campo costo en el cuerpo se ignora: el costo solo sale de la lista")
    void elCostoDelCuerpoSeIgnora() throws Exception {
        Long id = conCosto("AJ-COSTO", "900.00", "1420.00");

        JsonNode r = editarOk(duenoA, id, "{\"ajuste1\":-5,\"costo\":1.00}");

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
    }

    // ---- R-215: validacion ----

    @Test
    @DisplayName("fuera de rango, mas de 2 decimales y texto dan 400 y nada cambia")
    void validacion() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-VAL");
        Map<String, Object> antes = filaDe(id);

        editar(duenoA, id, ajustes("-100", "null", "null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ajuste1",
                        is("Cada descuento o recargo tiene que estar entre -99,99 y 1.000.")));
        editar(duenoA, id, ajustes("1000.01", "null", "null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ajuste1").exists());
        editar(duenoA, id, ajustes("null", "3.555", "null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ajuste2", is("Los descuentos y recargos admiten hasta 2 decimales.")));
        editar(duenoA, id, ajustes("\"abc\"", "null", "null")).andExpect(status().isBadRequest());

        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();
    }

    @Test
    @DisplayName("-99,99 sobre lista 1.000,00 sin bonificaciones: costo 0,10 y precio $ 10")
    void elMinimoPermitido() throws Exception {
        Long id = importado(a, "AJ-MIN", proveedorA, "1000.00", false, NADA, NADA, "1000.00", "1580.00");

        JsonNode r = editarOk(duenoA, id, ajustes("-99.99", "null", "null"));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("10");
    }

    // ---- 409 ----

    @Test
    @DisplayName("sin lista (alta manual, precio de lista sin proveedor): 409 con el texto unico, solo mensaje, y nada cambia")
    void sinLista() throws Exception {
        Long manual = manual(a, "AJ-MAN", "500.00", "790.00");
        Long sinProveedor = conCosto("AJ-SINP", "900.00", "1420.00");
        jdbc.update("update articulo set proveedor_id = null where id = ?", sinProveedor);

        for (Long id : new Long[] {manual, sinProveedor}) {
            Map<String, Object> antes = filaDe(id);
            editar(duenoA, id, ajustes("-5", "null", "null", true)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", is(SIN_LISTA)))
                    .andExpect(jsonPath("$.errors").doesNotExist());
            assertThat(filaDe(id)).isEqualTo(antes);
            assertThat(historialDelArticulo(id)).isZero();
        }
    }

    @Test
    @DisplayName("USD sin ninguna cotizacion: 409 y nada cambia")
    void dolaresSinCotizacion() throws Exception {
        Long id = importado(a, "AJ-USDNO", proveedorA, "10.00", false, NADA, NADA, "10.00", "15730.00");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);
        configuracion(a, c -> c.setCasaPrecios("t220-no-existe"));
        Map<String, Object> antes = filaDe(id);

        editar(duenoA, id, ajustes("-10", "null", "null")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(filaDe(id)).isEqualTo(antes);
    }

    @Test
    @DisplayName("costo a mano 950 contra lista 900: 409 con los dos costos; con la confirmacion pasa a 855,00 y deja historial 950 -> 855")
    void pisarElCosto() throws Exception {
        Long id = conCosto("AJ-PISAR", "950.00", "1500.00");
        Map<String, Object> antes = filaDe(id);

        editar(duenoA, id, ajustes("-5", "null", "null")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("El costo guardado ($ 950,00) no coincide con el que sale de la "
                        + "lista ($ 900,00). Si guardás, el costo pasa a $ 855,00.")))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "null", "null", true));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1350");
        assertHistorialManual(id, "950.00", "855.00", "1500", "1350");
    }

    @Test
    @DisplayName("tras un masivo (costo 990 con lista 900) quitar un ajuste avisa")
    void despuesDeUnMasivo() throws Exception {
        Long id = importado(a, "AJ-MASIVO", proveedorA, "1000.00", false, valores("10", null, null),
                valores("-5", null, null), "990.00", "1560.00");

        editar(duenoA, id, ajustes("null", "null", "null")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("El costo guardado ($ 990,00) no coincide con el que sale de la "
                        + "lista ($ 855,00). Si guardás, el costo pasa a $ 900,00.")));
    }

    @Test
    @DisplayName("articulo anterior a V7 cuyo proveedor cambio desde la importacion (base 12, costo 900 de la bonificacion 10): la primera edicion avisa")
    void anteriorAV7ConElProveedorCambiado() throws Exception {
        // V7 le copio la bonificacion que el proveedor tiene HOY (12); el costo guardado salio de la importacion con 10.
        Long id = importado(a, "AJ-V7", proveedorA, "1000.00", false, valores("12", null, null), NADA, "900.00",
                "1420.00");
        Map<String, Object> antes = filaDe(id);

        editar(duenoA, id, ajustes("-5", "null", "null")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("El costo guardado ($ 900,00) no coincide con el que sale de la "
                        + "lista ($ 880,00). Si guardás, el costo pasa a $ 836,00.")))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();

        JsonNode r = editarOk(duenoA, id, ajustes("-5", "null", "null", true));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("836.00");
        assertHistorialManual(id, "900.00", "836.00", "1420", "1320");
    }

    @Test
    @DisplayName("la confirmacion con ajustes que no cambian es inocua: 200 y sin historial")
    void confirmacionInocua() throws Exception {
        Long id = importado(a, "AJ-INOCUA", proveedorA, "1000.00", false, valores("10", null, null),
                valores("-5", null, null), "950.00", "1500.00");
        Map<String, Object> antes = filaDe(id);

        editarOk(duenoA, id, ajustes("-5", "null", "null", true));

        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();
    }

    // ---- 403 ----

    @Test
    @DisplayName("el Vendedor recibe 403 antes de leer el cuerpo (tambien con la confirmacion y con un ajuste invalido) y nada cambia")
    void elVendedorNoEdita() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-VEND");
        Map<String, Object> antes = filaDe(id);

        editar(vendedorA, id, ajustes("-5", "null", "null")).andExpect(status().isForbidden());
        editar(vendedorA, id, ajustes("-5", "null", "null", true)).andExpect(status().isForbidden());
        editar(vendedorA, id, ajustes("-100", "null", "null")).andExpect(status().isForbidden());
        editar(vendedorA, id, "no es json").andExpect(status().isForbidden());

        assertThat(filaDe(id)).isEqualTo(antes);
        assertThat(historialDelArticulo(id)).isZero();
    }

    // ---- T-221: aislamiento (regla 7) ----

    @Test
    @DisplayName("el Dueno de A no edita los ajustes de un articulo de B: 404 y B no cambia (ajustes, costo, precio ni historial)")
    void otraFerreteriaDa404() throws Exception {
        Long deB = conDosAjustes(b, proveedorB, "AJ-B");
        Map<String, Object> antes = filaDe(deB);

        editar(duenoA, deB, ajustes("-5", "null", "null", true)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        // Con un ajuste invalido sigue siendo 404 y no 400.
        editar(duenoA, deB, ajustes("-100", "null", "null")).andExpect(status().isNotFound());
        // El Vendedor, aun con uno invalido, es 403 y no 400.
        editar(vendedorA, deB, ajustes("-100", "null", "null")).andExpect(status().isForbidden());
        editar(duenoA, 999999999L, ajustes("-5", "null", "null")).andExpect(status().isNotFound());

        assertThat(filaDe(deB)).isEqualTo(antes);
        assertThat(historialDelArticulo(deB)).isZero();
    }

    @Test
    @DisplayName("sin token da 401")
    void sinTokenDa401() throws Exception {
        Long id = conDosAjustes(a, proveedorA, "AJ-401");

        mvc.perform(MockMvcRequestBuilders.put("/api/articulos/" + id + "/ajustes")
                        .contentType("application/json").content(ajustes("-5", "null", "null")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un ferreteriaId en el cuerpo se ignora y el articulo identico de B queda intacto")
    void elFerreteriaIdDelCuerpoSeIgnora() throws Exception {
        Long deA = conDosAjustes(a, proveedorA, "AJ-IDEM");
        Long deB = conDosAjustes(b, proveedorB, "AJ-IDEM");
        Map<String, Object> bAntes = filaDe(deB);

        JsonNode r = editarOk(duenoA, deA, "{\"ajuste1\":-5,\"ferreteriaId\":" + b + "}");

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(filaDe(deB)).isEqualTo(bAntes);
        assertThat(historialDelArticulo(deB)).isZero();
        // Y el Dueno de B sigue editando solo el suyo.
        editarOk(duenoB, deB, ajustes("-5", "null", "null"));
        assertThat(((BigDecimal) filaDe(deA).get("costo"))).isEqualByComparingTo("855.00");
    }
}
