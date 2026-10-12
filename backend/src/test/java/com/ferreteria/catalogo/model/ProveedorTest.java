package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class ProveedorTest {

    @Test
    @DisplayName("un proveedor nuevo arranca activo")
    void nuevoArrancaActivo() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);

        assertThat(proveedor.isActivo()).isTrue();
        assertThat(proveedor.getFerreteriaId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("actualizar cambia nombre, CUIT y activo")
    void actualizarCambiaLosCampos() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);

        proveedor.actualizar("Sinteplast SA", "30-12345678-9", false);

        assertThat(proveedor.getNombre()).isEqualTo("Sinteplast SA");
        assertThat(proveedor.getCuit()).isEqualTo("30-12345678-9");
        assertThat(proveedor.isActivo()).isFalse();
    }

    @Test
    @DisplayName("actualizar puede quitar el CUIT y reactivar")
    void actualizarQuitaCuitYReactiva() {
        Proveedor proveedor = new Proveedor(1L, "Tersuave", "30-12345678-9");
        proveedor.desactivar();

        proveedor.actualizar("Tersuave", null, true);

        assertThat(proveedor.getCuit()).isNull();
        assertThat(proveedor.isActivo()).isTrue();
    }

    @Test
    @DisplayName("actualizar no cambia la ferreteria")
    void actualizarNoCambiaLaFerreteria() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);

        proveedor.actualizar("Otro nombre", null, true);

        assertThat(proveedor.getFerreteriaId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("desactivar pone activo en false y conserva nombre y CUIT")
    void desactivar() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", "30-12345678-9");

        proveedor.desactivar();

        assertThat(proveedor.isActivo()).isFalse();
        assertThat(proveedor.getNombre()).isEqualTo("Sinteplast");
        assertThat(proveedor.getCuit()).isEqualTo("30-12345678-9");
    }
}
