package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Masivo: todo o nada ante un choque de version (lote 5b, T-244, R-222 y R-227) por HTTP contra MySQL. La carrera de
 * #5438 reproducida con el PUT de ajustes REAL: un ajuste confirmado en el medio del aplicar ya no se pierde. CUIT
 * 30-78401051-1; casa propia de cotizacion (t244-).
 */
class ActualizacionMasivaVersionIntegrationTest extends VersionTestBase {

    private Long f;
    private Long proveedor;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78401051-1", "Version masivo");
        borrarDatosDe(f);
        conDolar(f, "t244-oficial", "1000", "1100");
        proveedor = proveedor(f, "Proveedor del masivo");
        dueno = login(usuario(f, "dueno-t244@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t244-");
        borrarDatosDe(f);
    }

    /** Lista 1.000 sin IVA, sin bonificaciones ni ajustes, costo 1.000 y precio $ 1.580, version 1. */
    private Long listaMil(String codigo) {
        Long id = importado(f, codigo, proveedor, "1000.00", false, NADA, NADA, "1000.00", "1580.00");
        jdbc.update("update articulo set version = 1 where id = ?", id);
        return id;
    }

    private long masivos() {
        return jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO'",
                Long.class, f);
    }

    private BigDecimal costoDe(Long id) {
        return jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id);
    }

    @Test
    @DisplayName("la carrera de #5438: un PUT de ajustes REAL confirma en el medio del aplicar; el masivo da 409 y A queda en 950, $ 1.500 y ajuste -5")
    void laCarreraDelMasivoContraElPutDeAjustes() throws Exception {
        Long id = listaMil("V244-A");
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);
        JsonNode previa = previaMasivo(dueno, pedido);
        ganchoEnSaveAll(() -> putAjustesVigente(dueno, id, "{\"ajuste1\":-5}").andExpect(status().isOk()));

        aplicarMasivo(dueno, pedido, previa).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        var fila = filaDe(id);
        assertThat((BigDecimal) fila.get("costo")).isEqualByComparingTo("950.00");
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) fila.get("ajuste1")).isEqualByComparingTo("-5.00");
        assertThat(versionDe(id)).isEqualTo(2);
        assertThat(masivos()).isZero();
    }

    @Test
    @DisplayName("un masivo de 3 articulos en el que el segundo choca da 409: ninguno cambia y no hay historial MASIVO (el choque se detecta dentro de un batch)")
    void elChoqueDelSegundoNoDejaNadaEscrito() throws Exception {
        Long primero = listaMil("V244-1");
        Long segundo = listaMil("V244-2");
        Long tercero = listaMil("V244-3");
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);
        JsonNode previa = previaMasivo(dueno, pedido);
        ganchoEnSaveAll(() -> jdbc.update("update articulo set descripcion = 'Del otro', version = version + 1 "
                + "where id = ?", segundo));

        aplicarMasivo(dueno, pedido, previa).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        for (Long id : new Long[] {primero, segundo, tercero}) {
            assertThat(costoDe(id)).isEqualByComparingTo("1000.00");
        }
        assertThat(versionDe(primero)).isEqualTo(1);
        assertThat(versionDe(tercero)).isEqualTo(1);
        assertThat(masivos()).isZero();
    }

    @Test
    @DisplayName("sin choque el masivo de 3 da 201 y los tres suben su version")
    void sinChoqueLosTresSuben() throws Exception {
        Long[] ids = {listaMil("V244-S1"), listaMil("V244-S2"), listaMil("V244-S3")};
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);

        aplicarMasivo(dueno, pedido, previaMasivo(dueno, pedido)).andExpect(status().isCreated());

        for (Long id : ids) {
            assertThat(costoDe(id)).isEqualByComparingTo("1100.00");
            assertThat(versionDe(id)).isEqualTo(2);
        }
        assertThat(masivos()).isEqualTo(3);
    }

    @Test
    @DisplayName("una descripcion cambiada entre la vista previa y el aplicar da 201 y se conserva")
    void unaDescripcionCambiadaEnElMedioSeConserva() throws Exception {
        Long id = listaMil("V244-D");
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);
        JsonNode previa = previaMasivo(dueno, pedido);
        putArticulo(dueno, id, cuerpoDe(dueno, id).put("descripcion", "Cambiada en el medio")).andExpect(status().isOk());

        aplicarMasivo(dueno, pedido, previa).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, id))
                .isEqualTo("Cambiada en el medio");
        assertThat(costoDe(id)).isEqualByComparingTo("1100.00");
    }

    @Test
    @DisplayName("un costo cambiado entre la vista previa y el aplicar da 409 por la huella, como hoy")
    void unCostoCambiadoEnElMedioDa409PorLaHuella() throws Exception {
        Long id = listaMil("V244-C");
        ObjectNode pedido = pedidoMasivo("COSTO_PORCENTAJE", "10", proveedor);
        JsonNode previa = previaMasivo(dueno, pedido);
        putArticulo(dueno, id, cuerpoDe(dueno, id).put("costo", new BigDecimal("1200.00"))).andExpect(status().isOk());

        aplicarMasivo(dueno, pedido, previa).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(costoDe(id)).isEqualByComparingTo("1200.00");
        assertThat(masivos()).isZero();
    }

    @Test
    @DisplayName("un articulo del alcance con error («El costo nuevo da 0 o menos»), editado en el medio, no frena nada: 201")
    void unArticuloConErrorEditadoNoFrenaNada() throws Exception {
        Long conError = importado(f, "V244-ERR", proveedor, "5.00", false, NADA, NADA, "5.00", "10.00");
        Long bueno = listaMil("V244-OK");
        ObjectNode pedido = pedidoMasivo("COSTO_MONTO", "-10", proveedor);
        JsonNode previa = previaMasivo(dueno, pedido);
        assertThat(previa.at("/conteos/conError").asInt()).isEqualTo(1);
        ganchoEnSaveAll(() -> jdbc.update("update articulo set descripcion = 'Editado', version = version + 1 "
                + "where id = ?", conError));

        aplicarMasivo(dueno, pedido, previa).andExpect(status().isCreated());

        assertThat(costoDe(bueno)).isEqualByComparingTo("990.00");
        assertThat(costoDe(conError)).isEqualByComparingTo("5.00");
        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, conError))
                .isEqualTo("Editado");
    }
}
