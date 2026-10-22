package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.CompraRegistrada;
import com.ferreteria.compras.service.CompraService;
import com.ferreteria.compras.service.PlanCompra;
import com.ferreteria.compras.service.RegistrarCompra;
import com.ferreteria.compras.service.RegistrarCompra.RenglonCompra;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Registrar una compra (CU-13) contra MySQL real: suma el stock, pisa el costo (ultimo costo), recalcula el precio y deja
 * historial COMPRA, en una sola transaccion e idempotente por clave. Margen 30 %, sin redondeo, IVA 21 %.
 */
class CompraServiceIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static final LocalDate AYER = LocalDate.now().minusDays(1);

    @Autowired
    CompraService compraService;

    Long ferreteria;
    Long usuario;
    Long proveedor;
    Long tornillo;
    Long tuerca;

    @BeforeEach
    void datos() {
        ferreteria = ferreteria();
        usuario = usuario(ferreteria);
        proveedor = proveedor(ferreteria, "Bulonera Sur", true);
        tornillo = articulo(ferreteria, "TOR-1", "Tornillo 1/4", "UNIDAD", "100.00", "157.30", "10", true);
        tuerca = articulo(ferreteria, "TUE-1", "Tuerca", "UNIDAD", "50.00", "78.65", "4", true);
        TenantContext.setCurrentFerreteriaId(ferreteria);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- registrar ----

    @Test
    @DisplayName("registrar suma el stock, pisa el costo, recalcula el precio y deja historial y movimientos COMPRA")
    void registrarAplicaTodo() {
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1234L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("110.00")),
                new RenglonCompra(tuerca, new BigDecimal("6"), new BigDecimal("50.00")));

        CompraRegistrada registrada = compraService.registrar(pedido, usuario);

        assertThat(registrada.repetida()).isFalse();
        Long compraId = registrada.compra().getId();
        assertThat(registrada.compra().getProveedorNombre()).isEqualTo("Bulonera Sur");
        assertThat(registrada.compra().getNeto()).isEqualByComparingTo("2500.00");
        assertThat(registrada.compra().getMoneda()).isEqualTo("ARS");
        assertThat(registrada.items()).hasSize(2);
        assertThat(registrada.items().get(0).getRenglon()).isEqualTo(1);
        assertThat(registrada.items().get(0).getCostoAnterior()).isEqualByComparingTo("100.00");
        assertThat(registrada.items().get(0).getPrecioAnterior()).isEqualByComparingTo("157.30");
        assertThat(registrada.items().get(0).getPrecioNuevo()).isEqualByComparingTo("173.03");
        assertThat(registrada.items().get(0).getSubtotal()).isEqualByComparingTo("2200.00");

        assertThat(fila("select stock_actual, costo, precio_final from articulo where id = ?", tornillo))
                .containsEntry("stock_actual", new BigDecimal("30.000"))
                .containsEntry("costo", new BigDecimal("110.00"))
                .containsEntry("precio_final", new BigDecimal("173.03"));
        assertThat(fila("select stock_actual, costo, precio_final from articulo where id = ?", tuerca))
                .containsEntry("stock_actual", new BigDecimal("10.000"))
                .containsEntry("costo", new BigDecimal("50.00"));
        assertThat(jdbc.queryForObject("select count(*) from historial_precio where origen = 'COMPRA' "
                + "and articulo_id in (?, ?)", Integer.class, tornillo, tuerca)).isEqualTo(1);
        assertThat(jdbc.queryForList("select tipo, cantidad from movimiento_stock where compra_id = ? order by articulo_id",
                compraId)).extracting(m -> m.get("tipo")).containsOnly("COMPRA").hasSize(2);
    }

    @Test
    @DisplayName("la misma clave con el mismo contenido devuelve la compra original sin volver a sumar")
    void mismaClaveMismoContenido() {
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1235L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("110.00")));

        CompraRegistrada primera = compraService.registrar(pedido, usuario);
        CompraRegistrada segunda = compraService.registrar(pedido, usuario);

        assertThat(segunda.repetida()).isTrue();
        assertThat(segunda.compra().getId()).isEqualTo(primera.compra().getId());
        assertThat(jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, tornillo))
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("la misma clave con otro contenido es 409 y no cambia nada")
    void mismaClaveOtroContenido() {
        String clave = UUID.randomUUID().toString();
        compraService.registrar(pedido(clave, 1236L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("110.00"))), usuario);

        assertThatThrownBy(() -> compraService.registrar(pedido(clave, 1236L,
                new RenglonCompra(tornillo, new BigDecimal("21"), new BigDecimal("110.00"))), usuario))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Esta compra ya se registró con otros datos.");
        assertThat(jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, tornillo))
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("el mismo comprobante del mismo proveedor con otra clave es 409 y no cambia nada")
    void comprobanteRepetido() {
        compraService.registrar(pedido(UUID.randomUUID().toString(), 1234L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("110.00"))), usuario);

        assertThatThrownBy(() -> compraService.registrar(pedido(UUID.randomUUID().toString(), 1234L,
                new RenglonCompra(tuerca, new BigDecimal("1"), new BigDecimal("60.00"))), usuario))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Esa compra ya está cargada: Factura A 00003-00001234 de Bulonera Sur.");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, tuerca))
                .isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("el mismo numero de otro proveedor o de otro tipo no choca")
    void mismoNumeroOtroProveedorOTipo() {
        Long otro = proveedor(ferreteria, "Pinturería Norte", true);
        compraService.registrar(pedido(UUID.randomUUID().toString(), 77L,
                new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("100.00"))), usuario);

        compraService.registrar(new RegistrarCompra(UUID.randomUUID().toString(), otro, TipoCompra.FA, 3, 77L, AYER,
                List.of(new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("100.00")))), usuario);
        compraService.registrar(new RegistrarCompra(UUID.randomUUID().toString(), proveedor, TipoCompra.REM, 3, 77L,
                AYER, List.of(new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("100.00")))), usuario);

        assertThat(jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class, ferreteria))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("un articulo en dolares toma el costo del renglon en USD, recalcula el precio en pesos y la compra queda en USD")
    void articuloEnDolares() {
        Long taladro = taladroEnDolares();

        CompraRegistrada registrada = compraService.registrar(pedido(UUID.randomUUID().toString(), 77L,
                new RenglonCompra(taladro, new BigDecimal("2"), new BigDecimal("12.00"))), usuario);

        assertThat(registrada.compra().getMoneda()).isEqualTo("USD");
        assertThat(registrada.compra().getNeto()).isEqualByComparingTo("24.00");
        assertThat(fila("select costo, precio_final from articulo where id = ?", taladro))
                .containsEntry("costo", new BigDecimal("12.00"))
                .containsEntry("precio_final", new BigDecimal("18876.00"));
    }

    @Test
    @DisplayName("una compra que mezcla pesos y dolares es 400 y no escribe nada")
    void mezclaDeMonedas() {
        Long taladro = taladroEnDolares();

        assertThatThrownBy(() -> compraService.registrar(pedido(UUID.randomUUID().toString(), 78L,
                new RenglonCompra(tornillo, new BigDecimal("2"), new BigDecimal("12.00")),
                new RenglonCompra(taladro, new BigDecimal("2"), new BigDecimal("12.00"))), usuario))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("renglones", "Una compra no puede mezclar artículos en pesos y en dólares."));
        assertThat(jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class, ferreteria))
                .isZero();
        assertThat(jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, tornillo))
                .isEqualByComparingTo("10");
    }

    /** Un taladro en USD (costo 10) con la cotizacion de la ferreteria en 1000: 12 x 1,3 x 1,21 x 1000 = 18.876. */
    private Long taladroEnDolares() {
        Long id = articulo(ferreteria, "USD-1", "Taladro", "UNIDAD", "10.00", "15730.00", "1", true);
        jdbc.update("update articulo set moneda = 'USD' where id = ?", id);
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id, vigente) values (?, 1000.0000)", ferreteria);
        return id;
    }

    // ---- vista previa ----

    @Test
    @DisplayName("la vista previa cuenta subas, bajas e iguales, calcula el neto y no escribe nada")
    void vistaPreviaNoEscribe() {
        Long clavo = articulo(ferreteria, "CLA-1", "Clavo", "KILO", "200.00", "314.60", "0", true);
        RegistrarCompra pedido = pedido(null, 99L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("210.00")),
                new RenglonCompra(tuerca, new BigDecimal("6"), new BigDecimal("40.00")),
                new RenglonCompra(clavo, new BigDecimal("2.5"), new BigDecimal("200.00")));

        PlanCompra plan = compraService.vistaPrevia(pedido);

        assertThat(plan.proveedorNombre()).isEqualTo("Bulonera Sur");
        assertThat(plan.moneda()).isEqualTo("ARS");
        assertThat(plan.suben()).isEqualTo(1);
        assertThat(plan.bajan()).isEqualTo(1);
        assertThat(plan.iguales()).isEqualTo(1);
        assertThat(plan.neto()).isEqualByComparingTo("4940.00");
        assertThat(plan.renglones().get(0).costo().advertencia())
                .isEqualTo("El costo sube un 110 % (de 100,00 a 210,00). Revisá que sea correcto.");
        assertThat(plan.renglones().get(2).subtotal()).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, tornillo))
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class, ferreteria))
                .isZero();
    }

    @Test
    @DisplayName("la vista previa ya avisa que el comprobante esta cargado")
    void vistaPreviaAvisaComprobanteRepetido() {
        compraService.registrar(pedido(UUID.randomUUID().toString(), 1234L,
                new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("100.00"))), usuario);

        assertThatThrownBy(() -> compraService.vistaPrevia(pedido(null, 1234L,
                new RenglonCompra(tuerca, new BigDecimal("1"), new BigDecimal("60.00")))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Esa compra ya está cargada: Factura A 00003-00001234 de Bulonera Sur.");
    }

    // ---- precio manual y ajustes sobre el precio final (CU-14) ----

    @Test
    @DisplayName("con precio manual el precio queda el escrito y con ajustes se suman; la vista previa da lo mismo")
    void precioManualYAjustes() {
        jdbc.update("update articulo set precio_manual = true, precio_final = 500.00 where id = ?", tornillo);
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":\"Flete\",\"tipo\":\"PORCENTAJE\",\"valor\":10}]", tuerca);
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1240L,
                new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("110.00")),
                new RenglonCompra(tuerca, new BigDecimal("1"), new BigDecimal("60.00")));

        PlanCompra plan = compraService.vistaPrevia(pedido);
        // 60 x 1,30 x 1,21 = 94,38; +10 % = 103,818 hacia arriba al centavo.
        assertThat(plan.renglones().get(0).costo().precioNuevo()).isEqualByComparingTo("500.00");
        assertThat(plan.renglones().get(1).costo().precioNuevo()).isEqualByComparingTo("103.82");

        compraService.registrar(pedido, usuario);

        assertThat(fila("select costo, precio_final, precio_manual from articulo where id = ?", tornillo))
                .containsEntry("costo", new BigDecimal("110.00"))
                .containsEntry("precio_final", new BigDecimal("500.00"))
                .containsEntry("precio_manual", true);
        assertThat(jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, tuerca))
                .isEqualByComparingTo("103.82");
    }

    @Test
    @DisplayName("un ajuste que dejaria el precio en 0 o menos: la vista previa avisa, el registro conserva el precio, "
            + "pisa el costo, suma el stock y el resto de la compra sigue (S7)")
    void ajusteQueDejaElPrecioEnCeroSeSaltea() {
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":-150}]", tornillo);
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1241L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("10.00")),
                new RenglonCompra(tuerca, new BigDecimal("6"), new BigDecimal("60.00")));
        String aviso = "No se cambió el precio de TOR-1 (Tornillo 1/4): con sus ajustes quedaría en 0 o menos. "
                + "Revisá sus ajustes.";

        PlanCompra plan = compraService.vistaPrevia(pedido);

        assertThat(plan.renglones().get(0).costo().precioNuevo()).isEqualByComparingTo("157.30");
        assertThat(plan.renglones().get(0).costo().advertencia()).endsWith(aviso);
        assertThat(plan.renglones().get(1).costo().advertencia()).isNull();

        compraService.registrar(pedido, usuario);

        assertThat(fila("select stock_actual, costo, precio_final from articulo where id = ?", tornillo))
                .containsEntry("stock_actual", new BigDecimal("30.000"))
                .containsEntry("costo", new BigDecimal("10.00"))
                .containsEntry("precio_final", new BigDecimal("157.30"));
        assertThat(jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, tuerca))
                .isEqualByComparingTo("94.38");
        assertThat(fila("select costo_anterior, costo_nuevo, precio_anterior, precio_nuevo from historial_precio "
                + "where articulo_id = ?", tornillo))
                .containsEntry("costo_anterior", new BigDecimal("100.00"))
                .containsEntry("costo_nuevo", new BigDecimal("10.00"))
                .containsEntry("precio_anterior", new BigDecimal("157.30"))
                .containsEntry("precio_nuevo", new BigDecimal("157.30"));
    }

    // ---- rechazos ----

    @Test
    @DisplayName("un proveedor de otra ferreteria o inexistente es 404")
    void proveedorAjeno() {
        Long ajeno = proveedor(ferreteria(), "Ajeno", true);

        assertThatThrownBy(() -> compraService.vistaPrevia(new RegistrarCompra(null, ajeno, TipoCompra.FA, 3, 1L, AYER,
                List.of(new RenglonCompra(tornillo, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");
    }

    @Test
    @DisplayName("un proveedor dado de baja es 400 en proveedorId")
    void proveedorInactivo() {
        Long inactivo = proveedor(ferreteria, "Cerrado", false);

        assertThatThrownBy(() -> compraService.vistaPrevia(new RegistrarCompra(null, inactivo, TipoCompra.FA, 3, 1L,
                AYER, List.of(new RenglonCompra(tornillo, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("proveedorId", "El proveedor está dado de baja."));
    }

    @Test
    @DisplayName("un articulo de otra ferreteria o inexistente es 404 y no registra nada")
    void articuloAjeno() {
        Long otra = ferreteria();
        Long ajeno = articulo(otra, "TOR-1", "Tornillo ajeno", "UNIDAD", "100.00", "157.30", "0", true);

        assertThatThrownBy(() -> compraService.registrar(pedido(UUID.randomUUID().toString(), 5L,
                new RenglonCompra(tornillo, BigDecimal.ONE, BigDecimal.TEN),
                new RenglonCompra(ajeno, BigDecimal.ONE, BigDecimal.TEN)), usuario))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artículo no encontrado");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, tornillo))
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, ajeno))
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("un articulo dado de baja es 400 en su renglon")
    void articuloInactivo() {
        Long viejo = articulo(ferreteria, "OLD-1", "Martillo viejo", "UNIDAD", "100.00", "157.30", "0", false);

        assertThatThrownBy(() -> compraService.vistaPrevia(pedido(null, 5L,
                new RenglonCompra(tornillo, BigDecimal.ONE, BigDecimal.TEN),
                new RenglonCompra(viejo, BigDecimal.ONE, BigDecimal.TEN))))
                .isInstanceOf(DatosInvalidosException.class)
                .extracting(e -> ((DatosInvalidosException) e).errors())
                .isEqualTo(Map.of("renglones[1].articuloId", "Martillo viejo está dado de baja."));
    }

    // ---- helpers ----

    private RegistrarCompra pedido(String clave, Long numero, RenglonCompra... renglones) {
        return new RegistrarCompra(clave, proveedor, TipoCompra.FA, 3, numero, AYER, List.of(renglones));
    }

    private Map<String, Object> fila(String sql, Long id) {
        return jdbc.queryForMap(sql, id);
    }

    private Long ferreteria() {
        String cuit = String.format("30-%08d-%d", 78900000 + SECUENCIA.incrementAndGet(), 3);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Compras " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long usuario(Long ferreteriaId) {
        String email = "dueno" + SECUENCIA.incrementAndGet() + "@compras.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno Compras', 'DUENO')", ferreteriaId, email);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private Long proveedor(Long ferreteriaId, String nombre, boolean activo) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, ?, ?)", ferreteriaId, nombre, activo);
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?",
                Long.class, ferreteriaId, nombre);
    }

    private Long articulo(Long ferreteriaId, String codigo, String descripcion, String unidad, String costo,
                          String precio, String stock, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                        + "alicuota_iva, stock_actual, activo) values (?, ?, ?, ?, ?, ?, 'IVA_21', ?, ?)",
                ferreteriaId, codigo, descripcion, costo, precio, unidad, stock, activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }

    @Test
    @DisplayName("un ajuste que pasaria el tope: la vista previa avisa, el registro pisa el costo y conserva el precio (S9)")
    void ajustesQuePasanElTopeSeSaltean() {
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":9999999999842.69}]", tornillo);
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1291L,
                new RenglonCompra(tornillo, new BigDecimal("20"), new BigDecimal("200.00")),
                new RenglonCompra(tuerca, new BigDecimal("6"), new BigDecimal("60.00")));

        PlanCompra plan = compraService.vistaPrevia(pedido);
        assertThat(plan.renglones().get(0).costo().precioNuevo()).isEqualByComparingTo("157.30");
        assertThat(plan.renglones().get(0).costo().advertencia()).endsWith(
                "No se cambió el precio de TOR-1 (Tornillo 1/4): con sus ajustes quedaría demasiado grande. Revisá sus ajustes.");

        compraService.registrar(pedido, usuario);
        assertThat(fila("select stock_actual, costo, precio_final from articulo where id = ?", tornillo))
                .containsEntry("stock_actual", new BigDecimal("30.000"))
                .containsEntry("costo", new BigDecimal("200.00"))
                .containsEntry("precio_final", new BigDecimal("157.30"));
        assertThat(jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, tuerca))
                .isEqualByComparingTo("94.38");
        assertThat(fila("select costo_anterior, costo_nuevo, precio_anterior, precio_nuevo from historial_precio "
                + "where articulo_id = ?", tornillo))
                .containsEntry("costo_anterior", new BigDecimal("100.00"))
                .containsEntry("costo_nuevo", new BigDecimal("200.00"))
                .containsEntry("precio_anterior", new BigDecimal("157.30"))
                .containsEntry("precio_nuevo", new BigDecimal("157.30"));
    }

    @Test
    @DisplayName("si el precio calculado ya pasa el tope, la vista previa y el registro dan 400 y el costo no cambia")
    void calculadoQuePasaElTopeRechaza() {
        RegistrarCompra pedido = pedido(UUID.randomUUID().toString(), 1292L,
                new RenglonCompra(tornillo, new BigDecimal("1"), new BigDecimal("7000000000000.00")));

        assertThatThrownBy(() -> compraService.vistaPrevia(pedido)).isInstanceOf(DatosInvalidosException.class);
        assertThatThrownBy(() -> compraService.registrar(pedido, usuario)).isInstanceOf(DatosInvalidosException.class);
        assertThat(fila("select costo from articulo where id = ?", tornillo))
                .containsEntry("costo", new BigDecimal("100.00"));
    }
}
