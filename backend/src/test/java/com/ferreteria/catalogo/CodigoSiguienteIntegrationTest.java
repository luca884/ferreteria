package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.cotizacion.CotizacionTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GET /api/articulos/codigo-siguiente (mejoras-ferreteros-bloque-1, S3): el codigo interno que se sugiere en el alta,
 * a partir del ultimo articulo cargado a mano de la ferreteria del token. A (CUIT 30-77420001-1) y B (30-77420002-2).
 */
class CodigoSiguienteIntegrationTest extends CotizacionTestBase {

    private static final String URL = "/api/articulos/codigo-siguiente";

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77420001-1", "Codigo siguiente A");
        b = ferreteria("30-77420002-2", "Codigo siguiente B");
        duenoA = login(usuario(a, "dueno-t42a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t42a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t42b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    /** Un articulo con su codigo de proveedor: la importacion crea los nuevos con el interno igual al del proveedor. */
    private void articulo(Long ferreteriaId, String codigoInterno, String codigoProveedor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_proveedor, descripcion, costo, moneda, "
                        + "precio_final, activo) values (?, ?, ?, ?, ?, 'ARS', ?, true)",
                ferreteriaId, codigoInterno, codigoProveedor, "Articulo " + codigoInterno, new BigDecimal("10"),
                new BigDecimal("20"));
    }

    private String sugeridoPara(String token) throws Exception {
        var codigo = getJson(URL, token).get("codigo");
        return codigo.isNull() ? null : codigo.asText();
    }

    @Test
    @DisplayName("despues del ultimo cargado a mano (002) sugiere 003")
    void ultimoMasUno() throws Exception {
        articulo(a, "001", null);
        articulo(a, "002", "PROV-9");

        assertThat(sugeridoPara(duenoA)).isEqualTo("003");
    }

    @Test
    @DisplayName("un articulo importado (interno igual al del proveedor) no cuenta, aunque sea el ultimo")
    void ignoraLosImportados() throws Exception {
        articulo(a, "TOR-009", null);
        articulo(a, "DBS-100", "DBS-100");

        assertThat(sugeridoPara(duenoA)).isEqualTo("TOR-010");
    }

    @Test
    @DisplayName("si el siguiente ya esta ocupado, lo saltea")
    void salteaOcupados() throws Exception {
        articulo(a, "003", "003");
        articulo(a, "002", null);

        assertThat(sugeridoPara(duenoA)).isEqualTo("004");
    }

    @Test
    @DisplayName("sin articulos cargados a mano no sugiere nada")
    void sinArticulos() throws Exception {
        assertThat(sugeridoPara(duenoA)).isNull();
    }

    @Test
    @DisplayName("cada ferreteria sigue su propia numeracion")
    void aislamiento() throws Exception {
        articulo(a, "002", null);
        articulo(b, "500", null);

        assertThat(sugeridoPara(duenoA)).isEqualTo("003");
        assertThat(sugeridoPara(duenoB)).isEqualTo("501");
    }

    @Test
    @DisplayName("el Vendedor no da de alta articulos: 403")
    void vendedorNoPuede() throws Exception {
        getResult(URL, vendedorA).andExpect(status().isForbidden());
    }
}
