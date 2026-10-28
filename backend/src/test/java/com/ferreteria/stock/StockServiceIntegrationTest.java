package com.ferreteria.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** StockService contra MySQL real: el movimiento se guarda, el cache baja y las dos cosas siempre cuadran. */
class StockServiceIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    StockService stockService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("una venta deja un movimiento VENTA por articulo y el cache queda igual a la suma de movimientos")
    void ventaDejaMovimientosYElCacheCuadra() {
        Escenario e = escenario();
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());

        Map<Long, BigDecimal> resultante = new TransactionTemplate(transactionManager).execute(status ->
                stockService.registrarVenta(e.comprobanteId(), e.usuarioId(), HoraArgentina.ahora(), List.of(
                        new SalidaStock(e.tornillo(), new BigDecimal("3")),
                        new SalidaStock(e.cable(), new BigDecimal("2.5")),
                        new SalidaStock(e.tornillo(), new BigDecimal("1")))));

        assertThat(resultante.get(e.tornillo())).isEqualByComparingTo("6");
        assertThat(resultante.get(e.cable())).isEqualByComparingTo("7.5");
        assertThat(movimientos(e.tornillo(), "VENTA")).hasSize(1);
        assertThat(movimientos(e.tornillo(), "VENTA").get(0)).isEqualByComparingTo("-4");
        assertThat(movimientos(e.cable(), "VENTA").get(0)).isEqualByComparingTo("-2.500");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_stock where comprobante_id = ?",
                Integer.class, e.comprobanteId())).isEqualTo(2);
        for (Long articuloId : List.of(e.tornillo(), e.cable())) {
            assertThat(stockEnBase(articuloId)).as("cache de " + articuloId).isEqualByComparingTo(sumaDeMovimientos(articuloId));
        }
    }

    @Test
    @DisplayName("el stock puede quedar negativo y el cache sigue siendo la suma de movimientos")
    void stockNegativoCuadra() {
        Escenario e = escenario();
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());

        Map<Long, BigDecimal> resultante = new TransactionTemplate(transactionManager).execute(status ->
                stockService.registrarVenta(e.comprobanteId(), e.usuarioId(), HoraArgentina.ahora(),
                        List.of(new SalidaStock(e.tornillo(), new BigDecimal("15")))));

        assertThat(resultante.get(e.tornillo())).isEqualByComparingTo("-5");
        assertThat(stockEnBase(e.tornillo())).isEqualByComparingTo(sumaDeMovimientos(e.tornillo()));
    }

    @Test
    @DisplayName("si la transaccion se revierte no queda ni movimiento ni descuento")
    void unaTransaccionRevertidaNoDejaNada() {
        Escenario e = escenario();
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());

        new TransactionTemplate(transactionManager).execute(status -> {
            stockService.registrarVenta(e.comprobanteId(), e.usuarioId(), HoraArgentina.ahora(),
                    List.of(new SalidaStock(e.tornillo(), new BigDecimal("3"))));
            status.setRollbackOnly();
            return null;
        });

        assertThat(movimientos(e.tornillo(), "VENTA")).isEmpty();
        assertThat(stockEnBase(e.tornillo())).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un articulo de otra ferreteria no se descuenta ni deja movimiento")
    void articuloAjeno() {
        Escenario a = escenario();
        Escenario b = escenario();
        TenantContext.setCurrentFerreteriaId(a.ferreteriaId());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                stockService.registrarVenta(a.comprobanteId(), a.usuarioId(), HoraArgentina.ahora(),
                        List.of(new SalidaStock(b.tornillo(), new BigDecimal("3"))))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stockEnBase(b.tornillo())).isEqualByComparingTo("10");
        assertThat(movimientos(b.tornillo(), "VENTA")).isEmpty();
    }

    // ---- apoyo ----

    private List<BigDecimal> movimientos(Long articuloId, String tipo) {
        return jdbc.queryForList("select cantidad from movimiento_stock where articulo_id = ? and tipo = ?",
                BigDecimal.class, articuloId, tipo);
    }

    private BigDecimal sumaDeMovimientos(Long articuloId) {
        return jdbc.queryForObject("select coalesce(sum(cantidad), 0) from movimiento_stock where articulo_id = ?",
                BigDecimal.class, articuloId);
    }

    private BigDecimal stockEnBase(Long articuloId) {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private record Escenario(Long ferreteriaId, Long usuarioId, Long comprobanteId, Long tornillo, Long cable) {}

    /** Ferreteria con dos articulos de stock 10 (cada uno con su AJUSTE inicial) y un comprobante para colgar los movimientos. */
    private Escenario escenario() {
        String cuit = String.format("30-%08d-%d", 79900000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Stock mov " + cuit, cuit);
        Long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "stockmov-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno', 'DUENO')", f, email);
        Long u = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        Long tornillo = articuloConAjusteInicial(f, u, "TOR-1", "UNIDAD");
        Long cable = articuloConAjusteInicial(f, u, "CAB-1", "METRO");
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, "
                + "neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, ?, ?, 0, 0, 0, 0)",
                f, "00000000-0000-4000-8000-" + String.format("%012d", SECUENCIA.get()), u);
        Long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, u, comprobante, tornillo, cable);
    }

    private Long articuloConAjusteInicial(Long f, Long u, String codigo, String unidad) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                + "stock_actual) values (?, ?, ?, 100.00, 170.00, ?, 10)", f, codigo, "Articulo " + codigo, unidad);
        Long id = jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class, f, codigo);
        jdbc.update("insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, usuario_id, motivo) "
                + "values (?, ?, 'AJUSTE', 10, ?, 'Stock inicial')", f, id, u);
        return id;
    }
}
