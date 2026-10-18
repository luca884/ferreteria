package com.ferreteria.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.stock.model.TipoMovimiento;
import com.ferreteria.stock.repository.MovimientoStockRepository;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;
    private static final Long COMPROBANTE = 77L;

    @Mock
    ArticulosParaVenta articulosParaVenta;

    @Mock
    MovimientoStockRepository movimientoRepository;

    StockService service;

    @BeforeEach
    void preparar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        service = new StockService(articulosParaVenta, movimientoRepository);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> deltasEnviados() {
        ArgumentCaptor<Map<Long, BigDecimal>> captor = ArgumentCaptor.forClass(Map.class);
        verify(articulosParaVenta).ajustarStock(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<MovimientoStock> movimientosGuardados() {
        ArgumentCaptor<List<MovimientoStock>> captor = ArgumentCaptor.forClass(List.class);
        verify(movimientoRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("agrupa las salidas por articulo en orden de id y manda los deltas en negativo")
    void agrupaPorArticuloEnOrdenDeId() {
        when(articulosParaVenta.ajustarStock(any())).thenReturn(Map.of());

        service.registrarVenta(COMPROBANTE, USUARIO, List.of(
                new SalidaStock(30L, bd("1")), new SalidaStock(10L, bd("2")), new SalidaStock(30L, bd("4")),
                new SalidaStock(20L, bd("0.5"))));

        Map<Long, BigDecimal> deltas = deltasEnviados();
        assertThat(new ArrayList<>(deltas.keySet())).containsExactly(10L, 20L, 30L);
        assertThat(deltas.get(10L)).isEqualByComparingTo("-2");
        assertThat(deltas.get(20L)).isEqualByComparingTo("-0.5");
        assertThat(deltas.get(30L)).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("ajusta el stock ANTES de guardar los movimientos (los locks van antes de los INSERT)")
    void ajustaAntesDeGuardar() {
        when(articulosParaVenta.ajustarStock(any())).thenReturn(Map.of());

        service.registrarVenta(COMPROBANTE, USUARIO, List.of(new SalidaStock(1L, bd("3"))));

        InOrder orden = inOrder(articulosParaVenta, movimientoRepository);
        orden.verify(articulosParaVenta).ajustarStock(any());
        orden.verify(movimientoRepository).saveAll(any());
    }

    @Test
    @DisplayName("un movimiento VENTA por articulo, con cantidad negativa, comprobante, usuario y ferreteria")
    void unMovimientoVentaPorArticulo() {
        when(articulosParaVenta.ajustarStock(any())).thenReturn(Map.of());

        service.registrarVenta(COMPROBANTE, USUARIO, List.of(
                new SalidaStock(2L, bd("3")), new SalidaStock(1L, bd("1")), new SalidaStock(2L, bd("2"))));

        List<MovimientoStock> movimientos = movimientosGuardados();
        assertThat(movimientos).hasSize(2);
        assertThat(movimientos).extracting(MovimientoStock::getArticuloId).containsExactly(1L, 2L);
        assertThat(movimientos.get(0).getCantidad()).isEqualByComparingTo("-1");
        assertThat(movimientos.get(1).getCantidad()).isEqualByComparingTo("-5");
        for (MovimientoStock m : movimientos) {
            assertThat(m.getTipo()).isEqualTo(TipoMovimiento.VENTA);
            assertThat(m.getComprobanteId()).isEqualTo(COMPROBANTE);
            assertThat(m.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(m.getFerreteriaId()).isEqualTo(FERRETERIA);
        }
    }

    @Test
    @DisplayName("2,5 m da un movimiento de -2,500")
    void cantidadDecimal() {
        when(articulosParaVenta.ajustarStock(any())).thenReturn(Map.of());

        service.registrarVenta(COMPROBANTE, USUARIO, List.of(new SalidaStock(4L, bd("2.5"))));

        assertThat(movimientosGuardados().get(0).getCantidad()).isEqualByComparingTo("-2.500");
        assertThat(deltasEnviados().get(4L)).isEqualByComparingTo("-2.500");
    }

    @Test
    @DisplayName("devuelve el stock resultante que informo el puerto")
    void devuelveElStockResultante() {
        Map<Long, BigDecimal> resultante = Map.of(1L, bd("7"), 2L, bd("-3"));
        when(articulosParaVenta.ajustarStock(any())).thenReturn(resultante);

        Map<Long, BigDecimal> devuelto = service.registrarVenta(COMPROBANTE, USUARIO, List.of(
                new SalidaStock(1L, bd("3")), new SalidaStock(2L, bd("5"))));

        assertThat(devuelto).isEqualTo(resultante);
    }

    @Test
    @DisplayName("sin salidas no toca el stock ni guarda nada")
    void sinSalidas() {
        Map<Long, BigDecimal> devuelto = service.registrarVenta(COMPROBANTE, USUARIO, List.of());

        assertThat(devuelto).isEmpty();
        verify(articulosParaVenta, never()).ajustarStock(any());
        verify(movimientoRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("si el ajuste de stock falla no se guarda ningun movimiento")
    void siFallaElAjusteNoSeGuardaNada() {
        when(articulosParaVenta.ajustarStock(any())).thenThrow(new IllegalStateException("sin fila"));

        assertThatThrownBy(() -> service.registrarVenta(COMPROBANTE, USUARIO, List.of(new SalidaStock(1L, bd("1")))))
                .isInstanceOf(IllegalStateException.class).hasMessage("sin fila");

        verify(movimientoRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("el remito agrupa por articulo en orden de id, descuenta y guarda un movimiento REMITO por articulo")
    void remitoDescuentaYGuardaMovimientos() {
        Map<Long, BigDecimal> resultante = Map.of(1L, bd("-1"), 2L, bd("4"));
        when(articulosParaVenta.ajustarStock(any())).thenReturn(resultante);

        Map<Long, BigDecimal> devuelto = service.registrarRemito(70L, USUARIO, List.of(
                new SalidaStock(2L, bd("3")), new SalidaStock(1L, bd("1")), new SalidaStock(2L, bd("2"))));

        Map<Long, BigDecimal> deltas = deltasEnviados();
        assertThat(new ArrayList<>(deltas.keySet())).containsExactly(1L, 2L);
        assertThat(deltas.get(1L)).isEqualByComparingTo("-1");
        assertThat(deltas.get(2L)).isEqualByComparingTo("-5");
        List<MovimientoStock> movimientos = movimientosGuardados();
        assertThat(movimientos).extracting(MovimientoStock::getArticuloId).containsExactly(1L, 2L);
        for (MovimientoStock m : movimientos) {
            assertThat(m.getTipo()).isEqualTo(TipoMovimiento.REMITO);
            assertThat(m.getRemitoId()).isEqualTo(70L);
            assertThat(m.getComprobanteId()).isNull();
            assertThat(m.getUsuarioId()).isEqualTo(USUARIO);
            assertThat(m.getFerreteriaId()).isEqualTo(FERRETERIA);
        }
        assertThat(devuelto).isEqualTo(resultante);
    }

    @Test
    @DisplayName("el remito ajusta el stock ANTES de guardar los movimientos")
    void remitoAjustaAntesDeGuardar() {
        when(articulosParaVenta.ajustarStock(any())).thenReturn(Map.of());

        service.registrarRemito(70L, USUARIO, List.of(new SalidaStock(1L, bd("3"))));

        InOrder orden = inOrder(articulosParaVenta, movimientoRepository);
        orden.verify(articulosParaVenta).ajustarStock(any());
        orden.verify(movimientoRepository).saveAll(any());
    }

    @Test
    @DisplayName("un remito sin salidas no toca el stock ni guarda nada")
    void remitoSinSalidas() {
        assertThat(service.registrarRemito(70L, USUARIO, List.of())).isEmpty();

        verify(articulosParaVenta, never()).ajustarStock(any());
        verify(movimientoRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("anular un remito devuelve cada articulo con delta positivo y un movimiento ANULACION_REMITO")
    void anularRemitoDevuelveElStock() {
        Map<Long, BigDecimal> resultante = Map.of(1L, bd("9"), 2L, bd("6"));
        when(articulosParaVenta.ajustarStock(any())).thenReturn(resultante);

        Map<Long, BigDecimal> devuelto = service.devolverRemito(70L, USUARIO, List.of(
                new SalidaStock(2L, bd("3")), new SalidaStock(1L, bd("1")), new SalidaStock(2L, bd("2"))));

        Map<Long, BigDecimal> deltas = deltasEnviados();
        assertThat(new ArrayList<>(deltas.keySet())).containsExactly(1L, 2L);
        assertThat(deltas.get(1L)).isEqualByComparingTo("1");
        assertThat(deltas.get(2L)).isEqualByComparingTo("5");
        List<MovimientoStock> movimientos = movimientosGuardados();
        assertThat(movimientos).extracting(MovimientoStock::getTipo)
                .containsOnly(TipoMovimiento.ANULACION_REMITO);
        assertThat(movimientos.get(1).getCantidad()).isEqualByComparingTo("5");
        assertThat(movimientos.get(1).getRemitoId()).isEqualTo(70L);
        assertThat(devuelto).isEqualTo(resultante);
    }

    @Test
    @DisplayName("registrarRemito y devolverRemito exigen una transaccion abierta (MANDATORY)")
    void remitoExigeTransaccion() throws NoSuchMethodException {
        assertThat(StockService.class.getMethod("registrarRemito", Long.class, Long.class, List.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(StockService.class.getMethod("devolverRemito", Long.class, Long.class, List.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("registrarVenta exige una transaccion abierta (MANDATORY)")
    void exigeTransaccion() throws NoSuchMethodException {
        Transactional anotacion = StockService.class
                .getMethod("registrarVenta", Long.class, Long.class, List.class).getAnnotation(Transactional.class);

        assertThat(anotacion.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
