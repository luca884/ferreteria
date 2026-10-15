package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.service.ArticulosParaVentaService;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto que catalogo le da a ventas y stock (C-004): datos vendibles de un articulo y ajuste de stock.
 * Contra MySQL real (buscar, ajustar, aislamiento) y con el repositorio simulado donde importa el orden
 * de las llamadas.
 */
class ArticulosParaVentaServiceTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    ArticulosParaVenta articulosParaVenta;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- buscar ----

    @Test
    @DisplayName("buscar trae activos e inactivos de la ferreteria, con los datos que copia la venta")
    void buscarTraeActivosEInactivos() {
        Long f = ferreteria();
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "UNIDAD", "IVA_21", "170.00", "500", true);
        Long cable = articulo(f, "CAB-1", "Cable unipolar", "METRO", "IVA_10_5", "790.00", "150.500", true);
        Long viejo = articulo(f, "OLD-1", "Martillo viejo", "UNIDAD", "IVA_21", "5090.00", "0", false);
        TenantContext.setCurrentFerreteriaId(f);

        List<ArticuloVendible> vendibles = articulosParaVenta.buscar(List.of(viejo, cable, tornillo));

        assertThat(vendibles).extracting(ArticuloVendible::id).containsExactlyInAnyOrder(tornillo, cable, viejo);
        ArticuloVendible t = porId(vendibles, tornillo);
        assertThat(t.descripcion()).isEqualTo("Tornillo 1/4");
        assertThat(t.unidad()).isEqualTo("UNIDAD");
        assertThat(t.admiteDecimales()).isFalse();
        assertThat(t.precioFinal()).isEqualByComparingTo("170.00");
        assertThat(t.alicuotaIva()).isEqualTo("IVA_21");
        assertThat(t.porcentajeIva()).isEqualByComparingTo("21.00");
        assertThat(t.activo()).isTrue();
        ArticuloVendible c = porId(vendibles, cable);
        assertThat(c.unidad()).isEqualTo("METRO");
        assertThat(c.admiteDecimales()).isTrue();
        assertThat(c.alicuotaIva()).isEqualTo("IVA_10_5");
        assertThat(c.porcentajeIva()).isEqualByComparingTo("10.50");
        assertThat(porId(vendibles, viejo).activo()).isFalse();
    }

    @Test
    @DisplayName("buscar no trae los de otra ferreteria ni los inexistentes")
    void buscarNoTraeAjenosNiInexistentes() {
        Long a = ferreteria();
        Long b = ferreteria();
        Long propio = articulo(a, "P-1", "Propio", "UNIDAD", "IVA_21", "10.00", "1", true);
        Long ajeno = articulo(b, "A-1", "Ajeno", "UNIDAD", "IVA_21", "10.00", "1", true);
        TenantContext.setCurrentFerreteriaId(a);

        List<ArticuloVendible> vendibles = articulosParaVenta.buscar(List.of(propio, ajeno, 999_999_999L));

        assertThat(vendibles).extracting(ArticuloVendible::id).containsExactly(propio);
    }

    @Test
    @DisplayName("buscar sin ids devuelve vacio sin consultar")
    void buscarSinIds() {
        Long f = ferreteria();
        articulo(f, "X-1", "Existe", "UNIDAD", "IVA_21", "10.00", "1", true);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(articulosParaVenta.buscar(List.of())).isEmpty();
    }

    // ---- ajustarStock ----

    @Test
    @DisplayName("ajustarStock descuenta y devuelve el stock resultante de cada articulo")
    void ajustarStockDevuelveElResultante() {
        Long f = ferreteria();
        Long a = articulo(f, "A-1", "Uno", "UNIDAD", "IVA_21", "10.00", "10", true);
        Long b = articulo(f, "B-1", "Dos", "METRO", "IVA_21", "10.00", "2", true);
        TenantContext.setCurrentFerreteriaId(f);

        Map<Long, BigDecimal> resultante = new TransactionTemplate(transactionManager).execute(status ->
                articulosParaVenta.ajustarStock(Map.of(a, new BigDecimal("-3"), b, new BigDecimal("-2.500"))));

        assertThat(resultante).containsOnlyKeys(a, b);
        assertThat(resultante.get(a)).isEqualByComparingTo("7");
        assertThat(resultante.get(b)).isEqualByComparingTo("-0.5");
        assertThat(stockEnBase(a)).isEqualByComparingTo("7");
        assertThat(stockEnBase(b)).isEqualByComparingTo("-0.5");
    }

    @Test
    @DisplayName("ajustarStock exige una transaccion abierta (MANDATORY)")
    void ajustarStockExigeTransaccion() throws NoSuchMethodException {
        Long f = ferreteria();
        Long a = articulo(f, "A-2", "Uno", "UNIDAD", "IVA_21", "10.00", "10", true);
        TenantContext.setCurrentFerreteriaId(f);

        assertThatThrownBy(() -> articulosParaVenta.ajustarStock(Map.of(a, new BigDecimal("-3"))))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(stockEnBase(a)).isEqualByComparingTo("10");
        Transactional anotacion = ArticulosParaVentaService.class
                .getMethod("ajustarStock", Map.class).getAnnotation(Transactional.class);
        assertThat(anotacion.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("un id de otra ferreteria afecta 0 filas: IllegalStateException y no se descuenta nada")
    void ajustarStockDeUnArticuloAjenoFalla() {
        Long a = ferreteria();
        Long b = ferreteria();
        Long propio = articulo(a, "P-3", "Propio", "UNIDAD", "IVA_21", "10.00", "10", true);
        Long ajeno = articulo(b, "A-3", "Ajeno", "UNIDAD", "IVA_21", "10.00", "10", true);
        TenantContext.setCurrentFerreteriaId(a);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
                articulosParaVenta.ajustarStock(Map.of(propio, new BigDecimal("-1"), ajeno, new BigDecimal("-1")))))
                .isInstanceOf(IllegalStateException.class);

        // La transaccion se revirtio entera: ni el propio ni el ajeno cambiaron.
        assertThat(stockEnBase(propio)).isEqualByComparingTo("10");
        assertThat(stockEnBase(ajeno)).isEqualByComparingTo("10");
    }

    // ---- el orden, con el repositorio simulado ----

    @Test
    @DisplayName("ajustarStock suma por id ASCENDENTE aunque el mapa venga desordenado (evita deadlocks)")
    void ajustarStockVaPorIdAscendente() {
        ArticuloRepository repositorio = mock(ArticuloRepository.class);
        when(repositorio.sumarStockScoped(anyLong(), any())).thenReturn(1);
        when(repositorio.stockDeScoped(any())).thenReturn(Map.of());
        Map<Long, BigDecimal> desordenado = new HashMap<>();
        for (long id : new long[] {42, 7, 1000, 3, 19}) {
            desordenado.put(id, new BigDecimal("-1"));
        }

        new ArticulosParaVentaService(repositorio).ajustarStock(desordenado);

        InOrder orden = inOrder(repositorio);
        for (long id : new long[] {3, 7, 19, 42, 1000}) {
            orden.verify(repositorio).sumarStockScoped(id, new BigDecimal("-1"));
        }
    }

    @Test
    @DisplayName("si el UPDATE afecta mas de una fila tambien es IllegalStateException")
    void masDeUnaFilaTambienFalla() {
        ArticuloRepository repositorio = mock(ArticuloRepository.class);
        when(repositorio.sumarStockScoped(5L, new BigDecimal("-1"))).thenReturn(2);

        assertThatThrownBy(() -> new ArticulosParaVentaService(repositorio)
                .ajustarStock(Map.of(5L, new BigDecimal("-1"))))
                .isInstanceOf(IllegalStateException.class);
        verify(repositorio, never()).stockDeScoped(any());
    }

    // ---- apoyo ----

    private static ArticuloVendible porId(List<ArticuloVendible> vendibles, Long id) {
        return vendibles.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private BigDecimal stockEnBase(Long articuloId) {
        return jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private Long ferreteria() {
        String cuit = String.format("30-%08d-%d", 79800000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Puerto " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long articulo(Long ferreteriaId, String codigo, String descripcion, String unidad, String alicuota,
                          String precio, String stock, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                        + "alicuota_iva, stock_actual, activo) values (?, ?, ?, 100.00, ?, ?, ?, ?, ?)",
                ferreteriaId, codigo, descripcion, precio, unidad, alicuota, stock, activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }
}
