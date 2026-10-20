package com.ferreteria.clientes;

import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.InvarianteCuenta;
import com.ferreteria.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Datos de apoyo de los tests de integración de la cuenta corriente (corte 2): ferretería con su Dueño, clientes con y sin
 * límite, y ventas sueltas (comprobante + pago a cuenta corriente) para colgar los movimientos del libro. CUIT 30-8302NNNN-1.
 */
public abstract class CuentaCorrienteTestBase extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /** Números de recibo por ferretería para los tests que emiten recibos en la transacción del cobro (únicos, no correlativos). */
    private static final ConcurrentHashMap<Long, AtomicLong> NUMEROS_DE_RECIBO = new ConcurrentHashMap<>();

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected ClientesParaVenta clientesParaVenta;

    @Autowired
    protected CuentaCorrienteParaCobranza cobranza;

    @PersistenceContext
    protected EntityManager em;

    /** Una ferretería de prueba con su usuario Dueño. */
    protected record Ferreteria(long id, long usuarioId) {}

    /** El comprobante y el pago a cuenta corriente de una venta suelta. */
    protected record VentaSuelta(long comprobanteId, long pagoId, long numero) {}

    @AfterEach
    void limpiarTenant() {
        TenantContext.clear();
    }

    /** El bloque de CUIT de la clase (30-84NN de este change: NN por clase); las de tramo 2 usan el de siempre. */
    protected String prefijoDeCuit() {
        return "30-8302";
    }

    protected Ferreteria ferreteria() {
        int n = SECUENCIA.incrementAndGet();
        String cuit = (prefijoDeCuit() + "%04d-1").formatted(n);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Cuenta corriente " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "cc-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno CC', 'DUENO')", id, email);
        long usuario = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                + "values (?, 'Cuenta corriente', 'CUENTA_CORRIENTE', 0, 1, 5)", id);
        return new Ferreteria(id, usuario);
    }

    /** Los emails de un Dueño y un Vendedor de la ferretería, con la contraseña demo, para los tests HTTP. */
    protected record Logins(String dueno, String vendedor) {}

    /**
     * Deja a la ferretería lista para el login: el Dueño que armó {@link #ferreteria()} recibe la contraseña demo y se le suma
     * un Vendedor. Se llama una vez por ferretería.
     */
    protected Logins logins(Ferreteria f) {
        String hash = jdbc.queryForObject("select password_hash from usuario where email = 'vendedor@tornillo.demo'",
                String.class);
        jdbc.update("update usuario set password_hash = ? where id = ?", hash, f.usuarioId());
        String dueno = jdbc.queryForObject("select email from usuario where id = ?", String.class, f.usuarioId());
        String vendedor = "vendedor-" + dueno;
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor CC', 'VENDEDOR')", f.id(), vendedor, hash);
        return new Logins(dueno, vendedor);
    }

    /** Un cliente de la ferretería con ese límite (null = sin cuenta corriente) y saldo 0. */
    protected long cliente(Ferreteria f, String limite) {
        String razon = "Cliente " + SECUENCIA.incrementAndGet();
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, ?)", f.id(),
                razon, limite == null ? null : new BigDecimal(limite));
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class, f.id(),
                razon);
    }

    /**
     * Desde dónde numeran los comprobantes sembrados por los helpers. La numeración real de una ferretería (el numerador de
     * comprobantes) arranca en 1, y {@code uk_comprobante_numero (ferreteria_id, numero)} es único: con números chicos
     * (la secuencia del test) una venta REAL de la misma ferretería, hecha por un servicio, chocaba con una sembrada según el
     * orden en que corrieran los tests (S8). Un rango propio, muy lejos de lo que numerará el servicio, lo evita.
     */
    protected static final long NUMERO_BASE_SEMBRADO = 100_000_000L;

    /** Un comprobante y un pago a cuenta corriente de ese monto (sin tocar el libro ni el saldo). */
    protected VentaSuelta ventaSuelta(Ferreteria f, long clienteId, String monto) {
        int n = SECUENCIA.incrementAndGet();
        long numero = NUMERO_BASE_SEMBRADO + n;
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, cliente_id, usuario_id, "
                + "neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, ?, 0, 0, ?, ?)", f.id(), numero,
                "00000000-0000-4000-8000-%012d".formatted(n), clienteId, f.usuarioId(), new BigDecimal(monto),
                new BigDecimal(monto));
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?",
                Long.class, f.id(), numero);
        long forma = jdbc.queryForObject(
                "select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class, f.id());
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'CUENTA_CORRIENTE', ?, ?, 'Cuenta corriente')", f.id(), comprobante, new BigDecimal(monto),
                forma);
        long pago = jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
        return new VentaSuelta(comprobante, pago, numero);
    }

    /**
     * Una venta a cuenta corriente YA asentada en el libro y en el saldo (como la dejaría {@code asentarVenta}): un
     * comprobante con un pago a cuenta corriente por cada monto, una fila {@code VENTA} del libro por pago con la referencia
     * «Venta N.º n», y el saldo del cliente subido por el total. Varios montos = una venta con varios pagos CC.
     */
    protected VentaSuelta ventaEnLibro(Ferreteria f, long clienteId, String... montos) {
        VentaSuelta venta = ventaSuelta(f, clienteId, montos[0]);
        List<Long> pagos = new ArrayList<>(List.of(venta.pagoId()));
        long forma = jdbc.queryForObject(
                "select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class, f.id());
        for (int i = 1; i < montos.length; i++) {
            jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                    + "values (?, ?, 'CUENTA_CORRIENTE', ?, ?, 'Cuenta corriente')", f.id(), venta.comprobanteId(),
                    new BigDecimal(montos[i]), forma);
            pagos.add(jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class,
                    venta.comprobanteId()));
        }
        for (int i = 0; i < montos.length; i++) {
            jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                    + "pago_id, fecha, usuario_id, referencia) values (?, ?, 'VENTA', ?, 0, ?, ?, now(6), ?, ?)", f.id(),
                    clienteId, new BigDecimal(montos[i]), venta.comprobanteId(), pagos.get(i), f.usuarioId(),
                    "Venta N.º " + venta.numero());
            jdbc.update("update cliente set saldo = saldo + ? where id = ?", new BigDecimal(montos[i]), clienteId);
        }
        return venta;
    }

    /** Un recibo EFECTIVO de ese total (con una caja ya cerrada) para colgarle imputaciones y un COBRO del libro. */
    protected long reciboSuelto(Ferreteria f, long clienteId, String total) {
        jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, efectivo_inicial) "
                + "values (?, null, now(6), ?, 0)", f.id(), f.usuarioId());
        long caja = jdbc.queryForObject("select max(id) from caja where ferreteria_id = ?", Long.class, f.id());
        long numero = jdbc.queryForObject("select coalesce(max(numero), 0) + 1 from recibo where ferreteria_id = ?",
                Long.class, f.id());
        jdbc.update("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, caja_id, "
                + "usuario_id, fecha, medio, total) values (?, ?, ?, ?, 'Cliente', ?, ?, now(6), 'EFECTIVO', ?)", f.id(),
                numero, java.util.UUID.randomUUID().toString(), clienteId, caja, f.usuarioId(), new BigDecimal(total));
        return jdbc.queryForObject("select id from recibo where ferreteria_id = ? and numero = ?", Long.class, f.id(),
                numero);
    }

    /** Un COBRO ya asentado: recibo de ese total, fila {@code COBRO} del libro y el saldo bajado. Devuelve el id del recibo. */
    protected long cobroEnLibro(Ferreteria f, long clienteId, String total) {
        long recibo = reciboSuelto(f, clienteId, total);
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                + "recibo_id, referencia) values (?, ?, 'COBRO', 0, ?, now(6), ?, ?, 'Recibo N.º test')", f.id(), clienteId,
                new BigDecimal(total), f.usuarioId(), recibo);
        jdbc.update("update cliente set saldo = saldo - ? where id = ?", new BigDecimal(total), clienteId);
        return recibo;
    }

    /** Una fila de {@code imputacion} (el recibo ya existe; no toca el libro ni el saldo). */
    protected void imputar(Ferreteria f, long clienteId, long reciboId, long comprobanteId, String importe) {
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, ?)", f.id(), clienteId, reciboId, comprobanteId, new BigDecimal(importe));
    }

    protected BigDecimal saldo(long clienteId) {
        return jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
    }

    protected long movimientos(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class,
                clienteId);
    }

    // ---- cobranza a nivel puerto: como la hará RegistroCobranza (tomar al cliente → recibo → asentarCobro, en UNA transacción) ----

    /** Un recibo emitido dentro de la transacción del cobro. */
    protected record ReciboEmitido(long id, long numero) {}

    /** Una caja ya cerrada de la ferretería, para que cuelguen los recibos (la FK de recibo.caja_id). */
    protected long cajaCerrada(Ferreteria f) {
        jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, efectivo_inicial) "
                + "values (?, null, now(6), ?, 0)", f.id(), f.usuarioId());
        return jdbc.queryForObject("select max(id) from caja where ferreteria_id = ?", Long.class, f.id());
    }

    /**
     * Inserta el recibo en la transacción abierta (SQL nativo por el mismo EntityManager: si el cobro se rechaza, el recibo se
     * revierte con todo lo demás, como en producción). Requiere {@code TenantContext}.
     */
    protected ReciboEmitido emitirRecibo(Ferreteria f, long clienteId, long cajaId, String total) {
        long numero = NUMEROS_DE_RECIBO.computeIfAbsent(f.id(), k -> new AtomicLong()).incrementAndGet();
        em.createNativeQuery("insert into recibo (ferreteria_id, numero, clave_idempotencia, cliente_id, cliente_nombre, "
                + "caja_id, usuario_id, fecha, medio, total) values (?1, ?2, ?3, ?4, 'Cliente', ?5, ?6, now(6), 'EFECTIVO', ?7)")
                .setParameter(1, f.id()).setParameter(2, numero).setParameter(3, UUID.randomUUID().toString())
                .setParameter(4, clienteId).setParameter(5, cajaId).setParameter(6, f.usuarioId())
                .setParameter(7, new BigDecimal(total)).executeUpdate();
        Number id = (Number) em.createNativeQuery("select id from recibo where ferreteria_id = ?1 and numero = ?2")
                .setParameter(1, f.id()).setParameter(2, numero).getSingleResult();
        return new ReciboEmitido(id.longValue(), numero);
    }

    /** Lo que hace RegistroCobranza en la tx: toma al cliente (lock), emite el recibo y asienta el cobro. */
    protected List<ImputacionAsentada> asentarEnLaTx(Ferreteria f, long clienteId, long cajaId, String total, Imputar... imputaciones) {
        clientesParaVenta.tomarParaVenta(clienteId).orElseThrow();
        ReciboEmitido recibo = emitirRecibo(f, clienteId, cajaId, total);
        return cobranza.asentarCobro(new AsientoCobro(clienteId, recibo.id(), recibo.numero(), new BigDecimal(total),
                List.of(imputaciones), f.usuarioId()));
    }

    /** Un cobro completo en su propia transacción (se revierte entero si el puerto rechaza). Requiere {@code TenantContext}. */
    protected List<ImputacionAsentada> cobrar(Ferreteria f, long clienteId, long cajaId, String total, Imputar... imputaciones) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> asentarEnLaTx(f, clienteId, cajaId, total, imputaciones));
    }

    protected static Imputar imputa(long comprobanteId, String importe) {
        return new Imputar(comprobanteId, new BigDecimal(importe));
    }

    protected long recibos(long clienteId) {
        return jdbc.queryForObject("select count(*) from recibo where cliente_id = ?", Long.class, clienteId);
    }

    protected long cobros(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'COBRO'",
                Long.class, clienteId);
    }

    protected long imputaciones(long clienteId) {
        return jdbc.queryForObject("select count(*) from imputacion where cliente_id = ?", Long.class, clienteId);
    }

    protected BigDecimal imputadoA(long comprobanteId) {
        return jdbc.queryForObject("select coalesce(sum(importe), 0) from imputacion where comprobante_id = ?",
                BigDecimal.class, comprobanteId);
    }

    protected void assertInvariante(long clienteId) {
        InvarianteCuenta.assertInvariante(jdbc, clienteId);
    }
}
