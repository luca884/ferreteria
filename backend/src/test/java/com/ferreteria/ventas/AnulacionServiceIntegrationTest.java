package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.CobroRegistrado;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3B1-312 (CC-056..CC-060, CC-062, CC-065..CC-067, CC-069, CC-070, CC-083, CC-020/021, CC-084): la anulación completa
 * contra MySQL real, sin HTTP (el controlador llega en C6): el cobro entra por {@link CobranzaService} y la anulación por
 * {@link AnulacionService}. Cada escenario deja I1, I2, I2b, I2e e I-inactivo al final. CUIT 30-8503NNNN-1.
 */
class AnulacionServiceIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    AnulacionService anulacionService;

    @Autowired
    ClienteService clienteService;

    @Autowired
    CuentaCorrienteParaVenta cuentaParaVenta;

    @Override
    protected String prefijoDeCuit() {
        return "30-8503";
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

    private CobroRegistrado cobrar(Escenario e, long cliente, MedioPago medio, String total, Imputar... imputaciones) {
        return cobranzaService.cobrar(new CobranzaPedido(clave(), cliente, medio, new BigDecimal(total), List.of(imputaciones)),
                e.f().usuarioId());
    }

    private CobroRegistrado anular(Escenario e, long reciboId, String motivo) {
        return anulacionService.anular(reciboId, motivo, e.f().usuarioId());
    }

    private Map<String, Object> filaDeRecibo(long reciboId) {
        return jdbc.queryForMap("select estado, fecha_anulacion, usuario_anulacion_id, caja_anulacion_id, motivo_anulacion "
                + "from recibo where id = ?", reciboId);
    }

    private String estado(long reciboId) {
        return (String) filaDeRecibo(reciboId).get("estado");
    }

    private long anulaciones(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, clienteId);
    }

    private long vigentes(long reciboId) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 1", Long.class, reciboId);
    }

    private long noVigentes(long reciboId) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 0", Long.class, reciboId);
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private void cerrarCaja(long cajaId) {
        jdbc.update("update caja set abierta = null where id = ?", cajaId);
    }

    private void sinCambios(long cliente, long recibo, String saldo) {
        assertThat(estado(recibo)).isEqualTo("EMITIDO");
        assertThat(anulaciones(cliente)).isZero();
        assertThat(noVigentes(recibo)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo(saldo);
    }

    // ---- CC-062: el efecto completo ----

    @Test
    @DisplayName("CC-062 feliz: cobro de 1.000 imputado a A 600 y B 400 → recibo ANULADO con quién/cuándo/caja/motivo recortado, 2 imputaciones no vigentes, UNA ANULACION debe 1.000 «Anulación Recibo N.º 1», saldo 1.000; el numerador no cambia y el próximo cobro es el N.º 2")
    void anulacionFeliz() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000.00");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "600.00");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "400.00");
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "1000.00", imputa(a.comprobanteId(), "600.00"),
                imputa(b.comprobanteId(), "400.00"));
        long recibo = cobro.recibo().getId();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");

        CobroRegistrado anulado = anular(e, recibo, "  " + MOTIVO + "  ");

        assertThat(anulado.recibo().isAnulado()).isTrue();
        assertThat(anulado.repetida()).isFalse();
        assertThat(anulado.anuladoPor()).isEqualTo("Dueno CC");
        assertThat(anulado.cobradoPor()).isEqualTo("Dueno CC");
        assertThat(anulado.imputaciones()).hasSize(2);
        assertThat(anulado.aCuenta()).isEqualByComparingTo("0");
        Map<String, Object> fila = filaDeRecibo(recibo);
        assertThat(fila.get("estado")).isEqualTo("ANULADO");
        assertThat(fila.get("fecha_anulacion")).isNotNull();
        assertThat(((Number) fila.get("usuario_anulacion_id")).longValue()).isEqualTo(e.f().usuarioId());
        assertThat(((Number) fila.get("caja_anulacion_id")).longValue()).isEqualTo(e.caja());
        assertThat(fila.get("motivo_anulacion")).isEqualTo(MOTIVO);
        assertThat(noVigentes(recibo)).isEqualTo(2);
        assertThat(vigentes(recibo)).isZero();
        assertThat(anulaciones(cliente)).isEqualTo(1);
        Map<String, Object> asiento = jdbc.queryForMap("select debe, haber, referencia, motivo, usuario_id, recibo_id "
                + "from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'", cliente);
        assertThat((BigDecimal) asiento.get("debe")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) asiento.get("haber")).isEqualByComparingTo("0");
        assertThat(asiento.get("referencia")).isEqualTo("Anulación Recibo N.º 1");
        assertThat(asiento.get("motivo")).isEqualTo(MOTIVO);
        assertThat(((Number) asiento.get("usuario_id")).longValue()).isEqualTo(e.f().usuarioId());
        assertThat(saldo(cliente)).isEqualByComparingTo("1000");
        assertThat(ultimoNumero(e.f().id())).isEqualTo(1L);
        assertInvariante(cliente);

        // CC-062 / CC-067: no consume número; el próximo cobro toma el siguiente
        CobroRegistrado nuevo = cobrar(e, cliente, MedioPago.EFECTIVO, "1000.00", imputa(a.comprobanteId(), "600.00"),
                imputa(b.comprobanteId(), "400.00"));
        assertThat(nuevo.recibo().getNumero()).isEqualTo(2L);
        assertThat(nuevo.recibo().getId()).isNotEqualTo(recibo);
        assertThat(estado(nuevo.recibo().getId())).isEqualTo("EMITIDO");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un adelanto (sin imputaciones) se anula: saldo −500 → 0, una ANULACION, sin imputaciones que tocar")
    void anulaUnAdelanto() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado adelanto = cobrar(e, cliente, MedioPago.EFECTIVO, "500");
        assertThat(saldo(cliente)).isEqualByComparingTo("-500");

        CobroRegistrado anulado = anular(e, adelanto.recibo().getId(), MOTIVO);

        assertThat(anulado.recibo().isAnulado()).isTrue();
        assertThat(anulado.imputaciones()).isEmpty();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    // ---- CC-058: la caja ----

    @Test
    @DisplayName("CC-058: un cobro por TRANSFERENCIA también exige caja abierta: sin caja → CAJA_CERRADA «Abrí la caja para anular.», recibo EMITIDO, sin ANULACION")
    void transferenciaExigeCajaAbierta() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.TRANSFERENCIA, "300");
        cerrarCaja(e.caja());

        assertThatThrownBy(() -> anular(e, cobro.recibo().getId(), MOTIVO)).isInstanceOfSatisfying(CobranzaRechazadaException.class, ex -> {
            assertThat(ex.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
            assertThat(ex.getMessage()).isEqualTo("Abrí la caja para anular.");
        });

        sinCambios(cliente, cobro.recibo().getId(), "-300");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-058: caja abierta ayer → CAJA_DE_OTRO_DIA «La caja del dd/MM sigue abierta. Cerrala antes de anular.», sin cambios")
    void cajaDeOtroDia() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "300");
        jdbc.update("update caja set fecha_apertura = ? where id = ?", LocalDateTime.now().minusDays(2), e.caja());

        assertThatThrownBy(() -> anular(e, cobro.recibo().getId(), MOTIVO)).isInstanceOfSatisfying(CobranzaRechazadaException.class, ex -> {
            assertThat(ex.motivo()).isEqualTo(Motivo.CAJA_DE_OTRO_DIA);
            assertThat(ex.getMessage()).startsWith("La caja del ").endsWith(" sigue abierta. Cerrala antes de anular.");
        });

        sinCambios(cliente, cobro.recibo().getId(), "-300");
    }

    @Test
    @DisplayName("CC-058/CC-083: un recibo de una caja ya CERRADA se anula con la caja de HOY abierta: 200 y caja_anulacion_id es la de hoy (la original no se lee ni se toca)")
    void reciboDeCajaCerradaConCajaDeHoy() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "700");
        long cajaDelCobro = e.caja();
        cerrarCaja(cajaDelCobro);
        long cajaDeHoy = cajaAbiertaHoy(e.f().id());
        assertThat(cajaDeHoy).isNotEqualTo(cajaDelCobro);

        anular(e, cobro.recibo().getId(), MOTIVO);

        Map<String, Object> fila = filaDeRecibo(cobro.recibo().getId());
        assertThat(((Number) fila.get("caja_anulacion_id")).longValue()).isEqualTo(cajaDeHoy);
        assertThat(jdbc.queryForObject("select caja_id from recibo where id = ?", Long.class, cobro.recibo().getId()))
                .isEqualTo(cajaDelCobro);
        assertThat(saldo(cliente)).isZero();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-058/CC-066: dos ferreterías con caja abierta cada una: cada anulación usa la caja de SU ferretería")
    void usaLaCajaDeSuFerreteria() {
        Escenario uno = escenario();
        long c1 = cliente(uno.f(), null);
        CobroRegistrado r1 = cobrar(uno, c1, MedioPago.EFECTIVO, "10");
        Escenario dos = escenario();
        long c2 = cliente(dos.f(), null);
        CobroRegistrado r2 = cobrar(dos, c2, MedioPago.EFECTIVO, "20");

        TenantContext.setCurrentFerreteriaId(uno.f().id());
        anular(uno, r1.recibo().getId(), MOTIVO);
        TenantContext.setCurrentFerreteriaId(dos.f().id());
        anular(dos, r2.recibo().getId(), MOTIVO);

        assertThat(((Number) filaDeRecibo(r1.recibo().getId()).get("caja_anulacion_id")).longValue()).isEqualTo(uno.caja());
        assertThat(((Number) filaDeRecibo(r2.recibo().getId()).get("caja_anulacion_id")).longValue()).isEqualTo(dos.caja());
        assertInvariante(c1);
        assertInvariante(c2);
    }

    // ---- CC-057: el orden de validación ----

    @Test
    @DisplayName("CC-057: el cuerpo gana al 404 (motivo inválido sobre un recibo inexistente → 400); inexistente → 404 «Recibo no encontrado»")
    void ordenCuerpoYRecibo() {
        Escenario e = escenario();

        assertThatThrownBy(() -> anular(e, 999_999_999L, "abc")).isInstanceOfSatisfying(DatosInvalidosException.class,
                ex -> assertThat(ex.errors()).containsKey("motivo"));
        assertThatThrownBy(() -> anular(e, 999_999_999L, MOTIVO)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recibo no encontrado");
    }

    @Test
    @DisplayName("CC-057: la caja gana al estado (recibo anulado + caja cerrada → 422 CAJA_CERRADA); anulado + cliente activo + caja abierta → 409 con nombre y hora; desactivado gana al 409 (422)")
    void ordenCajaClienteEstado() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "300");
        long recibo = cobro.recibo().getId();
        anular(e, recibo, MOTIVO);

        // anulado + cliente activo + caja abierta → 409 con quién y cuándo
        assertThatThrownBy(() -> anular(e, recibo, MOTIVO)).isInstanceOfSatisfying(CobranzaRechazadaException.class, ex -> {
            assertThat(ex.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
            assertThat(ex.motivo().estadoHttp()).isEqualTo(409);
            assertThat(ex.getMessage()).matches("Este recibo ya fue anulado por Dueno CC el \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}\\.");
            assertThat(ex.anuladoPor()).isEqualTo("Dueno CC");
            assertThat(ex.fechaAnulacion()).isNotNull();
        });
        // anulado + desactivado + caja abierta → 422 desactivado (gana al 409)
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);
        assertThatThrownBy(() -> anular(e, recibo, MOTIVO)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
        jdbc.update("update cliente set activo = 1 where id = ?", cliente);
        // anulado + caja cerrada → 422 CAJA_CERRADA (gana al estado y al cliente)
        cerrarCaja(e.caja());
        assertThatThrownBy(() -> anular(e, recibo, MOTIVO)).isInstanceOfSatisfying(CobranzaRechazadaException.class,
                ex -> assertThat(ex.motivo()).isEqualTo(Motivo.CAJA_CERRADA));

        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    // ---- CC-059: cliente desactivado ----

    @Test
    @DisplayName("CC-059: cliente desactivado → 422 «El cliente está desactivado.» y el recibo sigue EMITIDO; reactivado → 200")
    void clienteDesactivado() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "100");
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "100", imputa(venta.comprobanteId(), "100"));
        long recibo = cobro.recibo().getId();
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        assertThatThrownBy(() -> anular(e, recibo, MOTIVO)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("El cliente está desactivado.");
        sinCambios(cliente, recibo, "0");
        assertThat(vigentes(recibo)).isEqualTo(1);
        assertInvariante(cliente);

        jdbc.update("update cliente set activo = 1 where id = ?", cliente);
        anular(e, recibo, MOTIVO);

        assertThat(estado(recibo)).isEqualTo("ANULADO");
        assertThat(saldo(cliente)).isEqualByComparingTo("100");
        assertInvariante(cliente);
    }

    // ---- CC-060: doble anulación ----

    @Test
    @DisplayName("CC-060: la segunda anulación del mismo recibo → 409 RECIBO_YA_ANULADO; una sola ANULACION y el saldo no cambia")
    void dobleAnulacion() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "800");
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "800", imputa(venta.comprobanteId(), "800"));
        long recibo = cobro.recibo().getId();
        anular(e, recibo, MOTIVO);
        BigDecimal saldoTrasLaPrimera = saldo(cliente);

        assertThatThrownBy(() -> anular(e, recibo, "Otra vez, por las dudas")).isInstanceOfSatisfying(CobranzaRechazadaException.class,
                ex -> assertThat(ex.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO));

        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo(saldoTrasLaPrimera).isEqualByComparingTo("800");
        assertThat(filaDeRecibo(recibo).get("motivo_anulacion")).isEqualTo(MOTIVO);
        assertInvariante(cliente);
    }

    // ---- CC-065: el límite no frena la anulación ----

    @Test
    @DisplayName("CC-065 (Q5): límite 5.000, saldo 4.000 tras un cobro de 2.000 → anular da 200, saldo 6.000, sin excedio_limite; la venta CC siguiente ya no tiene cupo")
    void anulacionExcedeElLimite() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000.00");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "6000.00");
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.EFECTIVO, "2000", imputa(venta.comprobanteId(), "2000"));
        assertThat(saldo(cliente)).isEqualByComparingTo("4000");

        anular(e, cobro.recibo().getId(), MOTIVO);

        assertThat(saldo(cliente)).isEqualByComparingTo("6000");
        assertThat(jdbc.queryForObject("select excedio_limite from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Boolean.class, cliente)).isFalse();
        assertThat(jdbc.queryForObject("select autorizado_por from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, cliente)).isNull();
        CupoCuenta cupo = new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            return cuentaParaVenta.verificarCupo(cliente, new BigDecimal("100"));
        });
        assertThat(cupo.excede()).isTrue();
        assertThat(cupo.alcanza()).isFalse();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-065: límite NULL (sin cuenta habilitada) y un cobro anulable → 200")
    void anulaConLimiteNulo() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado cobro = cobrar(e, cliente, MedioPago.TRANSFERENCIA, "50");

        anular(e, cobro.recibo().getId(), MOTIVO);

        assertThat(estado(cobro.recibo().getId())).isEqualTo("ANULADO");
        assertThat(saldo(cliente)).isZero();
        assertInvariante(cliente);
    }

    // ---- CC-066: aislamiento por ferretería ----

    @Test
    @DisplayName("CC-066: el recibo de otra ferretería es 404 «Recibo no encontrado» y la otra ferretería queda intacta")
    void reciboAjeno() {
        Escenario a = escenario();
        long clienteA = cliente(a.f(), null);
        CobroRegistrado deA = cobrar(a, clienteA, MedioPago.EFECTIVO, "100");
        Escenario b = escenario();
        long clienteB = cliente(b.f(), null);
        cobrar(b, clienteB, MedioPago.EFECTIVO, "100");

        // el Dueño de B (tenant B) intenta anular el recibo de A
        assertThatThrownBy(() -> anular(b, deA.recibo().getId(), MOTIVO)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recibo no encontrado");

        sinCambios(clienteA, deA.recibo().getId(), "-100");
        assertThat(saldo(clienteB)).isEqualByComparingTo("-100");
        assertInvariante(clienteA);
        assertInvariante(clienteB);
    }

    // ---- CC-067 / CC-070: cobrar de nuevo y la repetida ----

    @Test
    @DisplayName("CC-067: tras anular, cobrar de nuevo con OTRA clave da un recibo nuevo con el N.º siguiente y su propio COBRO")
    void cobrarDeNuevoConClaveNueva() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        CobroRegistrado primero = cobrar(e, cliente, MedioPago.EFECTIVO, "250");
        anular(e, primero.recibo().getId(), MOTIVO);

        CobroRegistrado segundo = cobrar(e, cliente, MedioPago.EFECTIVO, "250");

        assertThat(segundo.repetida()).isFalse();
        assertThat(segundo.recibo().getNumero()).isEqualTo(2L);
        assertThat(recibos(cliente)).isEqualTo(2);
        assertThat(cobros(cliente)).isEqualTo(2);
        assertThat(saldo(cliente)).isEqualByComparingTo("-250");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-070: la cobranza REPETIDA (misma clave y contenido) de un recibo anulado da 200 ANULADO, un solo recibo y un solo COBRO, también con la caja cerrada; con otro total, CLAVE_CON_OTRO_CONTENIDO")
    void repetidaDeUnReciboAnulado() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "500");
        String clave = clave();
        CobranzaPedido pedido = new CobranzaPedido(clave, cliente, MedioPago.EFECTIVO, new BigDecimal("300"),
                List.of(imputa(venta.comprobanteId(), "300")));
        CobroRegistrado original = cobranzaService.cobrar(pedido, e.f().usuarioId());
        anular(e, original.recibo().getId(), MOTIVO);
        BigDecimal saldoAnulado = saldo(cliente);

        CobroRegistrado repetida = cobranzaService.cobrar(pedido, e.f().usuarioId());

        assertThat(repetida.repetida()).isTrue();
        assertThat(repetida.recibo().getId()).isEqualTo(original.recibo().getId());
        assertThat(repetida.recibo().isAnulado()).isTrue();
        assertThat(repetida.anuladoPor()).isEqualTo("Dueno CC");
        assertThat(repetida.imputaciones()).hasSize(1);
        assertThat(repetida.imputaciones().get(0).importe()).isEqualByComparingTo("300");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo(saldoAnulado);
        assertThat(ultimoNumero(e.f().id())).isEqualTo(1L);

        // aunque la caja esté cerrada: sigue siendo 200 ANULADO
        cerrarCaja(e.caja());
        assertThat(cobranzaService.cobrar(pedido, e.f().usuarioId()).recibo().isAnulado()).isTrue();

        CobranzaPedido otroTotal = new CobranzaPedido(clave, cliente, MedioPago.EFECTIVO, new BigDecimal("301"),
                List.of(imputa(venta.comprobanteId(), "300")));
        assertThatThrownBy(() -> cobranzaService.cobrar(otroTotal, e.f().usuarioId())).isInstanceOfSatisfying(
                CobranzaRechazadaException.class, ex -> assertThat(ex.motivo()).isEqualTo(Motivo.CLAVE_CON_OTRO_CONTENIDO));
        assertThat(recibos(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    // ---- CC-020 / CC-021: el adelanto por error ----

    @Test
    @DisplayName("CC-020/021: un adelanto cargado por error se anula (saldo 0) y entonces el cliente se desactiva con 200; un adelanto legítimo sigue sin poder desactivarse (422)")
    void adelantoPorErrorYDesactivar() {
        Escenario e = escenario();
        long porError = cliente(e.f(), null);
        long legitimo = cliente(e.f(), null);
        CobroRegistrado error = cobrar(e, porError, MedioPago.EFECTIVO, "500");
        cobrar(e, legitimo, MedioPago.EFECTIVO, "500");
        assertThatThrownBy(() -> clienteService.cambiarActivo(porError, false)).isInstanceOf(ReglaDeNegocioException.class);

        anular(e, error.recibo().getId(), MOTIVO);
        clienteService.cambiarActivo(porError, false);

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, porError)).isFalse();
        assertThat(saldo(porError)).isZero();
        assertThatThrownBy(() -> clienteService.cambiarActivo(legitimo, false)).isInstanceOf(ReglaDeNegocioException.class);
        assertInvariante(porError);
        assertInvariante(legitimo);
    }
}
