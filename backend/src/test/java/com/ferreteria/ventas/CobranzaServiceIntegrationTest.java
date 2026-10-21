package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.CobroRegistrado;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3-311 (CC-033..CC-038, CC-040, CC-041, CC-054): la fachada de cobrar completa contra MySQL real, sin HTTP (el controlador
 * llega en C4). Cada escenario deja la invariante I1 + I2 al final. CUIT 30-8403NNNN-1.
 */
class CobranzaServiceIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CobranzaService cobranzaService;

    @Override
    protected String prefijoDeCuit() {
        return "30-8403";
    }

    /** Ferretería con caja abierta hoy y el tenant puesto en el hilo del test. */
    private record Escenario(Ferreteria f, long caja) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        return new Escenario(f, caja);
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private static CobranzaPedido pedido(String clave, long cliente, MedioPago medio, String total, Imputar... imputaciones) {
        return new CobranzaPedido(clave, cliente, medio, new BigDecimal(total), List.of(imputaciones));
    }

    private CobroRegistrado cobrar(Escenario e, CobranzaPedido pedido) {
        return cobranzaService.cobrar(pedido, e.f().usuarioId());
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    @Test
    @DisplayName("cobro feliz a un comprobante: recibo N.º 1 en la caja abierta, una imputación, un COBRO y el saldo en 0")
    void cobroFeliz() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "1000");

        CobroRegistrado cobro = cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "1000", imputa(venta.comprobanteId(), "1000")));

        assertThat(cobro.repetida()).isFalse();
        assertThat(cobro.recibo().getNumero()).isEqualTo(1L);
        assertThat(cobro.recibo().getCajaId()).isEqualTo(e.caja());
        assertThat(cobro.recibo().getMedio()).isEqualTo(MedioPago.EFECTIVO);
        assertThat(cobro.cobradoPor()).isEqualTo("Dueno CC");
        assertThat(cobro.imputaciones()).hasSize(1);
        assertThat(cobro.imputaciones().get(0).referencia()).isEqualTo("Venta N.º " + venta.numero());
        assertThat(cobro.aCuenta()).isEqualByComparingTo("0");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("varios comprobantes y excedente a cuenta: 700 sobre deudas de 300 y 200 deja 200 a favor (saldo −200)")
    void variosYExcedente() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "200");

        CobroRegistrado cobro = cobrar(e, pedido(clave(), cliente, MedioPago.TRANSFERENCIA, "700",
                imputa(a.comprobanteId(), "300"), imputa(b.comprobanteId(), "200")));

        assertThat(cobro.recibo().getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
        assertThat(cobro.imputaciones()).hasSize(2);
        assertThat(cobro.aCuenta()).isEqualByComparingTo("200");
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputaciones(cliente)).isEqualTo(2);
        assertThat(saldo(cliente)).isEqualByComparingTo("-200");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("ADELANTO (#5574): cliente activo con saldo 0, sin pendientes ni cuenta habilitada cobra 500: recibo, COBRO, saldo −500, a cuenta 500")
    void adelanto() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);

        CobroRegistrado cobro = cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "500"));

        assertThat(cobro.imputaciones()).isEmpty();
        assertThat(cobro.aCuenta()).isEqualByComparingTo("500");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("-500");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un cliente desactivado es 422 «El cliente está desactivado.», sin recibo ni número consumido")
    void clienteDesactivado() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        assertThatThrownBy(() -> cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "10")))
                .isInstanceOf(ReglaDeNegocioException.class).hasMessage("El cliente está desactivado.");

        assertThat(recibos(cliente)).isZero();
        assertThat(ultimoNumero(e.f().id())).isNull();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("R-004: cobrar desde DENTRO de una transacción externa se rechaza (IllegalStateException) y no deja nada: sin recibo, sin número consumido, saldo intacto; fuera de la transacción cobra con normalidad (recibo N.º 1)")
    void noSeCobraDentroDeUnaTransaccionExterna() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "1000");
        CobranzaPedido pedido = pedido(clave(), cliente, MedioPago.EFECTIVO, "400", imputa(venta.comprobanteId(), "400"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> cobrar(e, pedido)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CobranzaService no puede llamarse dentro de una transaccion: cada intento abre la suya.");

        assertThat(recibos(cliente)).isZero();
        assertThat(ultimoNumero(e.f().id())).isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("1000");
        assertInvariante(cliente);

        CobroRegistrado cobro = cobrar(e, pedido);

        assertThat(cobro.repetida()).isFalse();
        assertThat(cobro.recibo().getNumero()).isEqualTo(1L);
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("numeración correlativa por ferretería y SIN saltos por rechazos: un 409 entre dos éxitos deja los números 1 y 2; la otra ferretería arranca en 1")
    void numeracionSinSaltos() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "100");

        CobroRegistrado primero = cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "40", imputa(venta.comprobanteId(), "40")));
        assertThatThrownBy(() -> cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "200", imputa(venta.comprobanteId(), "200"))))
                .isInstanceOf(CobranzaRechazadaException.class);
        CobroRegistrado segundo = cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "60", imputa(venta.comprobanteId(), "60")));

        assertThat(primero.recibo().getNumero()).isEqualTo(1L);
        assertThat(segundo.recibo().getNumero()).isEqualTo(2L);
        assertThat(ultimoNumero(e.f().id())).isEqualTo(2L);

        Escenario otra = escenario();
        long clienteOtra = cliente(otra.f(), null);
        assertThat(cobrar(otra, pedido(clave(), clienteOtra, MedioPago.EFECTIVO, "5")).recibo().getNumero()).isEqualTo(1L);
        assertInvariante(cliente);
        assertInvariante(clienteOtra);
    }

    @Test
    @DisplayName("repetida con el mismo contenido: el mismo recibo, repetida=true, un solo COBRO y el saldo no se mueve")
    void repetidaMismoContenido() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "500");
        String clave = clave();

        CobroRegistrado original = cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "300", imputa(venta.comprobanteId(), "300")));
        CobroRegistrado repetida = cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "300.00", imputa(venta.comprobanteId(), "300.0")));

        assertThat(repetida.repetida()).isTrue();
        assertThat(repetida.recibo().getId()).isEqualTo(original.recibo().getId());
        assertThat(repetida.recibo().getNumero()).isEqualTo(1L);
        assertThat(repetida.cobradoPor()).isEqualTo("Dueno CC");
        // misma imputación por valor (la original trae la escala del pedido y la repetida la de la base: 300 vs 300.00)
        assertThat(repetida.imputaciones()).hasSameSizeAs(original.imputaciones());
        assertThat(repetida.imputaciones().get(0).comprobanteId()).isEqualTo(original.imputaciones().get(0).comprobanteId());
        assertThat(repetida.imputaciones().get(0).referencia()).isEqualTo(original.imputaciones().get(0).referencia());
        assertThat(repetida.imputaciones().get(0).importe()).isEqualByComparingTo(original.imputaciones().get(0).importe());
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertThat(ultimoNumero(e.f().id())).isEqualTo(1L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("misma clave con OTRO contenido (total, medio, imputaciones): 422 CLAVE_CON_OTRO_CONTENIDO, sin cambios")
    void repetidaOtroContenido() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "500");
        String clave = clave();
        cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "300", imputa(venta.comprobanteId(), "300")));

        for (CobranzaPedido distinto : List.of(
                pedido(clave, cliente, MedioPago.EFECTIVO, "301", imputa(venta.comprobanteId(), "300")),
                pedido(clave, cliente, MedioPago.TRANSFERENCIA, "300", imputa(venta.comprobanteId(), "300")),
                pedido(clave, cliente, MedioPago.EFECTIVO, "300", imputa(venta.comprobanteId(), "299")),
                pedido(clave, cliente, MedioPago.EFECTIVO, "300"))) {
            assertThatThrownBy(() -> cobrar(e, distinto)).isInstanceOfSatisfying(CobranzaRechazadaException.class,
                    ex -> assertThat(ex.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO));
        }

        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("repetida con la caja ya CERRADA: devuelve el recibo original (200), no 422 de caja")
    void repetidaConLaCajaCerrada() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        String clave = clave();
        CobroRegistrado original = cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "50"));
        jdbc.update("update caja set abierta = null where id = ?", e.caja());

        CobroRegistrado repetida = cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "50"));

        assertThat(repetida.repetida()).isTrue();
        assertThat(repetida.recibo().getId()).isEqualTo(original.recibo().getId());
        assertThat(recibos(cliente)).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma clave en OTRA ferretería es un recibo nuevo (la clave es por ferretería)")
    void mismaClaveEnOtraFerreteria() {
        String clave = clave();
        Escenario e1 = escenario();
        long c1 = cliente(e1.f(), null);
        CobroRegistrado r1 = cobrar(e1, pedido(clave, c1, MedioPago.EFECTIVO, "10"));
        Escenario e2 = escenario();
        long c2 = cliente(e2.f(), null);

        CobroRegistrado r2 = cobrar(e2, pedido(clave, c2, MedioPago.EFECTIVO, "10"));

        assertThat(r2.repetida()).isFalse();
        assertThat(r2.recibo().getId()).isNotEqualTo(r1.recibo().getId());
        assertThat(r2.recibo().getNumero()).isEqualTo(1L);
        assertInvariante(c1);
        assertInvariante(c2);
    }

    @Test
    @DisplayName("sin caja abierta: 422 CAJA_CERRADA sin recibo ni número consumido")
    void sinCaja() {
        Ferreteria f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f.id());
        long cliente = cliente(f, null);

        assertThatThrownBy(() -> cobranzaService.cobrar(pedido(clave(), cliente, MedioPago.EFECTIVO, "10"), f.usuarioId()))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, ex -> {
                    assertThat(ex.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
                    assertThat(ex.getMessage()).isEqualTo("Abrí la caja para cobrar.");
                });

        assertThat(recibos(cliente)).isZero();
        assertThat(ultimoNumero(f.id())).isNull();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("la caja abierta de otro día: 422 CAJA_DE_OTRO_DIA sin consumir número")
    void cajaDeOtroDia() {
        Ferreteria f = ferreteria();
        cajaAbiertaEl(f.id(), LocalDateTime.now().minusDays(2));
        TenantContext.setCurrentFerreteriaId(f.id());
        long cliente = cliente(f, null);

        assertThatThrownBy(() -> cobranzaService.cobrar(pedido(clave(), cliente, MedioPago.EFECTIVO, "10"), f.usuarioId()))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class,
                        ex -> assertThat(ex.motivo()).isEqualTo(Motivo.CAJA_DE_OTRO_DIA));

        assertThat(recibos(cliente)).isZero();
        assertThat(ultimoNumero(f.id())).isNull();
    }

    @Test
    @DisplayName("renombrar al cliente después: el recibo conserva la razón social de cuando se cobró")
    void elReciboConservaLaRazonSocial() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        String antes = jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, cliente);
        String clave = clave();
        cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "10"));

        jdbc.update("update cliente set razon_social = 'Otro Nombre SA' where id = ?", cliente);
        CobroRegistrado repetida = cobrar(e, pedido(clave, cliente, MedioPago.EFECTIVO, "10"));

        assertThat(repetida.recibo().getClienteNombre()).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select cliente_nombre from recibo where cliente_id = ?", String.class, cliente))
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("sobre-imputar es 409 IMPUTACION_EXCEDE_DEUDA con la deuda actual, sin recibo, sin COBRO y sin consumir número")
    void excedeLaDeuda() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "100");

        assertThatThrownBy(() -> cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "150", imputa(venta.comprobanteId(), "150"))))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, ex -> {
                    assertThat(ex.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
                    assertThat(ex.deudas()).hasSize(1);
                    assertThat(ex.deudas().get(0).comprobanteId()).isEqualTo(venta.comprobanteId());
                    assertThat(ex.deudas().get(0).pendiente()).isEqualByComparingTo("100");
                });

        assertThat(recibos(cliente)).isZero();
        assertThat(cobros(cliente)).isZero();
        assertThat(imputaciones(cliente)).isZero();
        assertThat(ultimoNumero(e.f().id())).isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("100");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un comprobante de otro cliente o un cliente de otra ferretería es 404, sin escribir nada")
    void tenant() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        long otroCliente = cliente(e.f(), "1000");
        VentaSuelta deOtro = ventaEnLibro(e.f(), otroCliente, "100");
        Ferreteria ajena = ferreteria();
        long clienteAjeno = cliente(ajena, null);

        assertThatThrownBy(() -> cobrar(e, pedido(clave(), cliente, MedioPago.EFECTIVO, "10", imputa(deOtro.comprobanteId(), "10"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cobrar(e, pedido(clave(), clienteAjeno, MedioPago.EFECTIVO, "10")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(recibos(cliente)).isZero();
        assertThat(recibos(clienteAjeno)).isZero();
        assertThat(ultimoNumero(e.f().id())).isNull();
        assertInvariante(cliente);
        assertInvariante(otroCliente);
    }

    @Test
    @DisplayName("un pedido mal formado es 400 sin tocar la base (ni número)")
    void malFormado() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);

        assertThatThrownBy(() -> cobrar(e, pedido(clave(), cliente, MedioPago.DEBITO, "10")))
                .isInstanceOf(DatosInvalidosException.class);

        assertThat(ultimoNumero(e.f().id())).isNull();
    }
}
