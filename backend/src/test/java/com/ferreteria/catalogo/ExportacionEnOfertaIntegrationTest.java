package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Regresión de bugs de la guía, S19 (sondas del verificador): enOferta en GET /api/articulos/exportacion. */
class ExportacionEnOfertaIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos/exportacion";
    private Long a;
    private Long b;
    private String duenoA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79092011-1", "Sonda Oferta A");
        b = ferreteria("30-79092012-0", "Sonda Oferta B");
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-sonda-oferta-a@test.local", "DUENO"));
        usuario(a, "vendedor-sonda-oferta-a@test.local", "VENDEDOR");
        usuario(b, "dueno-sonda-oferta-b@test.local", "DUENO");
        // Precio 2.00 en todos salvo que se diga.
        cargar(a, "O-FIJO", "Clavo fijo", "2.00", true, "PRECIO_FIJO", "1.50", null);
        cargar(a, "O-FIJO-IGUAL", "Clavo fijo igual", "2.00", true, "PRECIO_FIJO", "2.00", null);
        cargar(a, "O-MONTO", "Tornillo monto", "2.00", true, "DESCUENTO_MONTO", "0.50", null);
        cargar(a, "O-PCT-NULO", "Arandela pct chico", "2.00", true, "DESCUENTO_PORCENTAJE", "0.01", null);
        cargar(a, "O-PCT", "Arandela pct", "2.00", true, "DESCUENTO_PORCENTAJE", "10.00", null);
        cargar(a, "O-HOY", "Clavo vence hoy", "2.00", true, "PRECIO_FIJO", "1.00", HoraArgentina.hoy());
        cargar(a, "O-AYER", "Clavo vencio ayer", "2.00", true, "PRECIO_FIJO", "1.00", HoraArgentina.hoy().minusDays(1));
        cargar(a, "O-BAJA", "Clavo dado de baja", "2.00", false, "PRECIO_FIJO", "1.00", null);
        cargar(a, "SIN", "Clavo sin oferta", "2.00", true, null, null, null);
        cargar(b, "B-OF", "Clavo ajeno", "2.00", true, "PRECIO_FIJO", "1.00", null);
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    private void cargar(Long fid, String codigo, String descripcion, String precio, boolean activo, String tipo,
                        String valor, java.time.LocalDate hasta) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final,"
                        + " unidad, alicuota_iva, stock_actual, stock_minimo, activo, oferta_tipo, oferta_valor, oferta_hasta)"
                        + " values (?, ?, ?, 1.00, 'ARS', ?, 'UNIDAD', 'IVA_21', 0, 0, ?, ?, ?, ?)",
                fid, codigo, descripcion, bd(precio), activo, tipo, valor == null ? null : bd(valor), hasta);
    }

    private List<String> exportados(String query, String token) throws Exception {
        MockHttpServletResponse r = getResult(URL + query, token).andExpect(status().isOk()).andReturn().getResponse();
        byte[] bytes = r.getContentAsByteArray();
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        return Arrays.stream(csv.split("\r\n")).skip(1).map(l -> l.split(";")[0].replace("=", "").replace("\"", "")).toList();
    }

    private List<String> listados(String query, String token) throws Exception {
        JsonNode pagina = getJson("/api/articulos?size=100&" + query, token);
        List<String> codigos = new ArrayList<>();
        pagina.get("items").forEach(i -> codigos.add(i.get("codigoInterno").asText()));
        return codigos;
    }

    @Test
    @DisplayName("enOferta exporta exactamente lo que lista la pantalla con «Solo en oferta», en su orden, con y sin busqueda e inactivos")
    void mismoFiltroYOrdenQueElListado() throws Exception {
        for (String q : new String[] {"enOferta=true", "enOferta=true&q=clavo", "enOferta=true&incluirInactivos=true",
                "enOferta=true&incluirInactivos=true&q=clavo", "enOferta=false", "incluirInactivos=true"}) {
            assertThat(exportados("?" + q, duenoA)).as(q).isEqualTo(listados(q, duenoA));
        }
    }

    @Test
    @DisplayName("bordes de la oferta: vence hoy entra, vencio ayer no, igual al precio no, un % que no baja el centavo no, la baja solo con inactivos")
    void bordes() throws Exception {
        assertThat(exportados("?enOferta=true", duenoA))
                .containsExactlyInAnyOrder("O-FIJO", "O-MONTO", "O-PCT", "O-HOY");
        assertThat(exportados("?enOferta=true&incluirInactivos=true", duenoA))
                .containsExactlyInAnyOrder("O-FIJO", "O-MONTO", "O-PCT", "O-HOY", "O-BAJA");
        assertThat(exportados("", duenoA)).hasSize(8).doesNotContain("O-BAJA", "B-OF");
    }

    @Test
    @DisplayName("otra ferreteria con enOferta solo ve lo suyo; el Vendedor recibe 403 tambien con enOferta")
    void aislamiento() throws Exception {
        assertThat(exportados("?enOferta=true", login("dueno-sonda-oferta-b@test.local"))).containsExactly("B-OF");
        getResult(URL + "?enOferta=true", login("vendedor-sonda-oferta-a@test.local")).andExpect(status().isForbidden());
    }
}
