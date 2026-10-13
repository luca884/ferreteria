package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
    @DisplayName("un proveedor nuevo no tiene lista configurada")
    void nuevoSinLista() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);

        assertThat(proveedor.getMapeoLista()).isNull();
        assertThat(proveedor.isListaIncluyeIva()).isFalse();
        assertThat(proveedor.getBonificacion1()).isNull();
        assertThat(proveedor.getBonificacion2()).isNull();
        assertThat(proveedor.getBonificacion3()).isNull();
        assertThat(proveedor.bonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
    }

    @Test
    @DisplayName("configurarLista fija el mapeo, el IVA y las tres bonificaciones")
    void configurarListaFijaTodo() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);
        MapeoLista mapeo = new MapeoLista(0, 2, 0, 1, 2, new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));
        Bonificaciones bonificaciones =
                new Bonificaciones(new BigDecimal("40"), new BigDecimal("10"), new BigDecimal("5"));

        proveedor.configurarLista(mapeo, true, bonificaciones);

        assertThat(proveedor.getMapeoLista()).isEqualTo(mapeo);
        assertThat(proveedor.isListaIncluyeIva()).isTrue();
        assertThat(proveedor.getBonificacion1()).isEqualByComparingTo("40");
        assertThat(proveedor.getBonificacion2()).isEqualByComparingTo("10");
        assertThat(proveedor.getBonificacion3()).isEqualByComparingTo("5");
        assertThat(proveedor.bonificaciones()).isEqualTo(bonificaciones);
    }

    @Test
    @DisplayName("configurarLista con bonificaciones vacias deja las tres en null")
    void configurarListaSinBonificaciones() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);
        proveedor.configurarLista(null, true, new Bonificaciones(new BigDecimal("40"), null, null));

        proveedor.configurarLista(null, false, Bonificaciones.NINGUNA);

        assertThat(proveedor.isListaIncluyeIva()).isFalse();
        assertThat(proveedor.getBonificacion1()).isNull();
        assertThat(proveedor.bonificaciones()).isEqualTo(Bonificaciones.NINGUNA);
    }

    @Test
    @DisplayName("actualizar no toca el mapeo, el IVA ni las bonificaciones")
    void actualizarNoTocaLaLista() {
        Proveedor proveedor = new Proveedor(1L, "Sinteplast", null);
        MapeoLista mapeo = new MapeoLista(0, 2, 0, 1, 2, null);
        proveedor.configurarLista(mapeo, true, new Bonificaciones(new BigDecimal("40"), null, null));

        proveedor.actualizar("Sinteplast SA", "30-12345678-9", false);

        assertThat(proveedor.getMapeoLista()).isEqualTo(mapeo);
        assertThat(proveedor.isListaIncluyeIva()).isTrue();
        assertThat(proveedor.getBonificacion1()).isEqualByComparingTo("40");
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
