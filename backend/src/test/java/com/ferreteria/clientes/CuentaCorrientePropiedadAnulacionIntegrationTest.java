package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.CobroRegistrado;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CCT3B1-410 (CC-093, CC-084, CC-002): test de PROPIEDAD de la cuenta corriente con anulaciones. Una secuencia aleatoria de 200
 * operaciones, con semilla fija e IMPRESA (para repetirla: {@code -Dcc.semilla=...}), sobre 3 clientes y con centavos, por los
 * servicios REALES (ventas a cuenta, cobranzas imputadas / parciales / «a cuenta» / adelantos, anulaciones de recibos con y sin
 * imputaciones, de adelantos y de cobros de una caja ya cerrada, anulaciones repetidas (409), cobranzas repetidas de recibos
 * anulados (200 ANULADO), sobre-imputaciones, desactivaciones y reactivaciones). Tras CADA paso: I1, I2a..I2e e I-inactivo
 * ({@code assertInvariante}) y un MODELO independiente (saldo, pendientes por comprobante, «a cuenta») que tiene que coincidir con
 * el servicio y con la base; además Σ pendiente − aCuenta = saldo en lo que devuelve el servicio de pendientes. CUIT 30-8504NNNN-1.
 */
class CuentaCorrientePropiedadAnulacionIntegrationTest extends CuentaCorrienteTestBase {

    private static final String DESACTIVADO = "El cliente está desactivado.";
    private static final String CON_SALDO = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";
    private static final int PASOS = 200;

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    AnulacionService anulacionService;

    @Autowired
    VentaService ventaService;

    @Autowired
    CajaService cajaService;

    @Autowired
    ClienteService clienteService;

    @Autowired
    CuentaCorrienteService servicioDeCuenta;

    @Override
    protected String prefijoDeCuit() {
        return "30-8504";
    }

    /** Lo que sabe el modelo de un recibo emitido: para anularlo, repetirlo y calcular el «a cuenta». */
    private static final class ReciboModelo {
        final long id;
        final CobranzaPedido pedido;
        final BigDecimal total;
        final Map<Long, BigDecimal> imputaciones;
        final long cajaDelCobro;
        boolean anulado;

        ReciboModelo(long id, CobranzaPedido pedido, BigDecimal total, Map<Long, BigDecimal> imputaciones, long cajaDelCobro) {
            this.id = id;
            this.pedido = pedido;
            this.total = total;
            this.imputaciones = imputaciones;
            this.cajaDelCobro = cajaDelCobro;
        }
    }

    /** El modelo de un cliente, escrito aparte del servicio: saldo, pendiente por comprobante (ordenado por id = FIFO) y recibos. */
    private static final class Modelo {
        BigDecimal saldo = BigDecimal.ZERO;
        boolean activo = true;
        final TreeMap<Long, BigDecimal> abiertos = new TreeMap<>();
        final List<ReciboModelo> recibos = new ArrayList<>();

