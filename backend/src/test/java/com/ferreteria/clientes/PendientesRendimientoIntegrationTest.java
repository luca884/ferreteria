package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CCT3-407 (CC-039, no funcional; riesgo 12.10 del design): {@code GET .../cuenta-corriente/pendientes} de un cliente con 2.000
 * comprobantes, 1.500 de ellos con imputaciones parciales, repartidas en 20 recibos. Detecta una regresión GROSERA: tras
 * calentar, cada consulta tarda menos de 2 s (el valor real se loguea; hoy es una fracción de eso). Si molestara, el índice
 * {@code (ferreteria_id, cliente_id, tipo, id)} va en un cambio aparte: acá NO se implementa. CUIT 30-8409NNNN-1.
 */
class PendientesRendimientoIntegrationTest extends CuentaCorrienteTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(PendientesRendimientoIntegrationTest.class);

    private static final int COMPROBANTES = 2000;
    private static final int CON_IMPUTACION = 1500;
    private static final int RECIBOS = 20;
    private static final Duration LIMITE = Duration.ofSeconds(2);
    private static final int CALENTAMIENTO = 3;
    private static final int MEDICIONES = 10;

    @Override
    protected String prefijoDeCuit() {
        return "30-8409";
    }

    @Test
    void pendientesDeUnClienteConDosMilComprobantes() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "100000000");
        long forma = jdbc.queryForObject(
                "select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class, f.id());

        // 2.000 comprobantes de $ 100,00 con su pago a cuenta y su VENTA del libro.
        List<Object[]> comprobantes = new ArrayList<>();
        for (int i = 1; i <= COMPROBANTES; i++) {
            comprobantes.add(new Object[] {f.id(), i, UUID.randomUUID().toString(), cliente, f.usuarioId()});
        }
        jdbc.batchUpdate("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, cliente_id, "
                + "usuario_id, neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, ?, 0, 0, 100.00, 100.00)",
                comprobantes);
        List<Long> ids = jdbc.queryForList("select id from comprobante where ferreteria_id = ? order by numero", Long.class, f.id());
        List<Object[]> pagos = new ArrayList<>();
        for (Long id : ids) {
            pagos.add(new Object[] {f.id(), id, forma});
        }
        jdbc.batchUpdate("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre) "
                + "values (?, ?, 'CUENTA_CORRIENTE', 100.00, ?, 'Cuenta corriente')", pagos);
        List<Long> pagoIds = jdbc.queryForList(
                "select id from pago where ferreteria_id = ? order by comprobante_id", Long.class, f.id());
        List<Object[]> libro = new ArrayList<>();
        for (int i = 0; i < COMPROBANTES; i++) {
            libro.add(new Object[] {f.id(), cliente, ids.get(i), pagoIds.get(i), f.usuarioId(), "Venta N.º " + (i + 1)});
        }
        jdbc.batchUpdate("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, "
                + "pago_id, fecha, usuario_id, referencia) values (?, ?, 'VENTA', 100.00, 0, ?, ?, now(6), ?, ?)", libro);
        jdbc.update("update cliente set saldo = ? where id = ?", new BigDecimal(COMPROBANTES * 100), cliente);

        // 20 recibos de $ 3.000 con su COBRO, que imputan 40 a cada uno de los primeros 1.500 comprobantes.
        long totalCobrado = 0;
        for (int r = 0; r < RECIBOS; r++) {
            long recibo = reciboSuelto(f, cliente, "3000.00");
            jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                    + "recibo_id, referencia) values (?, ?, 'COBRO', 0, 3000.00, now(6), ?, ?, 'Recibo N.º test')", f.id(),
                    cliente, f.usuarioId(), recibo);
            totalCobrado += 3000;
            List<Object[]> imputaciones = new ArrayList<>();
            for (int i = r; i < CON_IMPUTACION; i += RECIBOS) {
                imputaciones.add(new Object[] {f.id(), cliente, recibo, ids.get(i)});
            }
            jdbc.batchUpdate("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                    + "values (?, ?, ?, ?, 40.00)", imputaciones);
        }
        jdbc.update("update cliente set saldo = saldo - ? where id = ?", new BigDecimal(totalCobrado), cliente);
        assertInvariante(cliente);
        String token = login(login.dueno());
        String url = "/api/clientes/" + cliente + "/cuenta-corriente/pendientes";

        JsonNode respuesta = null;
        for (int i = 0; i < CALENTAMIENTO; i++) {
            respuesta = getJson(url, token);
        }
        List<Long> milisegundos = new ArrayList<>();
        for (int i = 0; i < MEDICIONES; i++) {
            long inicio = System.nanoTime();
            getResult(url, token).andExpect(status().isOk());
            Duration duracion = Duration.ofNanos(System.nanoTime() - inicio);
            milisegundos.add(duracion.toMillis());
            assertThat(duracion).as("consulta %d", i + 1).isLessThan(LIMITE);
        }

        Collections.sort(milisegundos);
        LOG.info("RENDIMIENTO CCT3-407 (GET pendientes, {} comprobantes, {} con imputaciones): {} consultas, mediana {} ms, "
                + "maximo {} ms (umbral {} ms)", COMPROBANTES, CON_IMPUTACION, milisegundos.size(),
                milisegundos.get(milisegundos.size() / 2), milisegundos.get(milisegundos.size() - 1), LIMITE.toMillis());
        // La respuesta es correcta, no solo rápida: 2.000 comprobantes con deuda, FIFO, y cumple I2.
        assertThat(respuesta.get("comprobantes")).hasSize(COMPROBANTES);
        assertThat(respuesta.get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("60");
        assertThat(respuesta.get("comprobantes").get(COMPROBANTES - 1).get("pendiente").decimalValue()).isEqualByComparingTo("100");
        assertThat(respuesta.get("comprobantes").get(0).get("comprobanteId").asLong()).isEqualTo(ids.get(0));
        BigDecimal pendientes = BigDecimal.ZERO;
        for (JsonNode c : respuesta.get("comprobantes")) {
            pendientes = pendientes.add(c.get("pendiente").decimalValue());
        }
        assertThat(pendientes.subtract(respuesta.get("aCuenta").decimalValue()))
                .isEqualByComparingTo(respuesta.get("saldo").decimalValue());
    }
}
