package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.catalogo.model.AlicuotaIva;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/articulos/controles (GDS-18, G81): los «Controles de datos» de GDS. Un control por vez sobre los articulos
 * activos (costo 0, sin rubro, margen negativo, IVA 0), por descripcion, y nada de otra ferreteria (regla 7). CUIT
 * 30-79092001-6 y 30-79092002-4.
 */
class ControlesDeDatosIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos/controles";

    private Long a;
    private Long b;
    private Long bulones;
    private String duenoA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79092001-6", "Controles A");
        b = ferreteria("30-79092002-4", "Controles B");
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-controles-a@test.local", "DUENO"));
        usuario(a, "vendedor-controles-a@test.local", "VENDEDOR");
        usuario(b, "dueno-controles-b@test.local", "DUENO");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id, vigente) values (?, 1000.0000)", a);

        bulones = rubro(a, "Bulones", "30");
        Long sur = proveedor(a, "Bulonera Sur");
        cargar(a, "C-001", "Clavo", "ARS", "0.00", "100.00", "IVA_21", bulones, sur);
        cargar(a, "A-002", "Abrazadera", "ARS", "0.00", "50.00", "IVA_21", null, null);
        cargar(a, "T-003", "Tuerca", "ARS", "100.00", "120.00", "IVA_21", bulones, sur);
        cargar(a, "B-004", "Bulon", "ARS", "100.00", "200.00", "IVA_21", null, sur);
        cargar(a, "S-005", "Sierra USD", "USD", "1.00", "1000.00", "IVA_21", bulones, null);
        cargar(a, "P-006", "Pintura", "ARS", "100.00", "110.00", "IVA_10_5", bulones, sur);
        cargar(a, "L-007", "Lija", "ARS", "100.00", "100.00", "IVA_0", bulones, sur);
        // No salen en ningun control: margen 0 justo (en pesos y en dolares), dado de baja, y de otra ferreteria.
        cargar(a, "R-008", "Arandela", "ARS", "100.00", "121.00", "IVA_21", bulones, sur);
        cargar(a, "U-009", "Taladro USD", "USD", "1.00", "1210.00", "IVA_21", bulones, sur);
        Long baja = cargar(a, "F-010", "Dado de baja", "ARS", "0.00", "1.00", "IVA_0", null, null);
        jdbc.update("update articulo set activo = false where id = ?", baja);
        cargar(b, "G-011", "Ajeno", "ARS", "0.00", "1.00", "IVA_0", null, null);
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    private Long cargar(Long ferreteriaId, String codigo, String descripcion, String moneda, String costo,
                        String precio, String alicuota, Long rubroId, Long proveedorId) {
        Long id = articulo(ferreteriaId, codigo, moneda, costo, precio, true);
        jdbc.update("update articulo set descripcion = ?, alicuota_iva = ?, rubro_id = ?, proveedor_id = ? where id = ?",
                descripcion, alicuota, rubroId, proveedorId, id);
        return id;
    }

    private List<String> codigos(String control, String token) throws Exception {
        return getJson(URL + "?control=" + control, token).findValuesAsText("codigoInterno");
    }

    @Test
    @DisplayName("costo en cero: los activos propios con costo 0, por descripcion, con los datos para corregirlos")
    void costoCero() throws Exception {
        JsonNode filas = getJson(URL + "?control=COSTO_CERO", duenoA);

        assertThat(filas.findValuesAsText("codigoInterno")).containsExactly("A-002", "C-001");
        JsonNode clavo = filas.get(1);
        assertThat(clavo.get("articuloId").asLong()).isPositive();
        assertThat(clavo.get("descripcion").asText()).isEqualTo("Clavo");
        assertThat(clavo.get("rubro").asText()).isEqualTo("Bulones");
        assertThat(clavo.get("proveedor").asText()).isEqualTo("Bulonera Sur");
        assertThat(clavo.get("costo").decimalValue()).isEqualByComparingTo("0");
        assertThat(clavo.get("moneda").asText()).isEqualTo("ARS");
        assertThat(clavo.get("alicuotaIva").asText()).isEqualTo("IVA_21");
        assertThat(clavo.get("precioFinal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(clavo.get("precioManual").asBoolean()).isFalse();
        JsonNode abrazadera = filas.get(0);
        assertThat(abrazadera.get("rubro").isNull()).isTrue();
        assertThat(abrazadera.get("proveedor").isNull()).isTrue();
    }

    @Test
    @DisplayName("sin rubro e IVA en 0, como los controles de GDS")
    void sinRubroEIvaCero() throws Exception {
        assertThat(codigos("SIN_RUBRO", duenoA)).containsExactly("A-002", "B-004");
        assertThat(codigos("IVA_CERO", duenoA)).containsExactly("L-007");
    }

    @Test
    @DisplayName("margen negativo: costo en pesos (USD con el dolar vigente) mayor que el precio sin IVA; margen 0 no")
    void margenNegativo() throws Exception {
        assertThat(codigos("MARGEN_NEGATIVO", duenoA)).containsExactly("P-006", "S-005", "T-003");
    }

    @Test
    @DisplayName("margen negativo sin dolar guardado: los articulos en USD no salen")
    void margenNegativoSinDolar() throws Exception {
        borrarConfiguracion(a);

        assertThat(codigos("MARGEN_NEGATIVO", duenoA)).containsExactly("P-006", "T-003");
    }

    @Test
    @DisplayName("margen negativo con cada alicuota: el precio con margen 0 no sale, un centavo menos si")
    void margenNegativoConCadaAlicuota() throws Exception {
        jdbc.update("update articulo set activo = false where ferreteria_id = ?", a);
        List<String> esperados = new ArrayList<>();
        for (AlicuotaIva iva : AlicuotaIva.values()) {
            String justo = bd("100").add(iva.porcentaje()).toPlainString();
            String debajo = bd(justo).subtract(bd("0.01")).toPlainString();
            cargar(a, "J-" + iva.name(), "Justo " + iva.name(), "ARS", "100.00", justo, iva.name(), bulones, null);
            cargar(a, "D-" + iva.name(), "Debajo " + iva.name(), "ARS", "100.00", debajo, iva.name(), bulones, null);
            esperados.add("D-" + iva.name());
        }

        assertThat(codigos("MARGEN_NEGATIVO", duenoA)).containsExactlyInAnyOrderElementsOf(esperados);
    }

    @Test
    @DisplayName("otra ferreteria solo ve lo suyo; el Vendedor y el admin reciben 403; sin control o uno que no existe da 400")
    void aislamientoYPermisos() throws Exception {
        String duenoB = login("dueno-controles-b@test.local");
        assertThat(codigos("COSTO_CERO", duenoB)).containsExactly("G-011");
        assertThat(codigos("SIN_RUBRO", duenoB)).containsExactly("G-011");
        assertThat(codigos("IVA_CERO", duenoB)).containsExactly("G-011");
        assertThat(codigos("MARGEN_NEGATIVO", duenoB)).isEmpty();
        getResult(URL + "?control=COSTO_CERO", login("vendedor-controles-a@test.local"))
                .andExpect(status().isForbidden());
        getResult(URL + "?control=COSTO_CERO", login("admin@demo.local")).andExpect(status().isForbidden());
        getResult(URL, duenoA).andExpect(status().isBadRequest());
        getResult(URL + "?control=SIN_MARCA", duenoA).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin articulos en el control devuelve la lista vacia")
    void vacio() throws Exception {
        jdbc.update("update articulo set alicuota_iva = 'IVA_21' where ferreteria_id = ?", a);

        assertThat(getJson(URL + "?control=IVA_CERO", duenoA)).isEmpty();
    }
}