        BigDecimal aCuenta() {
            return recibos.stream().filter(r -> !r.anulado)
                    .map(r -> r.total.subtract(r.imputaciones.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        void sumarPendiente(long comprobante, BigDecimal importe) {
            BigDecimal queda = abiertos.getOrDefault(comprobante, BigDecimal.ZERO).add(importe);
            if (queda.signum() == 0) {
                abiertos.remove(comprobante);
            } else {
                abiertos.put(comprobante, queda);
            }
        }
    }

    private static BigDecimal centavos(Random azar, int maximo) {
        return BigDecimal.valueOf(1 + azar.nextInt(maximo), 2);
    }

    private long articuloDeCentavos(Ferreteria f) {
        String codigo = "P-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 0.01, 0.01, 'IVA_21', "
                + "'UNIDAD', 100000000, 0, 1)", f.id(), codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
    }

    @Test
    @DisplayName("propiedad: 200 operaciones aleatorias (semilla fija e impresa) con ≥ 20 anulaciones —con y sin imputaciones, adelantos y cobros de caja cerrada— mezcladas con ventas, cobranzas, repetidas, 409 y desactivaciones: I1, I2a..I2e, I-inactivo y el modelo valen tras cada paso")
    void propiedadDeLaAnulacion() throws Exception {
        long semilla = Long.getLong("cc.semilla", 20261020L);
        System.out.println("propiedadDeLaAnulacion: semilla=" + semilla + " (para repetirla: -Dcc.semilla=" + semilla + ")");
        Random azar = new Random(semilla);
        Ferreteria f = ferreteria();
        long[] caja = {cajaAbiertaHoy(f.id())};
        TenantContext.setCurrentFerreteriaId(f.id());
        long articulo = articuloDeCentavos(f);
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, f.id());
        long[] clientes3 = {cliente(f, "1000000.00"), cliente(f, "1000000.00"), cliente(f, null)}; // el 3.º nunca tiene deuda
        Modelo[] modelo = {new Modelo(), new Modelo(), new Modelo()};
        int[] hechos = new int[16];
        int cajasCerradasAnuladas = 0;
        int conImputaciones = 0;
        int adelantosAnulados = 0;

        for (int paso = 0; paso < PASOS; paso++) {
            int c = azar.nextInt(3);
            Modelo m = modelo[c];
            int accion = azar.nextInt(16);
            // una acción que no se puede hacer ahora se reemplaza por otra posible (la secuencia sigue siendo reproducible)
            if (!m.activo) {
                accion = azar.nextInt(3) == 0 ? 14 : 15; // 14: reactivar; 15: anular con el cliente desactivado
            }
            List<ReciboModelo> anulables = m.recibos.stream().filter(r -> !r.anulado).toList();
            List<ReciboModelo> anulados = m.recibos.stream().filter(r -> r.anulado).toList();
            if (m.activo) {
                if ((accion == 3 || accion == 4 || accion == 5) && m.abiertos.isEmpty()) {
                    accion = 0; // sin pendientes no hay a quién imputar: vende
                }
                if ((accion == 6 || accion == 7 || accion == 8) && anulables.isEmpty()) {
                    accion = 9; // sin recibos para anular: adelanto
                }
                if (accion == 10 && anulados.isEmpty()) {
                    accion = 6;
                    if (anulables.isEmpty()) {
                        accion = 9;
                    }
                }
                if (accion == 11 && m.recibos.isEmpty()) {
                    accion = 9;
                }
                if (c == 2 && (accion == 0 || accion == 1 || accion == 2)) {
                    accion = 9; // el 3.er cliente no tiene cuenta corriente habilitada: solo adelantos y anulaciones
                }
                if (c == 2 && (accion == 3 || accion == 4 || accion == 5)) {
                    accion = 9;
                }
                if (accion == 15 || accion == 14) {
                    accion = 12; // desactivar solo se prueba como acción propia
                }
            }
            hechos[accion]++;
            String donde = "paso " + paso + ", acción " + accion + ", cliente " + c + " (semilla " + semilla + ")";
            try {
                switch (accion) {
                    case 0, 1, 2 -> { // venta a cuenta por VentaService (centavos)
                        int unidades = 1 + azar.nextInt(30000);
                        BigDecimal total = BigDecimal.valueOf(unidades, 2);
                        ConfirmarVenta venta = new ConfirmarVenta(UUID.randomUUID().toString(),
                                List.of(new Renglon(articulo, new BigDecimal(unidades), new BigDecimal("0.01"))), total, forma,
                                BigDecimal.ZERO, List.of(), null, null, true, clientes3[c], false);
                        long comprobante = ventaService.cobrar(venta, f.usuarioId(), false).comprobante().getId();
                        m.saldo = m.saldo.add(total);
                        m.sumarPendiente(comprobante, total);
                    }
                    case 3, 4, 5 -> { // cobranza imputada: completa (3), parcial (4) o con un resto «a cuenta» (5)
                        List<Long> abiertos = new ArrayList<>(m.abiertos.keySet());
                        Map<Long, BigDecimal> imputado = new TreeMap<>();
                        BigDecimal suma = BigDecimal.ZERO;
                        int cuantos = 1 + azar.nextInt(Math.min(3, abiertos.size()));
                        for (int i = 0; i < cuantos; i++) {
                            Long comprobante = abiertos.remove(azar.nextInt(abiertos.size()));
                            BigDecimal pendiente = m.abiertos.get(comprobante);
                            BigDecimal importe = accion == 3 || pendiente.compareTo(new BigDecimal("0.01")) == 0 ? pendiente
                                    : centavos(azar, pendiente.movePointRight(2).intValueExact());
                            imputado.put(comprobante, importe);
                            suma = suma.add(importe);
                        }
                        BigDecimal total = accion == 5 ? suma.add(centavos(azar, 50000)) : suma;
                        List<Imputar> imputaciones = imputado.entrySet().stream().map(e -> new Imputar(e.getKey(), e.getValue()))
                                .toList();
                        CobranzaPedido pedido = new CobranzaPedido(UUID.randomUUID().toString(), clientes3[c], MedioPago.EFECTIVO,
                                total, imputaciones);
                        CobroRegistrado cobro = cobranzaService.cobrar(pedido, f.usuarioId());
                        assertThat(cobro.repetida()).as(donde).isFalse();
                        m.saldo = m.saldo.subtract(total);
                        imputado.forEach((comprobante, importe) -> m.sumarPendiente(comprobante, importe.negate()));
                        m.recibos.add(new ReciboModelo(cobro.recibo().getId(), pedido, total, imputado, caja[0]));
                    }
                    case 6, 7, 8 -> { // anular un recibo no anulado (con o sin imputaciones, adelanto, de una caja ya cerrada)
                        ReciboModelo r = anulables.get(azar.nextInt(anulables.size()));
                        CobroRegistrado anulado = anulacionService.anular(r.id, "Cobro mal cargado " + paso, f.usuarioId());
                        assertThat(anulado.recibo().isAnulado()).as(donde).isTrue();
                        r.anulado = true;
                        m.saldo = m.saldo.add(r.total);
                        r.imputaciones.forEach(m::sumarPendiente);
                        if (r.cajaDelCobro != caja[0]) {
                            cajasCerradasAnuladas++;
                        }
                        if (r.imputaciones.isEmpty()) {
                            adelantosAnulados++;
                        } else {
                            conImputaciones++;
                        }
                    }
                    case 9 -> { // adelanto a cuenta: sin imputar
                        BigDecimal total = centavos(azar, 80000);
                        CobranzaPedido pedido = new CobranzaPedido(UUID.randomUUID().toString(), clientes3[c], MedioPago.EFECTIVO,
                                total, List.of());
                        CobroRegistrado cobro = cobranzaService.cobrar(pedido, f.usuarioId());
                        m.saldo = m.saldo.subtract(total);
                        m.recibos.add(new ReciboModelo(cobro.recibo().getId(), pedido, total, new TreeMap<>(), caja[0]));
                    }
                    case 10 -> { // anulación repetida: 409 RECIBO_YA_ANULADO exacto
                        ReciboModelo r = anulados.get(azar.nextInt(anulados.size()));
                        assertThatThrownBy(() -> anulacionService.anular(r.id, "Otra vez, por las dudas", f.usuarioId())).as(donde)
                                .isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                                    assertThat(e.motivo()).isEqualTo(Motivo.RECIBO_YA_ANULADO);
                                    assertThat(e.motivo().estadoHttp()).isEqualTo(409);
                                });
                    }
                    case 11 -> { // cobranza repetida (misma clave y contenido): devuelve el recibo, también si está ANULADO (200)
                        ReciboModelo r = m.recibos.get(azar.nextInt(m.recibos.size()));
                        CobroRegistrado repetida = cobranzaService.cobrar(r.pedido, f.usuarioId());
                        assertThat(repetida.repetida()).as(donde).isTrue();
                        assertThat(repetida.recibo().getId()).as(donde).isEqualTo(r.id);
                        assertThat(repetida.recibo().isAnulado()).as(donde).isEqualTo(r.anulado);
                    }
                    case 12 -> { // desactivar: con saldo 0 entra; si no, 422 por saldo ≠ 0
                        if (m.saldo.signum() == 0) {
                            clienteService.cambiarActivo(clientes3[c], false);
                            m.activo = false;
                        } else {
                            assertThatThrownBy(() -> clienteService.cambiarActivo(clientes3[c], false)).as(donde)
                                    .isInstanceOf(ReglaDeNegocioException.class).hasMessage(CON_SALDO);
                        }
                    }
                    case 13 -> { // sobre-imputación: un centavo o más por encima del pendiente → 409 con la deuda actual
                        if (m.abiertos.isEmpty()) {
                            break;
                        }
                        Long comprobante = new ArrayList<>(m.abiertos.keySet()).get(azar.nextInt(m.abiertos.size()));
                        BigDecimal pendiente = m.abiertos.get(comprobante);
                        BigDecimal importe = pendiente.add(centavos(azar, 500));
                        assertThatThrownBy(() -> cobranzaService.cobrar(new CobranzaPedido(UUID.randomUUID().toString(),
                                clientes3[c], MedioPago.EFECTIVO, importe, List.of(new Imputar(comprobante, importe))), f.usuarioId()))
                                .as(donde).isInstanceOfSatisfying(CobranzaRechazadaException.class, e -> {
                                    assertThat(e.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
                                    assertThat(e.deudas().get(0).pendiente()).isEqualByComparingTo(pendiente);
                                });
                    }
                    case 14 -> { // reactivar
                        clienteService.cambiarActivo(clientes3[c], true);
                        m.activo = true;
                    }
                    default -> { // 15: anular con el cliente desactivado → 422 sin efectos (o cerrar y abrir la caja si no hay a quién)
                        if (!m.recibos.isEmpty() && m.recibos.stream().anyMatch(r -> !r.anulado)) {
                            ReciboModelo r = m.recibos.stream().filter(x -> !x.anulado).findFirst().orElseThrow();
                            assertThatThrownBy(() -> anulacionService.anular(r.id, "Con el cliente desactivado", f.usuarioId()))
                                    .as(donde).isInstanceOf(ReglaDeNegocioException.class).hasMessage(DESACTIVADO);
                        } else {
                            clienteService.cambiarActivo(clientes3[c], true);
                            m.activo = true;
                        }
                    }
                }
                if (paso % 37 == 36) { // se cierra la caja y se abre otra: los cobros anteriores quedan en una caja CERRADA
                    cajaService.cerrar(caja[0], BigDecimal.ZERO, f.usuarioId());
                    caja[0] = cajaAbiertaHoy(f.id());
                }
                for (int i = 0; i < 3; i++) {
                    assertInvariante(clientes3[i]);
                    assertThat(saldo(clientes3[i])).as("saldo del modelo del cliente %d tras %s", i, donde)
                            .isEqualByComparingTo(modelo[i].saldo);
                    assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, clientes3[i]))
                            .as("activo del cliente %d tras %s", i, donde).isEqualTo(modelo[i].activo);
                    PendientesCuenta p = servicioDeCuenta.pendientes(clientes3[i]);
                    assertThat(p.comprobantes()).as("pendientes del cliente %d tras %s", i, donde)
                            .extracting(PendientesCuenta.Pendiente::comprobanteId)
                            .containsExactlyElementsOf(modelo[i].abiertos.keySet());
                    BigDecimal sumaPendientes = BigDecimal.ZERO;
                    for (PendientesCuenta.Pendiente pendiente : p.comprobantes()) {
                        assertThat(pendiente.pendiente()).as(donde)
                                .isEqualByComparingTo(modelo[i].abiertos.get(pendiente.comprobanteId()));
                        sumaPendientes = sumaPendientes.add(pendiente.pendiente());
                    }
                    assertThat(p.saldo()).as(donde).isEqualByComparingTo(modelo[i].saldo);
                    assertThat(p.aCuenta()).as("aCuenta del cliente %d tras %s", i, donde).isEqualByComparingTo(modelo[i].aCuenta());
                    assertThat(sumaPendientes.subtract(p.aCuenta())).as("Σ pendiente − aCuenta = saldo tras %s", donde)
                            .isEqualByComparingTo(p.saldo());
                }
            } catch (AssertionError e) { // toda falla lleva la semilla y el paso: se repite con -Dcc.semilla=...
                throw new AssertionError(donde + " → " + e.getMessage(), e);
            }
        }

