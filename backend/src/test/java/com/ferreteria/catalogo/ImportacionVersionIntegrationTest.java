package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Importacion: todo o nada ante un choque de version (lote 5b, T-243, R-226) por HTTP contra MySQL. Un articulo que la
 * importacion va a escribir cambia mientras se aplica (el gancho de {@code saveAll} del historial): 409 con el texto de
 * la deriva y nada escrito. CUIT 30-78401041-1; casa propia de cotizacion (t243-).
 */
class ImportacionVersionIntegrationTest extends VersionTestBase {

    private Long f;
    private Long proveedor;
    private String dueno;
    private Long a;
    private Long b;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78401041-1", "Version importacion");
        borrarDatosDe(f);
        conDolar(f, "t243-oficial", "1000", "1100");
        proveedor = proveedor(f, "Proveedor de importacion");
        dueno = login(usuario(f, "dueno-t243@test.local", "DUENO"));
        a = deLista(f, "V243-A", proveedor);
        b = deLista(f, "V243-B", proveedor);
    }

    @AfterEach
    void limpiar() {
        soltarElGancho();
        borrarCotizaciones("t243-");
        borrarDatosDe(f);
    }

    private MockMultipartFile listaDeAyB() {
        return xlsx(lista(fila("V243-A", "Lista A", "1100"), fila("V243-B", "Lista B", "1100"),
                fila("V243-N1", "Nuevo 1", "100"), fila("V243-N2", "Nuevo 2", "200")));
    }

    private long importaciones() {
        return jdbc.queryForObject("select count(*) from importacion_lista where ferreteria_id = ?", Long.class, f);
    }

    private long historialDeImportacion() {
        return jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ? and origen = 'IMPORTACION'",
                Long.class, f);
    }

    private long nuevos() {
        return jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ? and codigo_proveedor like 'V243-N%'",
                Long.class, f);
    }

    /** Lo que otro Dueno deja en B en el medio: un ajuste de -5 y el costo que sale de la lista (855). */
    private void editaB() {
        jdbc.update("update articulo set ajuste1 = -5.00, costo = 855.00, precio_final = 1350.00, "
                + "version = version + 1 where id = ?", b);
    }

    @Test
    @DisplayName("si B cambia mientras se aplica: 409 de la lista, y no se escribe nada (articulos, altas, historial, registro ni proveedor)")
    void unChoqueNoEscribeNada() throws Exception {
        Map<String, Object> proveedorAntes = jdbc.queryForMap("select * from proveedor where id = ?", proveedor);
        ObjectNode datos = datosDeImportacion(proveedor, "10");
        ganchoEnSaveAll(this::editaB);

        aplicarImportacion(dueno, listaDeAyB(), datos, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(LISTA_CAMBIO)));

        assertThat((BigDecimal) filaDe(a).get("costo")).isEqualByComparingTo("900.00");
        assertThat(versionDe(a)).isZero();
        assertThat(importaciones()).isZero();
        assertThat(historialDeImportacion()).isZero();
        assertThat(nuevos()).isZero();
        assertThat(jdbc.queryForMap("select * from proveedor where id = ?", proveedor)).isEqualTo(proveedorAntes);
        // B conserva lo que dejo el otro.
        assertThat((BigDecimal) filaDe(b).get("ajuste1")).isEqualByComparingTo("-5.00");
        assertThat((BigDecimal) filaDe(b).get("costo")).isEqualByComparingTo("855.00");
        assertThat(versionDe(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("volver a pedir la vista previa y aplicar da 201: A en 990,00 y B en 940,50 con el -5 conservado")
    void despuesDelChoqueSeReintentaYAndaBien() throws Exception {
        ObjectNode datos = datosDeImportacion(proveedor, "10");
        ganchoEnSaveAll(this::editaB);
        aplicarImportacion(dueno, listaDeAyB(), datos, true).andExpect(status().isConflict());
        soltarElGancho();

        aplicarImportacion(dueno, listaDeAyB(), datos, true).andExpect(status().isCreated());

        assertThat((BigDecimal) filaDe(a).get("costo")).isEqualByComparingTo("990.00");
        assertThat((BigDecimal) filaDe(b).get("costo")).isEqualByComparingTo("940.50");
        assertThat((BigDecimal) filaDe(b).get("ajuste1")).isEqualByComparingTo("-5.00");
        assertThat(nuevos()).isEqualTo(2);
        assertThat(importaciones()).isEqualTo(1);
    }

    @Test
    @DisplayName("un articulo del proveedor que la lista no trae, editado en el medio, no frena nada: 201")
    void unArticuloQueLaListaNoTraeNoFrenaNada() throws Exception {
        Long c = deLista(f, "V243-C", proveedor);
        ganchoEnSaveAll(() -> jdbc.update("update articulo set descripcion = 'Editado', version = version + 1 "
                + "where id = ?", c));

        aplicarImportacion(dueno, xlsx(lista(fila("V243-A", "Lista A", "1100"))),
                datosDeImportacion(proveedor, "10"), false).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, c))
                .isEqualTo("Editado");
        assertThat((BigDecimal) filaDe(a).get("costo")).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("una descripcion cambiada entre la vista previa y el aplicar da 201 y se conserva")
    void unaDescripcionCambiadaEnElMedioSeConserva() throws Exception {
        MockMultipartFile archivo = xlsx(lista(fila("V243-A", "Lista A", "1100")));
        ObjectNode datos = datosDeImportacion(proveedor, "10");
        JsonNode previa = previaDeImportacion(dueno, archivo, datos);
        putArticulo(dueno, a, cuerpoDe(dueno, a).put("descripcion", "Cambiada en el medio")).andExpect(status().isOk());

        aplicarImportacion(dueno, archivo, datos, previa, false).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, a))
                .isEqualTo("Cambiada en el medio");
        assertThat((BigDecimal) filaDe(a).get("costo")).isEqualByComparingTo("990.00");
        assertThat(versionDe(a)).isEqualTo(2);
    }
}
