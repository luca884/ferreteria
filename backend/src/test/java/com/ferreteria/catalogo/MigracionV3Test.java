package com.ferreteria.catalogo;

import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migracion V3 (CU-04): mapeo, IVA y bonificaciones del proveedor, precio de lista del articulo e
 * importaciones aplicadas. El arranque del contexto ES la validacion de Hibernate (ddl-auto=validate):
 * si el mapeo JSON de Proveedor no coincide con la columna de Flyway, ningun test de esta clase corre.
 */
class MigracionV3Test extends IntegrationTestBase {

    @Autowired
    ProveedorRepository proveedorRepository;

    @Test
    void lasBonificacionesSonDecimalYAdmitenNull() {
        for (String columna : List.of("bonificacion1", "bonificacion2", "bonificacion3")) {
            assertThat(columna("proveedor", columna, "column_type")).isEqualTo("decimal(5,2)");
            assertThat(columna("proveedor", columna, "is_nullable")).isEqualTo("YES");
        }
    }

    @Test
    void elPrecioDeListaDelArticuloEsDecimalYAdmiteNull() {
        assertThat(columna("articulo", "precio_lista_proveedor", "column_type")).isEqualTo("decimal(15,2)");
        assertThat(columna("articulo", "precio_lista_proveedor", "is_nullable")).isEqualTo("YES");
    }

    @Test
    void listaIncluyeIvaEsObligatoriaYArrancaEnFalse() {
        assertThat(columna("proveedor", "lista_incluye_iva", "is_nullable")).isEqualTo("NO");

        Long f = ferreteriaId("30-71000001-1", "Migracion V3 A");
        Long proveedorId = proveedorId(f, "Sin lista");

        assertThat(jdbc.queryForObject(
                "select lista_incluye_iva from proveedor where id = ?", Boolean.class, proveedorId)).isFalse();
    }

    @Test
    void unaBonificacionFueraDeRangoLaRechazaElCheck() {
        Long f = ferreteriaId("30-71000002-2", "Migracion V3 B");
        Long proveedorId = proveedorId(f, "Check");

        // MySQL informa el CHECK violado como SQLException generica (error 3819), no como integridad.
        assertThatThrownBy(() -> jdbc.update(
                "update proveedor set bonificacion1 = 101 where id = ?", proveedorId))
                .hasMessageContaining("ck_proveedor_bonificacion1");
        assertThatThrownBy(() -> jdbc.update(
                "update proveedor set bonificacion3 = -1 where id = ?", proveedorId))
                .hasMessageContaining("ck_proveedor_bonificacion3");

        // Los extremos validos pasan.
        jdbc.update("update proveedor set bonificacion1 = 0, bonificacion2 = 100 where id = ?", proveedorId);
    }

    @Test
    void laTablaDeImportacionesTieneSuIndicePorFerreteria() {
        assertThat(columnasDelIndice("importacion_lista", "ix_importacion_proveedor"))
                .containsExactly("ferreteria_id", "proveedor_id", "fecha");
        assertThat(columna("importacion_lista", "ferreteria_id", "is_nullable")).isEqualTo("NO");
    }

    @Test
    void elMapeoYLasBonificacionesHacenViajeDeIdaYVuelta() {
        Long f = ferreteriaId("30-71000003-3", "Migracion V3 C");
        MapeoLista mapeo = new MapeoLista(1, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));

        TenantContext.setCurrentFerreteriaId(f);
        try {
            Proveedor proveedor = new Proveedor(f, "Ida y vuelta", null);
            proveedor.configurarLista(mapeo, true, new Bonificaciones(new BigDecimal("40"), new BigDecimal("10"), null));
            Long id = proveedorRepository.save(proveedor).getId();

            Proveedor leido = proveedorRepository.findScoped(id).orElseThrow();
            assertThat(leido.getMapeoLista()).isEqualTo(mapeo);
            assertThat(leido.isListaIncluyeIva()).isTrue();
            assertThat(leido.getBonificacion1()).isEqualByComparingTo("40");
            assertThat(leido.getBonificacion2()).isEqualByComparingTo("10");
            assertThat(leido.getBonificacion3()).isNull();
            assertThat(leido.bonificaciones().numerador()).isEqualByComparingTo("540000");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void unProveedorSinMapeoSeLeeConMapeoNull() {
        Long f = ferreteriaId("30-71000004-4", "Migracion V3 D");
        Long proveedorId = proveedorId(f, "Sin mapeo");

        TenantContext.setCurrentFerreteriaId(f);
        try {
            Proveedor leido = proveedorRepository.findScoped(proveedorId).orElseThrow();
            assertThat(leido.getMapeoLista()).isNull();
            assertThat(leido.isListaIncluyeIva()).isFalse();
            assertThat(leido.bonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList(
                "select column_name from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ? "
                        + "order by seq_in_index",
                String.class, tabla, indice);
    }

    private String columna(String tabla, String columna, String atributo) {
        return jdbc.queryForObject(
                "select " + atributo + " from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?",
                String.class, tabla, columna);
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long proveedorId(Long ferreteriaId, String nombre) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, nombre);
        return jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, nombre);
    }
}
