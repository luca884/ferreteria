package com.ferreteria.stock.service;

import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.stock.repository.MovimientoStockRepository.MovimientoListado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MovimientosArticuloTest {

    @ParameterizedTest
    @EnumSource(TipoCompra.class)
    @DisplayName("la compra se nombra como en Compras: «Remito 00036-03608111 · Proveedor»")
    void compraComoEnCompras(TipoCompra tipo) {
        MovimientoListado fila = compra(tipo.name(), "Bulonera Sur");

        assertThat(MovimientosArticulo.comprobante(fila))
                .isEqualTo(tipo.comprobante(36, 3608111) + " · Bulonera Sur");
    }

    @ParameterizedTest
    @EnumSource(TipoCompra.class)
    @DisplayName("sin proveedor queda solo el comprobante")
    void compraSinProveedor(TipoCompra tipo) {
        assertThat(MovimientosArticulo.comprobante(compra(tipo.name(), null)))
                .isEqualTo(tipo.comprobante(36, 3608111));
    }

    private static MovimientoListado compra(String tipo, String proveedor) {
        MovimientoListado fila = mock(MovimientoListado.class);
        when(fila.getVentaNumero()).thenReturn(null);
        when(fila.getRemitoNumero()).thenReturn(null);
        when(fila.getCompraTipo()).thenReturn(tipo);
        when(fila.getCompraPuntoVenta()).thenReturn(36);
        when(fila.getCompraNumero()).thenReturn(3608111L);
        when(fila.getProveedor()).thenReturn(proveedor);
        return fila;
    }
}
