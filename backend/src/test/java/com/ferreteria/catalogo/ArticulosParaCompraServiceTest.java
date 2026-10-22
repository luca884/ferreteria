package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto que catalogo le da a compras (CU-13): planear y aplicar el costo de una compra. El costo del renglon pisa
 * el del articulo (ultimo costo) y el precio se recalcula con la regla de precios. Contra MySQL real.
 * Margen por defecto de la ferreteria 30 %, sin redondeo: costo 100 e IVA 21 % dan 157,30.
 */
class ArticulosParaCompraServiceTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    ArticulosParaCompra articulosParaCompra;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- planear ----

    @Test
    @DisplayName("planear calcula costo y precio anteriores y nuevos sin escribir nada")
    void planearNoEscribe() {
        Long f = ferreteria();
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        List<CostoDeCompra> plan = articulosParaCompra.planear(Map.of(tornillo, new BigDecimal("110.00")));

        assertThat(plan).hasSize(1);
        CostoDeCompra c = plan.get(0);
        assertThat(c.articuloId()).isEqualTo(tornillo);
        assertThat(c.codigoInterno()).isEqualTo("TOR-1");
        assertThat(c.descripcion()).isEqualTo("Tornillo 1/4");
        assertThat(c.unidad()).isEqualTo("UNIDAD");
        assertThat(c.moneda()).isEqualTo("ARS");
        assertThat(c.activo()).isTrue();
        assertThat(c.costoAnterior()).isEqualByComparingTo("100.00");
        assertThat(c.costoNuevo()).isEqualByComparingTo("110.00");
        assertThat(c.precioAnterior()).isEqualByComparingTo("157.30");
        assertThat(c.precioNuevo()).isEqualByComparingTo("173.03");
        assertThat(c.advertencia()).isNull();
        assertThat(costoEnBase(tornillo)).isEqualByComparingTo("100.00");
        assertThat(precioEnBase(tornillo)).isEqualByComparingTo("157.30");
    }

    @Test
    @DisplayName("planear advierte la variacion fuerte del costo, igual que CU-04 y CU-05")
    void planearAdvierteVariacionFuerte() {
        Long f = ferreteria();
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        CostoDeCompra c = articulosParaCompra.planear(Map.of(tornillo, new BigDecimal("200.00"))).get(0);

        assertThat(c.advertencia()).isEqualTo(
                "El costo sube un 100 % (de 100,00 a 200,00). Revisá que sea correcto.");
    }

    @Test
    @DisplayName("planear no trae articulos de otra ferreteria ni inexistentes")
    void planearAislado() {
        Long f = ferreteria();
        Long otra = ferreteria();
        Long propio = articulo(f, "TOR-1", "Tornillo", "100.00", "157.30");
        Long ajeno = articulo(otra, "TOR-1", "Tornillo ajeno", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        Map<Long, BigDecimal> costos = new TreeMap<>(Map.of(
                propio, new BigDecimal("120"), ajeno, new BigDecimal("120"), 999_999_999L, new BigDecimal("120")));
        List<CostoDeCompra> plan = articulosParaCompra.planear(costos);

        assertThat(plan).extracting(CostoDeCompra::articuloId).containsExactly(propio);
    }

    // ---- aplicar ----

    @Test
    @DisplayName("aplicar pisa el costo, recalcula el precio y guarda historial COMPRA con el usuario")
    void aplicarPisaElCostoYGuardaHistorial() {
        Long f = ferreteria();
        Long usuario = usuario(f);
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "100.00", "157.30");
        Long tuerca = articulo(f, "TUE-1", "Tuerca", "50.00", "78.65");
        TenantContext.setCurrentFerreteriaId(f);

        List<CostoDeCompra> aplicado = enTransaccion(() -> articulosParaCompra.aplicar(
                new TreeMap<>(Map.of(tornillo, new BigDecimal("110.00"), tuerca, new BigDecimal("40.00"))), usuario));

        assertThat(aplicado).extracting(CostoDeCompra::articuloId).containsExactly(tornillo, tuerca);
        assertThat(costoEnBase(tornillo)).isEqualByComparingTo("110.00");
        assertThat(precioEnBase(tornillo)).isEqualByComparingTo("173.03");
        assertThat(costoEnBase(tuerca)).isEqualByComparingTo("40.00");
        assertThat(precioEnBase(tuerca)).isEqualByComparingTo("62.92");
        Map<String, Object> historial = jdbc.queryForMap(
                "select costo_anterior, costo_nuevo, precio_anterior, precio_nuevo, origen, usuario_id "
                        + "from historial_precio where articulo_id = ?", tornillo);
        assertThat((BigDecimal) historial.get("costo_anterior")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) historial.get("costo_nuevo")).isEqualByComparingTo("110.00");
        assertThat((BigDecimal) historial.get("precio_anterior")).isEqualByComparingTo("157.30");
        assertThat((BigDecimal) historial.get("precio_nuevo")).isEqualByComparingTo("173.03");
        assertThat(historial.get("origen")).isEqualTo("COMPRA");
        assertThat(((Number) historial.get("usuario_id")).longValue()).isEqualTo(usuario);
    }

    @Test
    @DisplayName("aplicar el mismo costo y precio no deja historial")
    void aplicarSinCambioNoDejaHistorial() {
        Long f = ferreteria();
        Long usuario = usuario(f);
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        enTransaccion(() -> articulosParaCompra.aplicar(Map.of(tornillo, new BigDecimal("100")), usuario));

        assertThat(jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ?",
                Integer.class, tornillo)).isZero();
    }

    @Test
    @DisplayName("aplicar exige una transaccion abierta")
    void aplicarExigeTransaccion() {
        Long f = ferreteria();
        Long usuario = usuario(f);
        Long tornillo = articulo(f, "TOR-1", "Tornillo 1/4", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        assertThatThrownBy(() -> articulosParaCompra.aplicar(Map.of(tornillo, new BigDecimal("110")), usuario))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("aplicar un articulo de otra ferreteria falla y no cambia nada")
    void aplicarAjenoFalla() {
        Long f = ferreteria();
        Long otra = ferreteria();
        Long usuario = usuario(f);
        Long ajeno = articulo(otra, "TOR-1", "Tornillo ajeno", "100.00", "157.30");
        TenantContext.setCurrentFerreteriaId(f);

        assertThatThrownBy(() -> enTransaccion(() ->
                articulosParaCompra.aplicar(Map.of(ajeno, new BigDecimal("110")), usuario)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(costoEnBase(ajeno)).isEqualByComparingTo("100.00");
    }

    // ---- helpers ----

    private <T> T enTransaccion(java.util.function.Supplier<T> accion) {
        return new TransactionTemplate(transactionManager).execute(estado -> accion.get());
    }

    private BigDecimal costoEnBase(Long id) {
        return jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id);
    }

    private BigDecimal precioEnBase(Long id) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, id);
    }

    private Long ferreteria() {
        String cuit = String.format("30-%08d-%d", 79900000 + SECUENCIA.incrementAndGet(), 2);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Compra " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long usuario(Long ferreteriaId) {
        String email = "dueno" + SECUENCIA.incrementAndGet() + "@compra.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno Compra', 'DUENO')", ferreteriaId, email);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private Long articulo(Long ferreteriaId, String codigo, String descripcion, String costo, String precio) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                        + "alicuota_iva, stock_actual, activo) values (?, ?, ?, ?, ?, 'UNIDAD', 'IVA_21', 0, true)",
                ferreteriaId, codigo, descripcion, costo, precio);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }
}
