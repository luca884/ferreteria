package com.ferreteria.catalogo;

import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datos demo de db/dev-data/V1002 (CU-04): la configuracion de lista de los proveedores demo. Los
 * ids 1 a 3 son los de V1001; los demas tests de importacion crean sus propios proveedores, asi que
 * estas aserciones solo miran los demo.
 */
class DatosDemoImportacionIntegrationTest extends IntegrationTestBase {

    @Autowired
    ProveedorRepository proveedorRepository;

    /** Lee el proveedor por JPA, como la aplicacion: si las claves del JSON no coinciden con MapeoLista, no se lee. */
    private Proveedor leer(long ferreteriaId, long proveedorId) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            return proveedorRepository.findScoped(proveedorId).orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void elProveedorUnoTieneElMapeoGuardadoYSeLeePorJpa() {
        Proveedor bulonera = leer(1L, 1L);

        assertThat(bulonera.getMapeoLista()).isEqualTo(
                new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Descripcion", "Precio")));
        assertThat(bulonera.isListaIncluyeIva()).isFalse();
        assertThat(bulonera.bonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
    }

    @Test
    void elProveedorDosTieneIvaIncluidoYBonificacionesEnCascadaSinMapeo() {
        Proveedor puerto = leer(1L, 2L);

        assertThat(puerto.getMapeoLista()).isNull();
        assertThat(puerto.isListaIncluyeIva()).isTrue();
        assertThat(puerto.getBonificacion1()).isEqualByComparingTo("40.00");
        assertThat(puerto.getBonificacion2()).isEqualByComparingTo("10.00");
        assertThat(puerto.getBonificacion3()).isNull();
    }

    @Test
    void elProveedorTresQuedaSinConfigurarLaLista() {
        Proveedor atlantico = leer(2L, 3L);

        assertThat(atlantico.getMapeoLista()).isNull();
        assertThat(atlantico.isListaIncluyeIva()).isFalse();
        assertThat(atlantico.bonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
    }

    @Test
    void losArticulosDemoNoTienenPrecioDeListaYNoHayImportacionesDeLosProveedoresDemo() {
        assertThat(jdbc.queryForObject(
                "select count(*) from articulo where id between 1 and 8 and precio_lista_proveedor is not null",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from importacion_lista where proveedor_id in (1, 2, 3)", Long.class)).isZero();
    }
}
