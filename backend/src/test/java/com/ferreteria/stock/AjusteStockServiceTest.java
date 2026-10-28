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
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.model.MotivoAjuste;
import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.stock.model.TipoMovimiento;
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

/** Ajuste manual de stock (CU-09): stock real contado, diferencia calculada y un movimiento AJUSTE. */
@ExtendWith(MockitoExtension.class)
class AjusteStockServiceTest {

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
        service = new StockService(articulos, movimientoRepository, java.time.Clock.system(HoraArgentina.ZONA));
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static ArticuloVendible articulo(String unidad) {
        return new ArticuloVendible(ARTICULO, "Tornillo", unidad, !"UNIDAD".equals(unidad), bd("170"),
                "IVA_21", bd("21"), true, bd("100"));
    }

    private void existe(String unidad, String stockActual) {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(articulo(unidad)));
        when(articulos.stockVigente(ARTICULO)).thenReturn(Optional.of(bd(stockActual)));
    }

    private MovimientoStock movimientoGuardado() {
        ArgumentCaptor<MovimientoStock> captor = ArgumentCaptor.forClass(MovimientoStock.class);
        verify(movimientoRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("12 contados con 10 en sistema: suma 2, guarda un AJUSTE +2 con el motivo y devuelve antes y despues")
    void ajustaHaciaArriba() {
        existe("UNIDAD", "10");
        when(articulos.ajustarStock(Map.of(ARTICULO, bd("2")))).thenReturn(Map.of(ARTICULO, bd("12")));

        AjusteRegistrado ajuste = service.ajustar(ARTICULO, bd("12"), MotivoAjuste.INGRESO_MERCADERIA, null, USUARIO);

        assertThat(ajuste.articuloId()).isEqualTo(ARTICULO);
        assertThat(ajuste.stockAnterior()).isEqualByComparingTo("10");
        assertThat(ajuste.diferencia()).isEqualByComparingTo("2");
        assertThat(ajuste.stockActual()).isEqualByComparingTo("12");
        MovimientoStock m = movimientoGuardado();
        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.AJUSTE);
        assertThat(m.getCantidad()).isEqualByComparingTo("2");
        assertThat(m.getMotivo()).isEqualTo("Ingreso de mercadería");
        assertThat(m.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(m.getUsuarioId()).isEqualTo(USUARIO);
        assertThat(m.getArticuloId()).isEqualTo(ARTICULO);
    }

    @Test
    @DisplayName("una rotura en metros: 145,5 con 150 resta 4,5 y el detalle queda en el motivo")
    void ajustaHaciaAbajoConDecimales() {
        existe("METRO", "150");
        when(articulos.ajustarStock(Map.of(ARTICULO, bd("-4.5")))).thenReturn(Map.of(ARTICULO, bd("145.5")));

        AjusteRegistrado ajuste = service.ajustar(ARTICULO, bd("145.5"), MotivoAjuste.ROTURA, "se cortó mal", USUARIO);

        assertThat(ajuste.stockActual()).isEqualByComparingTo("145.5");
        assertThat(movimientoGuardado().getMotivo()).isEqualTo("Rotura: se cortó mal");
    }

    @Test
    @DisplayName("lee el stock con lock ANTES de ajustar y guarda el movimiento despues del UPDATE")
    void ordenLockUpdateInsert() {
        existe("UNIDAD", "10");
        when(articulos.ajustarStock(any())).thenReturn(Map.of(ARTICULO, bd("7")));

        service.ajustar(ARTICULO, bd("7"), MotivoAjuste.CONTEO, null, USUARIO);

        InOrder orden = inOrder(articulos, movimientoRepository);
        orden.verify(articulos).stockVigente(ARTICULO);
        orden.verify(articulos).ajustarStock(any());
        orden.verify(movimientoRepository).save(any());
    }

    @Test
    @DisplayName("un articulo inexistente o de otra ferreteria da 404 y no toca nada")
    void articuloAjeno() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of());

        assertThatThrownBy(() -> service.ajustar(ARTICULO, bd("5"), MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");

        verify(articulos, never()).stockVigente(anyLong());
        verify(articulos, never()).ajustarStock(any());
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("stock real con decimales en UNIDAD y OTRO sin detalle: los dos errores juntos, por campo")
    void erroresJuntos() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(articulo("UNIDAD")));

        assertThatThrownBy(() -> service.ajustar(ARTICULO, bd("2.5"), MotivoAjuste.OTRO, " ", USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                        Map.entry("stockReal",
                                "Este artículo se cuenta por unidad: el stock real tiene que ser un número entero."),
                        Map.entry("detalle", "Contá el motivo del ajuste.")));

        verify(articulos, never()).ajustarStock(any());
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("sin motivo da 400 en el campo motivo")
    void sinMotivo() {
        when(articulos.buscar(List.of(ARTICULO))).thenReturn(List.of(articulo("UNIDAD")));

        assertThatThrownBy(() -> service.ajustar(ARTICULO, bd("3"), null, null, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsOnly(Map.entry("motivo", "Elegí el motivo del ajuste.")));
    }

    @Test
    @DisplayName("si el stock real es igual al actual da 400 y no guarda nada")
    void sinDiferencia() {
        existe("UNIDAD", "10");

        assertThatThrownBy(() -> service.ajustar(ARTICULO, bd("10"), MotivoAjuste.CONTEO, null, USUARIO))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors()).containsOnly(
                        Map.entry("stockReal", "El stock real es igual al actual: no hay nada que ajustar.")));

        verify(articulos, never()).ajustarStock(any());
        verify(movimientoRepository, never()).save(any());
    }
}
