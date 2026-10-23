package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * R-005, R-006: los dos {@code FOR UPDATE} nativos de la cobranza fijan su índice con {@code force index} para que el plan no
 * dependa del tamaño de la tabla. {@code bloquearPartidas} va por la PRIMARY (si el optimizador eligiera {@code ix_mcc_cliente}
 * o {@code ix_mcc_comprobante} tomaría next-key y gap locks y frenaría las ventas a cuenta de OTROS clientes) e
 * {@code imputadoBloqueado} por {@code ix_imputacion_cliente} (y no por alguna de las {@code fk_imputacion_*}). El texto de la
 * {@code @Query} es la prueba determinista; el {@code EXPLAIN} de la consulta REAL (con literales) confirma el plan contra
 * MySQL real y un volumen moderado. CUIT 30-8601NNNN-1.
 */
class PlanDeLocksDeCobranzaTest extends CuentaCorrienteTestBase {

    @Override
    protected String prefijoDeCuit() {
        return "30-8601";
    }

    /** El texto de la {@code @Query} del método, tal cual lo ejecuta Spring Data (nativo: va a MySQL sin traducir). */
    private static String consultaDe(Class<?> repositorio, String metodo) {
        for (Method m : repositorio.getDeclaredMethods()) {
            if (m.getName().equals(metodo) && m.isAnnotationPresent(Query.class)) {
                return m.getAnnotation(Query.class).value();
            }
        }
        throw new AssertionError("no hay una consulta " + repositorio.getSimpleName() + "." + metodo);
    }

    private static String literales(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Texto de la consulta (determinista)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R-005: bloquearPartidas fija la PRIMARY con force index justo después de la tabla")
    void bloquearPartidasLlevaForceIndexPrimary() {
        String consulta = consultaDe(MovimientoCuentaClienteRepository.class, "bloquearPartidas");

        assertThat(consulta).contains("from movimiento_cuenta_cliente force index (primary) where");
        assertThat(consulta).endsWith(" for update");
    }

    @Test
    @DisplayName("R-006: imputadoBloqueado fija ix_imputacion_cliente con force index justo después de la tabla")
    void imputadoBloqueadoLlevaForceIndexDelCliente() {
        String consulta = consultaDe(ImputacionRepository.class, "imputadoBloqueado");

        assertThat(consulta).contains("from imputacion force index (ix_imputacion_cliente) where");
        assertThat(consulta).endsWith(" for update");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Plan (EXPLAIN de la consulta real, contra MySQL real)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R-005: el EXPLAIN de bloquearPartidas (ids literales, libro de ~4.000 filas de dos clientes intercalados) usa la PRIMARY")
    void planDeBloquearPartidasUsaPrimary() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "100000");
        long otro = cliente(f, "100000");
        String insert = "insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, referencia, motivo, "
                + "fecha, usuario_id, ajuste_id) values (?, ?, 'AJUSTE', ?, 0, ?, 'Ajuste de prueba', ?, ?, ?)";
        for (int tanda = 0; tanda < 3; tanda++) {
            ventaEnLibro(f, cliente, "10.00"); // las partidas a bloquear, repartidas entre el ruido
            List<Object[]> filas = new ArrayList<>();
            // V28: cada AJUSTE del libro lleva su ajuste
            java.util.Iterator<Long> ajustesCliente = SemillaAjuste.ajustes(jdbc, f.id(), cliente, f.usuarioId(), "RECARGO",
                    java.util.Collections.nCopies(350, new BigDecimal("1.00"))).iterator();
            java.util.Iterator<Long> ajustesOtro = SemillaAjuste.ajustes(jdbc, f.id(), otro, f.usuarioId(), "RECARGO",
                    java.util.Collections.nCopies(350, new BigDecimal("1.00"))).iterator();
            for (int i = 0; i < 350; i++) {
                LocalDateTime fecha = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(tanda * 350L + i);
                filas.add(new Object[] {f.id(), cliente, new BigDecimal("1.00"), "Mov " + i, fecha, f.usuarioId(),
                        ajustesCliente.next()});
                filas.add(new Object[] {f.id(), otro, new BigDecimal("1.00"), "Otro " + i, fecha, f.usuarioId(),
                        ajustesOtro.next()});
            }
            jdbc.batchUpdate(insert, filas);
        }
        jdbc.execute("analyze table movimiento_cuenta_cliente");
        List<Long> ids = jdbc.queryForList(
                "select id from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'VENTA' order by id", Long.class,
                cliente);
        assertThat(ids).hasSize(3);

        String sql = consultaDe(MovimientoCuentaClienteRepository.class, "bloquearPartidas").replace(":ids", literales(ids))
                .replace(":fid", String.valueOf(f.id())).replace(":clienteId", String.valueOf(cliente));
        List<Map<String, Object>> plan = jdbc.queryForList("explain " + sql);

        assertThat(plan).as("plan de bloquearPartidas: %s", plan).anySatisfy(fila -> {
            assertThat(String.valueOf(fila.get("table"))).isEqualTo("movimiento_cuenta_cliente");
            assertThat(String.valueOf(fila.get("key"))).isEqualTo("PRIMARY");
        });
    }

