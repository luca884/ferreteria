package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.LectorDeFacturas;
import com.ferreteria.catalogo.api.ProveedoresParaCompra;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.compras.service.LecturaDeFactura;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reglas de la lectura de la factura en PDF (CU-13) que no dependen de la base. */
class LecturaDeFacturaTest {

    private final LectorDeFacturas lector = mock(LectorDeFacturas.class);
    private final ProveedoresParaCompra proveedores = mock(ProveedoresParaCompra.class);
    private final ArticulosParaCompra articulos = mock(ArticulosParaCompra.class);
    private final LecturaDeFactura lectura = new LecturaDeFactura(lector, proveedores, articulos);

    @Test
    @DisplayName("una factura con mas de 500 renglones no parece una factura: 400 sin buscar proveedor ni articulos")
    void masDeQuinientosRenglones() {
        when(lector.leer(any())).thenReturn(factura(501));

        assertThatThrownBy(() -> lectura.leer(new byte[0]))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("La factura trae más de 500 renglones: no parece una factura. Cargá la compra a mano.");
        verifyNoInteractions(proveedores, articulos);
    }

    @Test
    @DisplayName("una factura con 500 renglones, el tope de la compra, se lee")
    void quinientosRenglones() {
        when(lector.leer(any())).thenReturn(factura(500));
        when(proveedores.buscarPorCuit(any())).thenReturn(Optional.empty());

        assertThat(lectura.leer(new byte[0]).renglones()).hasSize(500);
    }

    private static FacturaLeida factura(int renglones) {
        return new FacturaLeida(
                new FacturaLeida.Encabezado(null, null, null, null, List.of()),
                IntStream.range(0, renglones)
                        .mapToObj(i -> new FacturaLeida.Renglon("COD-" + i, BigDecimal.ONE, "Renglon " + i,
                                BigDecimal.TEN, BigDecimal.TEN))
                        .toList(),
                List.of(), BigDecimal.valueOf(10L * renglones), FacturaLeida.Control.SIN_CONTROL);
    }
}
