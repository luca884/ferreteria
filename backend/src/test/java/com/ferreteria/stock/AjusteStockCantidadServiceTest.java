package com.ferreteria.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.model.MotivoAjuste;
import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.stock.repository.MovimientoStockRepository;
import com.ferreteria.stock.service.AjusteRegistrado;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Ajuste por cantidad (CU-09): suma o resta N al stock vigente al confirmar, con lock y sin stock real. */
@ExtendWith(MockitoExtension.class)
class AjusteStockCantidadServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;
    private static final Long ARTICULO = 40L;

    @Mock
    ArticulosParaVenta articulos;

    @Mock
    MovimientoStockRepository movimientoRepository;

    StockService service;

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        service = new StockService(articulos, movimientoRepository);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private void existe(String unidad, String stockVigente) {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(new ArticuloVendible(
                ARTICULO, "Tornillo", unidad, !"UNIDAD".equals(unidad), bd("170"), "IVA_21", bd("21"), true)));
        when(articulos.stockVigente(ARTICULO)).thenReturn(Optional.of(bd(stockVigente)));
    }

    @Test
    @DisplayName("sumar 25 con 10 vigentes: aplica +25 sobre el stock leido con lock y guarda el AJUSTE")
    void suma() {
        existe("UNIDAD", "10");
        when(articulos.ajustarStock(Map.of(ARTICULO, bd("25")))).thenReturn(Map.of(ARTICULO, bd("35")));

        AjusteRegistrado ajuste =
                service.ajustarPorCantidad(ARTICULO, bd("25"), MotivoAjuste.INGRESO_MERCADERIA, "remito 7", USUARIO);

        assertThat(ajuste.stockAnterior()).isEqualByComparingTo("10");
        assertThat(ajuste.diferencia()).isEqualByComparingTo("25");
        assertThat(ajuste.stockActual()).isEqualByComparingTo("35");
        ArgumentCaptor<MovimientoStock> captor = ArgumentCaptor.forClass(MovimientoStock.class);
        verify(movimientoRepository).save(captor.capture());
        assertThat(captor.getValue().getCantidad()).isEqualByComparingTo("25");
        assertThat(captor.getValue().getMotivo()).isEqualTo("Ingreso de mercadería: remito 7");
        InOrder orden = inOrder(articulos, movimientoRepository);
        orden.verify(articulos).stockVigente(ARTICULO);
        orden.verify(articulos).ajustarStock(any());
        orden.verify(movimientoRepository).save(any());
    }

    @Test
    @DisplayName("restar 4,5 metros de 150: aplica -4,5")
    void restaConDecimales() {
        existe("METRO", "150");
        when(articulos.ajustarStock(Map.of(ARTICULO, bd("-4.5")))).thenReturn(Map.of(ARTICULO, bd("145.5")));

        AjusteRegistrado ajuste = service.ajustarPorCantidad(ARTICULO, bd("-4.5"), MotivoAjuste.ROTURA, null, USUARIO);

        assertThat(ajuste.diferencia()).isEqualByComparingTo("-4.5");
        assertThat(ajuste.stockActual()).isEqualByComparingTo("145.5");
    }

    @Test
    @DisplayName("sacar mas de lo que hay: 400 en cantidad y no toca nada")
    void noQuedaNegativo() {
        existe("UNIDAD", "3");

        assertThatThrownBy(() -> service.ajustarPorCantidad(ARTICULO, bd("-5"), MotivoAjuste.ROTURA, null, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsOnly(Map.entry("cantidad", "No podés sacar 5: hay 3 unidades.")));

        verify(articulos, never()).ajustarStock(any());
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("cantidad cero, decimales en UNIDAD y OTRO sin detalle: errores por campo juntos")
    void erroresJuntos() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(new ArticuloVendible(
                ARTICULO, "Tornillo", "UNIDAD", false, bd("170"), "IVA_21", bd("21"), true)));

        assertThatThrownBy(() -> service.ajustarPorCantidad(ARTICULO, bd("2.5"), MotivoAjuste.OTRO, " ", USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                        Map.entry("cantidad",
                                "Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero."),
                        Map.entry("detalle", "Contá el motivo del ajuste.")));
        assertThatThrownBy(() -> service.ajustarPorCantidad(ARTICULO, BigDecimal.ZERO, MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsOnly(Map.entry("cantidad", "La cantidad no puede ser cero.")));
        verify(articulos, never()).stockVigente(anyLong());
    }

    @Test
    @DisplayName("un articulo dado de baja no se ajusta, en ningun modo")
    void deBaja() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(new ArticuloVendible(
                ARTICULO, "Tornillo", "UNIDAD", false, bd("170"), "IVA_21", bd("21"), false)));

        assertThatThrownBy(() -> service.ajustarPorCantidad(ARTICULO, bd("5"), MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOf(com.ferreteria.common.exception.ReglaDeNegocioException.class)
                .hasMessage("\"Tornillo\" está dado de baja. Reactivalo para ajustar el stock.");
        assertThatThrownBy(() -> service.ajustar(ARTICULO, bd("5"), MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOf(com.ferreteria.common.exception.ReglaDeNegocioException.class);
        verify(articulos, never()).stockVigente(anyLong());
        verify(articulos, never()).ajustarStock(any());
    }

    @Test
    @DisplayName("un articulo ajeno o inexistente da 404")
    void ajeno() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of());

        assertThatThrownBy(() -> service.ajustarPorCantidad(ARTICULO, bd("5"), MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(articulos, never()).ajustarStock(any());
    }
}