        long anulaciones = 0;
        for (long cliente : clientes3) {
            anulaciones += jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? "
                    + "and tipo = 'ANULACION'", Long.class, cliente);
        }
        System.out.println("propiedadDeLaAnulacion: anulaciones=" + anulaciones + " (con imputaciones " + conImputaciones
                + ", adelantos " + adelantosAnulados + ", de caja cerrada " + cajasCerradasAnuladas + "), acciones=" + java.util.Arrays.toString(hechos));
        assertThat(anulaciones).as("≥ 20 anulaciones (semilla %d)", semilla).isGreaterThanOrEqualTo(20);
        assertThat(conImputaciones).as("anulaciones de recibos con imputaciones (semilla %d)", semilla).isGreaterThan(0);
        assertThat(adelantosAnulados).as("anulaciones de adelantos (semilla %d)", semilla).isGreaterThan(0);
        assertThat(cajasCerradasAnuladas).as("anulaciones de cobros de una caja cerrada (semilla %d)", semilla).isGreaterThan(0);
        for (int accion : new int[] {0, 3, 4, 5, 6, 9, 10, 11, 12, 13}) {
            assertThat(hechos[accion]).as("la secuencia ejercitó la acción %d (semilla %d)", accion, semilla).isGreaterThan(0);
        }
    }
}
