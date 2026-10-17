package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La version del articulo (lote 5b, T-240) por HTTP contra MySQL: el alta responde 0, el GET y el listado la traen y
 * cada escritor la sube en 1 (PUT, PUT de ajustes, importacion, masivo, recalculo, cascada del rubro, baja y
 * reactivacion); una operacion que no cambia nada no la sube. CUIT 30-78401011-1; casa propia de cotizacion (t240-).
 * Los 409 del PUT y del PUT de ajustes por version vieja estan en T-241 y T-242; el stock y las ventas, en T-246.
 */
class VersionArticuloIntegrationTest extends VersionTestBase {

    private Long f;
    private Long proveedor;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78401011-1", "Version de articulos");
        borrarDatosDe(f);
        conDolar(f, "t240-oficial", "1000", "1100");
        proveedor = proveedor(f, "Proveedor de version");
        dueno = login(usuario(f, "dueno-t240@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t240-");
        borrarDatosDe(f);
    }

    private static final String ALTA = "{\"codigoInterno\":\"%s\",\"descripcion\":\"Articulo nuevo\",\"unidad\":\"UNIDAD\","
            + "\"costo\":100.00,\"margenPorcentaje\":30}";

    @Test
    @DisplayName("el alta responde version 0 y el GET y el listado traen la misma")
    void elAltaEmpiezaEnCero() throws Exception {
        JsonNode alta = json(postJson("/api/articulos", dueno, ALTA.formatted("V240-ALTA"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version", is(0))));
        Long id = alta.get("id").asLong();

        assertThat(articuloJson(dueno, id).get("version").asLong()).isZero();
        JsonNode pagina = getJson("/api/articulos?q=V240-ALTA", dueno);
        assertThat(pagina.at("/items/0/version").asLong()).isZero();
        assertThat(versionDe(id)).isZero();
    }

    @Test
    @DisplayName("un articulo con version 3 la trae en el GET y en el listado")
    void elGetYElListadoTraenLaVersion() throws Exception {
        Long id = manual(f, "V240-GET", "100.00", "160.00");
        jdbc.update("update articulo set version = 3 where id = ?", id);

        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(3);
        assertThat(getJson("/api/articulos?q=V240-GET", dueno).at("/items/0/version").asLong()).isEqualTo(3);
    }

    // ---- cada escritor sube la version en 1 ----

    @Test
    @DisplayName("el PUT (sin version en el cuerpo) la sube en 1 y la respuesta trae la nueva")
    void elPutLaSube() throws Exception {
        Long id = manual(f, "V240-PUT", "100.00", "160.00");
        ObjectNode cuerpo = cuerpoDe(dueno, id).put("descripcion", "Cambiada");

        putArticulo(dueno, id, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));

        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("el PUT de ajustes la sube en 1; el mismo pedido otra vez (sin cambio) la deja")
    void elPutDeAjustesLaSubeYSinCambioNo() throws Exception {
        Long id = deLista(f, "V240-AJ", proveedor);

        putAjustes(dueno, id, "{\"ajuste1\":-5}").andExpect(status().isOk());
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);

        putAjustes(dueno, id, "{\"ajuste1\":-5}").andExpect(status().isOk());
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("la importacion la sube en 1 cuando sube el costo")
    void laImportacionLaSube() throws Exception {
        Long id = deLista(f, "V240-IMP", proveedor);

        aplicarImportacion(dueno, xlsx(lista(fila("V240-IMP", "Lista", "1100"))),
                datosDeImportacion(proveedor, "10"), false).andExpect(status().isCreated());

        assertThat(articuloJson(dueno, id).get("costo").decimalValue()).isEqualByComparingTo("990.00");
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("la importacion la sube en 1 tambien en una fila «Sin cambio» elegida (anota la importacion)")
    void laImportacionSinCambioLaSube() throws Exception {
        Long id = deLista(f, "V240-SC", proveedor);

        aplicarImportacion(dueno, xlsx(lista(fila("V240-SC", "Lista", "1000"))),
                datosDeImportacion(proveedor, "10"), false)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.conteos.sinCambio", is(1)));

        assertThat(historialDelArticulo(id)).isZero();
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("el masivo la sube en 1")
    void elMasivoLaSube() throws Exception {
        Long id = deLista(f, "V240-MAS", proveedor);
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);

        aplicarMasivo(dueno, pedido, previaMasivo(dueno, pedido)).andExpect(status().isCreated());

        assertThat(articuloJson(dueno, id).get("costo").decimalValue()).isEqualByComparingTo("990.00");
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("«Recalcular ahora» la sube en 1 si cambia el precio (15.730 a 17.310) y no si no cambia")
    void elRecalculoLaSubeSoloSiCambiaElPrecio() throws Exception {
        Long id = articuloUsd(f, "V240-USD", "10.00", "15730.00");

        postJson("/api/cotizacion/recalculo", dueno, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));
        assertThat(precioDe(id)).isEqualByComparingTo("17310");
        assertThat(versionDe(id)).isEqualTo(1);

        postJson("/api/cotizacion/recalculo", dueno, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(0)));
        assertThat(versionDe(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("la cascada del margen del rubro la sube en 1 en el articulo que hereda")
    void laCascadaDelRubroLaSube() throws Exception {
        Long rubro = rubro(f, "Rubro de version", "30.00");
        Long hereda = manual(f, "V240-HER", "100.00", "160.00");
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubro, hereda);

        putJson("/api/rubros/" + rubro, dueno, "{\"nombre\":\"Rubro de version\",\"margenPorcentaje\":50}")
                .andExpect(status().isOk());

        assertThat(precioDe(hereda)).isEqualByComparingTo("190");
        assertThat(versionDe(hereda)).isEqualTo(1);
    }

    @Test
    @DisplayName("la baja y la reactivacion la suben en 1 cada una")
    void laBajaYLaReactivacionLaSuben() throws Exception {
        Long id = manual(f, "V240-BAJA", "100.00", "160.00");

        delete("/api/articulos/" + id, dueno).andExpect(status().isNoContent());
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(1);

        postJson("/api/articulos/" + id + "/reactivacion", dueno, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(2)));
        assertThat(articuloJson(dueno, id).get("version").asLong()).isEqualTo(2);
    }
}