    @Test
    @DisplayName("R-006: el EXPLAIN de imputadoBloqueado (comprobantes literales, 400 imputaciones de dos clientes intercalados) usa ix_imputacion_cliente")
    void planDeImputadoBloqueadoUsaIxImputacionCliente() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "100000");
        long otro = cliente(f, "100000");
        long reciboCliente = reciboSuelto(f, cliente, "100.00");
        long reciboOtro = reciboSuelto(f, otro, "100.00");
        List<Long> deCliente = comprobantesSembrados(f, cliente, 200);
        List<Long> deOtro = comprobantesSembrados(f, otro, 200);
        List<Object[]> imputaciones = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            imputaciones.add(new Object[] {f.id(), cliente, reciboCliente, deCliente.get(i), new BigDecimal("0.50")});
            imputaciones.add(new Object[] {f.id(), otro, reciboOtro, deOtro.get(i), new BigDecimal("0.50")});
        }
        jdbc.batchUpdate("insert into imputacion (ferreteria_id, cliente_id, recibo_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, ?)", imputaciones);
        jdbc.execute("analyze table imputacion");

        String sql = consultaDe(ImputacionRepository.class, "imputadoBloqueado")
                .replace(":comprobanteIds", literales(deCliente.subList(0, 5))).replace(":fid", String.valueOf(f.id()))
                .replace(":clienteId", String.valueOf(cliente));
        List<Map<String, Object>> plan = jdbc.queryForList("explain " + sql);

        assertThat(plan).as("plan de imputadoBloqueado: %s", plan).anySatisfy(fila -> {
            assertThat(String.valueOf(fila.get("table"))).isEqualTo("imputacion");
            assertThat(String.valueOf(fila.get("key"))).isEqualTo("ix_imputacion_cliente");
        });
    }

    /** {@code cantidad} comprobantes de venta del cliente, sembrados por lote, con sus ids en orden. */
    private List<Long> comprobantesSembrados(Ferreteria f, long clienteId, int cantidad) {
        List<Object[]> filas = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            // numero único por ferretería (uk_comprobante_numero), lejos de la secuencia de los helpers y de un cliente al otro
            filas.add(new Object[] {f.id(), NUMERO_BASE_SEMBRADO + 500_000L + 1_000L * (clienteId % 1_000) + i,
                    UUID.randomUUID().toString(), clienteId, f.usuarioId()});
        }
        jdbc.batchUpdate("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, cliente_id, "
                + "usuario_id, neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, ?, 0, 0, 10, 10)",
                filas);
        return jdbc.queryForList("select id from comprobante where ferreteria_id = ? and cliente_id = ? order by id",
                Long.class, f.id(), clienteId);
    }
}
