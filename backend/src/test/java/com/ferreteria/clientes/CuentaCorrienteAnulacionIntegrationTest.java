package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AnulacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoAnulacion;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto de la anulación contra MySQL real (CCT3B1-207: CC-062, CC-063, CC-064, CC-065, CC-067, CC-068, CC-061, CC-001,
 * CC-002, CC-020, CC-021). Se invoca como lo hará {@code RegistroAnulacion}: en UNA transacción que toma al cliente, marca el
 * recibo ANULADO (por SQL, como lo hace ventas) y asienta la anulación. Cada escenario termina con {@code assertInvariante}
 * (I1, I2a–e, I-inactivo). CUIT 30-8501NNNN.
 */
class CuentaCorrienteAnulacionIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Autowired
    CuentaCorrienteService servicio;

    @Autowired
    ClienteService clienteService;

    @Override
    protected String prefijoDeCuit() {
        return "30-8501";
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private PendientesCuenta pendientes(long cliente) {
        return servicio.pendientes(cliente);
    }

    /** Un cobro completo en su propia transacción, devolviendo el recibo (id y número). Requiere {@code TenantContext}. */
    private ReciboEmitido cobrarConRecibo(Ferreteria f, long cliente, long caja, String total, Imputar... imputaciones) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            ReciboEmitido recibo = emitirRecibo(f, cliente, caja, total);
            cobranza.asentarCobro(new AsientoCobro(cliente, recibo.id(), recibo.numero(), new BigDecimal(total),
                    List.of(imputaciones), f.usuarioId()));
            return recibo;
        });
    }

    /** Lo que hace RegistroAnulacion en la tx: toma al cliente, marca el recibo ANULADO (SQL) y asienta. */
    private AnulacionAsentada anular(Ferreteria f, long cliente, ReciboEmitido recibo, String total, long cajaAnulacion) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            em.createNativeQuery("update recibo set estado = 'ANULADO', fecha_anulacion = now(6), usuario_anulacion_id = ?1, "
                    + "caja_anulacion_id = ?2, motivo_anulacion = ?3 where id = ?4")
                    .setParameter(1, f.usuarioId()).setParameter(2, cajaAnulacion).setParameter(3, MOTIVO)
                    .setParameter(4, recibo.id()).executeUpdate();
            return cobranza.asentarAnulacion(
                    new AsientoAnulacion(cliente, recibo.id(), recibo.numero(), new BigDecimal(total), MOTIVO, f.usuarioId()));
        });
    }

    private long vigentes(long reciboId) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 1", Long.class, reciboId);
    }

    private long noVigentes(long reciboId) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 0", Long.class, reciboId);
    }

    private long anulaciones(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, clienteId);
    }

    private String estado(long reciboId) {
        return jdbc.queryForObject("select estado from recibo where id = ?", String.class, reciboId);
    }

    private static BigDecimal pendienteDe(PendientesCuenta p, long comprobante) {
        return p.comprobantes().stream().filter(c -> c.comprobanteId() == comprobante).findFirst().orElseThrow().pendiente();
    }

    // ---- los seis escenarios de CC-064 ----

    @Test
    @DisplayName("CC-064 (1) cobro imputado a varios comprobantes (A 600 / B 400, saldo 0): anular → saldo 1.000, 2 imputaciones no vigentes, UNA ANULACION y A y B vuelven a pendientes")
    void cobroImputadoAVariosComprobantes() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "600.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "400.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "1000.00", imputa(a.comprobanteId(), "600.00"),
                imputa(b.comprobanteId(), "400.00"));
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(pendientes(cliente).comprobantes()).isEmpty();
        assertInvariante(cliente);

        AnulacionAsentada asentada = anular(f, cliente, recibo, "1000.00", caja);

        assertThat(asentada.comprobantesLiberados()).containsExactly(a.comprobanteId(), b.comprobanteId());
        assertThat(asentada.movimientoId()).isNotNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("1000.00");
        assertThat(noVigentes(recibo.id())).isEqualTo(2);
        assertThat(vigentes(recibo.id())).isZero();
        assertThat(imputaciones(cliente)).as("las imputaciones no se borran").isEqualTo(2);
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select debe, haber, referencia, motivo, recibo_id from movimiento_cuenta_cliente "
                + "where cliente_id = ? and tipo = 'ANULACION'", cliente)).satisfies(fila -> {
                    assertThat((BigDecimal) fila.get("debe")).isEqualByComparingTo("1000.00");
                    assertThat((BigDecimal) fila.get("haber")).isEqualByComparingTo("0");
                    assertThat(fila.get("referencia")).isEqualTo("Anulación Recibo N.º " + recibo.numero());
                    assertThat(fila.get("motivo")).isEqualTo(MOTIVO);
                    assertThat(((Number) fila.get("recibo_id")).longValue()).isEqualTo(recibo.id());
                });
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).extracting(PendientesCuenta.Pendiente::comprobanteId)
                .containsExactly(a.comprobanteId(), b.comprobanteId());
        assertThat(pendienteDe(p, a.comprobanteId())).isEqualByComparingTo("600.00");
        assertThat(pendienteDe(p, b.comprobanteId())).isEqualByComparingTo("400.00");
        assertThat(p.saldo()).isEqualByComparingTo("1000.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-064 (2) parcial: A 1.000, cobro 700 imputando 400 → antes saldo 300, A pendiente 600, aCuenta 300; después saldo 1.000, A pendiente 1.000, aCuenta 0")
    void cobroParcial() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "700.00", imputa(a.comprobanteId(), "400.00"));
        PendientesCuenta antes = pendientes(cliente);
        assertThat(antes.saldo()).isEqualByComparingTo("300.00");
        assertThat(pendienteDe(antes, a.comprobanteId())).isEqualByComparingTo("600.00");
        assertThat(antes.aCuenta()).isEqualByComparingTo("300.00");

        anular(f, cliente, recibo, "700.00", caja);

        PendientesCuenta despues = pendientes(cliente);
        assertThat(despues.saldo()).isEqualByComparingTo("1000.00");
        assertThat(pendienteDe(despues, a.comprobanteId())).isEqualByComparingTo("1000.00");
        assertThat(despues.aCuenta()).as("el crédito sin imputar desaparece con el cobro anulado").isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-064 (3) adelanto puro (saldo −500): anular → saldo 0, aCuenta 0, sin pendientes y SIN imputaciones que tocar")
    void adelantoPuro() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "500.00");
        assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
        assertThat(pendientes(cliente).aCuenta()).isEqualByComparingTo("500.00");

        AnulacionAsentada asentada = anular(f, cliente, recibo, "500.00", caja);

        assertThat(asentada.comprobantesLiberados()).isEmpty();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.saldo()).isEqualByComparingTo("0");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-064 (4) adelanto consumido por una venta posterior (saldo −200): anular → saldo 300, pendiente 300, aCuenta 0")
    void adelantoConsumidoPorVentaPosterior() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido adelanto = cobrarConRecibo(f, cliente, caja, "500.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "300.00"); // saldo −500 + 300 = −200
        assertThat(saldo(cliente)).isEqualByComparingTo("-200.00");
        assertInvariante(cliente);

        anular(f, cliente, adelanto, "500.00", caja);

        assertThat(saldo(cliente)).isEqualByComparingTo("300.00");
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).singleElement().satisfies(c -> {
            assertThat(c.comprobanteId()).isEqualTo(venta.comprobanteId());
            assertThat(c.pendiente()).isEqualByComparingTo("300.00");
        });
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-064 (5) anulación tras ventas posteriores: FIFO [A, B] con A 500 liberada y B 200; saldo 700")
    void anulacionTrasVentasPosteriores() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "500.00", imputa(a.comprobanteId(), "500.00"));
        VentaSuelta b = ventaEnLibro(f, cliente, "200.00"); // posterior al cobro
        assertThat(pendientes(cliente).comprobantes()).extracting(PendientesCuenta.Pendiente::comprobanteId)
                .containsExactly(b.comprobanteId());

        anular(f, cliente, recibo, "500.00", caja);

        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).extracting(PendientesCuenta.Pendiente::comprobanteId)
                .as("FIFO por el débito más viejo: A (liberado) antes que B").containsExactly(a.comprobanteId(), b.comprobanteId());
        assertThat(pendienteDe(p, a.comprobanteId())).isEqualByComparingTo("500.00");
        assertThat(pendienteDe(p, b.comprobanteId())).isEqualByComparingTo("200.00");
        assertThat(p.saldo()).isEqualByComparingTo("700.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-064 (6) mixto: R1 imputa A y B, R2 imputa A; anular R1 → R2 sigue vigente y A queda con lo de R2")
    void mixto() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "300.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido r1 = cobrarConRecibo(f, cliente, caja, "250.00", imputa(a.comprobanteId(), "200.00"),
                imputa(b.comprobanteId(), "50.00"));
        ReciboEmitido r2 = cobrarConRecibo(f, cliente, caja, "150.00", imputa(a.comprobanteId(), "100.00"),
                imputa(b.comprobanteId(), "50.00"));
        assertInvariante(cliente);

        anular(f, cliente, r1, "250.00", caja);

        assertThat(vigentes(r1.id())).isZero();
        assertThat(vigentes(r2.id())).as("R2 no se toca").isEqualTo(2);
        assertThat(estado(r2.id())).isEqualTo("EMITIDO");
        PendientesCuenta p = pendientes(cliente);
        assertThat(pendienteDe(p, a.comprobanteId())).as("300 − 100 de R2").isEqualByComparingTo("200.00");
        assertThat(pendienteDe(p, b.comprobanteId())).as("100 − 50 de R2").isEqualByComparingTo("50.00");
        assertThat(p.saldo()).isEqualByComparingTo("250.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ---- CC-065: sin límite ----

    @Test
    @DisplayName("CC-065: anular puede dejar al cliente por encima del límite (y con límite NULL o 0): no se rechaza")
    void anulacionSinLimite() {
        Ferreteria f = ferreteria();
        long conLimite = cliente(f, "1000.00");
        long sinLimite = cliente(f, null);
        long limiteCero = cliente(f, "0.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        for (long cliente : List.of(conLimite, sinLimite, limiteCero)) {
            VentaSuelta vieja = ventaEnLibro(f, cliente, "900.00");
            ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "900.00", imputa(vieja.comprobanteId(), "900.00"));
            ventaEnLibro(f, cliente, "800.00"); // saldo 800: con la anulación pasa a 1.700 > límite 1.000

            anular(f, cliente, recibo, "900.00", caja);

            assertThat(saldo(cliente)).isEqualByComparingTo("1700.00");
            assertInvariante(cliente);
        }
    }

    // ---- la vigencia vista por la cobranza ----

    @Test
    @DisplayName("una cobranza posterior imputa el comprobante liberado (antes de anular, el mismo importe se rechaza por pendiente 0)")
    void cobranzaPosteriorImputaElComprobanteLiberado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido r1 = cobrarConRecibo(f, cliente, caja, "1000.00", imputa(a.comprobanteId(), "1000.00"));
        assertThatThrownBy(() -> cobrar(f, cliente, caja, "600.00", imputa(a.comprobanteId(), "600.00")))
                .as("antes de anular A está saldado").isInstanceOf(ImputacionInvalidaException.class);

        anular(f, cliente, r1, "1000.00", caja);
        cobrar(f, cliente, caja, "600.00", imputa(a.comprobanteId(), "600.00"));

        assertThat(imputadoA(a.comprobanteId())).as("1.000 no vigentes + 600 vigentes en total").isEqualByComparingTo("1600.00");
        PendientesCuenta p = pendientes(cliente);
        assertThat(pendienteDe(p, a.comprobanteId())).isEqualByComparingTo("400.00");
        assertThat(p.saldo()).isEqualByComparingTo("400.00");
        assertInvariante(cliente);
    }

    // ---- MANDATORY y defensas (CC-068) ----

    @Test
    @DisplayName("asentarAnulacion sin transacción → IllegalTransactionStateException (MANDATORY) y no escribe nada")
    void sinTransaccion() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = cobroEnLibro(f, cliente, "100.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> cobranza.asentarAnulacion(new AsientoAnulacion(cliente, recibo, 1L, d("100.00"), MOTIVO, f.usuarioId())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(anulaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("-100.00");
        assertInvariante(cliente);
    }

    private void assertNadaCambio(long cliente, BigDecimal saldoEsperado, long reciboId, String estadoEsperado,
            long vigentesEsperadas, long anulacionesEsperadas) {
        assertThat(saldo(cliente)).isEqualByComparingTo(saldoEsperado);
        assertThat(estado(reciboId)).isEqualTo(estadoEsperado);
        assertThat(vigentes(reciboId)).isEqualTo(vigentesEsperadas);
        assertThat(anulaciones(cliente)).isEqualTo(anulacionesEsperadas);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-068: un recibo de OTRO cliente → IllegalStateException y la transacción se revierte entera (recibo EMITIDO, imputaciones vigentes, saldo intacto)")
    void reciboDeOtroCliente() {
        Ferreteria f = ferreteria();
        long duenio = cliente(f, "5000.00");
        long otro = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, duenio, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, duenio, caja, "100.00", imputa(a.comprobanteId(), "100.00"));

        assertThatThrownBy(() -> anular(f, otro, recibo, "100.00", caja)).isInstanceOf(IllegalStateException.class);

        assertNadaCambio(duenio, d("0"), recibo.id(), "EMITIDO", 1, 0);
        assertThat(saldo(otro)).isEqualByComparingTo("0");
        assertInvariante(otro);
    }

    @Test
    @DisplayName("CC-068: un recibo sin COBRO en el libro → IllegalStateException sin escribir")
    void reciboSinCobro() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long recibo = reciboSuelto(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> anular(f, cliente, new ReciboEmitido(recibo, 1L), "100.00", caja))
                .isInstanceOf(IllegalStateException.class);

        assertThat(estado(recibo)).isEqualTo("EMITIDO");
        assertThat(anulaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CC-068: el COBRO tiene un haber distinto del total pedido → IllegalStateException sin escribir")
    void cobroConOtroImporte() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "500.00");

        assertThatThrownBy(() -> anular(f, cliente, recibo, "499.99", caja)).isInstanceOf(IllegalStateException.class);

        assertNadaCambio(cliente, d("-500.00"), recibo.id(), "EMITIDO", 0, 0);
    }

    @Test
    @DisplayName("CC-068/CC-061: la segunda anulación del mismo recibo → IllegalStateException; sigue una sola ANULACION y el saldo no cambia")
    void segundaAnulacion() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00"));
        anular(f, cliente, recibo, "100.00", caja);

        assertThatThrownBy(() -> anular(f, cliente, recibo, "100.00", caja)).isInstanceOf(IllegalStateException.class);

        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("100.00");
        assertThat(noVigentes(recibo.id())).isEqualTo(1);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un cliente de otra ferretería → 404 y no escribe nada")
    void clienteDeOtraFerreteria() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long clienteDeB = cliente(b, "5000.00");
        long reciboDeB = cobroEnLibro(b, clienteDeB, "100.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> cobranza
                .asentarAnulacion(new AsientoAnulacion(clienteDeB, reciboDeB, 1L, d("100.00"), MOTIVO, a.usuarioId()))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(anulaciones(clienteDeB)).isZero();
        assertThat(saldo(clienteDeB)).isEqualByComparingTo("-100.00");
        assertInvariante(clienteDeB);
    }

    @Test
    @DisplayName("un recibo de otra ferretería del cliente propio → IllegalStateException (no hay COBRO de ese cliente en SU libro) sin escribir")
    void reciboDeOtraFerreteria() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long clienteDeA = cliente(a, null);
        long clienteDeB = cliente(b, null);
        long reciboDeB = cobroEnLibro(b, clienteDeB, "100.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(clienteDeA).orElseThrow();
            return cobranza.asentarAnulacion(new AsientoAnulacion(clienteDeA, reciboDeB, 1L, d("100.00"), MOTIVO, a.usuarioId()));
        })).isInstanceOf(IllegalStateException.class);

        assertThat(anulaciones(clienteDeB)).isZero();
        assertThat(saldo(clienteDeB)).isEqualByComparingTo("-100.00");
        assertInvariante(clienteDeB);
        assertInvariante(clienteDeA);
    }

    // ---- CC-061: la base respalda la unicidad ----

    @Test
    @DisplayName("CC-061: un INSERT directo de una segunda ANULACION del mismo recibo rompe uk_mcc_recibo_tipo")
    void segundaAnulacionPorSqlRompeLaUnicidad() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null);
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido recibo = cobrarConRecibo(f, cliente, caja, "100.00");
        anular(f, cliente, recibo, "100.00", caja);

        assertThatThrownBy(() -> jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, "
                + "fecha, usuario_id, recibo_id, referencia, motivo) values (?, ?, 'ANULACION', 100, 0, now(6), ?, ?, "
                + "'Anulación duplicada', 'Cobro mal cargado')", f.id(), cliente, f.usuarioId(), recibo.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    // ---- CC-020 / CC-021: desactivar con el saldo que dejó la anulación ----

    @Test
    @DisplayName("CC-020/021: un adelanto cargado por error, anulado → saldo 0 → se puede desactivar; un adelanto legítimo (no anulado) sigue impidiéndolo (422)")
    void anularElAdelantoPermiteDesactivar() {
        Ferreteria f = ferreteria();
        long porError = cliente(f, null);
        long legitimo = cliente(f, null);
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido adelantoPorError = cobrarConRecibo(f, porError, caja, "500.00");
        cobrarConRecibo(f, legitimo, caja, "500.00");

        assertThatThrownBy(() -> clienteService.cambiarActivo(porError, false)).isInstanceOf(ReglaDeNegocioException.class);
        anular(f, porError, adelantoPorError, "500.00", caja);
        clienteService.cambiarActivo(porError, false);

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, porError)).isFalse();
        assertThatThrownBy(() -> clienteService.cambiarActivo(legitimo, false)).isInstanceOf(ReglaDeNegocioException.class);
        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, legitimo)).isTrue();
        assertInvariante(porError);
        assertInvariante(legitimo);
    }
}
