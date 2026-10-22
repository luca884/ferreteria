package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.RegistrarCompra;
import com.ferreteria.compras.service.RegistrarCompra.RenglonCompra;
import com.ferreteria.compras.service.ValidacionCompra;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reglas de la compra (CU-13) que no dependen de la base. */
class ValidacionCompraTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 21);

    @Test
    @DisplayName("un articulo repetido en la compra es 400 en el renglon que repite")
    void articuloRepetido() {
        RegistrarCompra pedido = pedido(HOY, renglon(1L, "1"), renglon(2L, "1"), renglon(1L, "2"));

        assertThatThrownBy(() -> ValidacionCompra.forma(pedido, HOY))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("renglones[2].articuloId", "El artículo está repetido en la compra."));
    }

    @Test
    @DisplayName("la fecha puede ser hoy pero no posterior")
    void fechaFutura() {
        assertThatCode(() -> ValidacionCompra.forma(pedido(HOY, renglon(1L, "1")), HOY)).doesNotThrowAnyException();

        assertThatThrownBy(() -> ValidacionCompra.forma(pedido(HOY.plusDays(1), renglon(1L, "1")), HOY))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("fecha", "La fecha no puede ser posterior a hoy."));
    }

    @Test
    @DisplayName("un articulo por unidad no admite cantidad con decimales; uno por kilo si")
    void cantidadEnteraPorUnidad() {
        RegistrarCompra pedido = pedido(HOY, renglon(1L, "2.5"), renglon(2L, "2.5"));
        List<CostoDeCompra> costos = List.of(costo(1L, "Tornillo", "UNIDAD", "ARS"), costo(2L, "Clavo", "KILO", "ARS"));

        assertThatThrownBy(() -> ValidacionCompra.articulos(pedido, costos))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("renglones[0].cantidad",
                        "Tornillo se compra por unidad: la cantidad tiene que ser entera."));
    }

    @Test
    @DisplayName("una compra no mezcla articulos en pesos y en dolares")
    void monedasMezcladas() {
        RegistrarCompra pedido = pedido(HOY, renglon(1L, "1"), renglon(2L, "1"));
        List<CostoDeCompra> costos = List.of(costo(1L, "Tornillo", "UNIDAD", "ARS"), costo(2L, "Taladro", "UNIDAD", "USD"));

        assertThatThrownBy(() -> ValidacionCompra.articulos(pedido, costos))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("renglones", "Una compra no puede mezclar artículos en pesos y en dólares."));
    }

    @Test
    @DisplayName("el comprobante se muestra con el tipo, el punto de venta a 5 digitos y el numero a 8")
    void comprobante() {
        assertThat(TipoCompra.FA.comprobante(3, 1234L)).isEqualTo("Factura A 00003-00001234");
        assertThat(TipoCompra.REM.comprobante(12, 5L)).isEqualTo("Remito 00012-00000005");
    }

    private static RegistrarCompra pedido(LocalDate fecha, RenglonCompra... renglones) {
        return new RegistrarCompra(null, 1L, TipoCompra.FA, 1, 1L, fecha, List.of(renglones));
    }

    private static RenglonCompra renglon(Long articuloId, String cantidad) {
        return new RenglonCompra(articuloId, new BigDecimal(cantidad), BigDecimal.TEN);
    }

    private static CostoDeCompra costo(Long id, String descripcion, String unidad, String moneda) {
        return new CostoDeCompra(id, "C-" + id, descripcion, unidad, moneda, true,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, null);
    }
}
