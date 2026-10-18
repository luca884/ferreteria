package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Migracion V4 (CU-07): comprobante, item_comprobante, pago, movimiento_stock y numerador_comprobante.
 * El arranque del contexto ES la validacion de Hibernate (ddl-auto=validate): si algun mapeo no
 * coincide con Flyway, ningun test de esta clase corre.
 */
class MigracionV4Test extends IntegrationTestBase {

    private static final List<String> TABLAS = List.of(
            "comprobante", "item_comprobante", "pago", "movimiento_stock", "numerador_comprobante");

    /** tabla.columna -> tipo y nulabilidad ("NOT NULL" o "NULL"), tal como los informa information_schema. */
    private static final Map<String, String> COLUMNAS = Map.ofEntries(
            Map.entry("comprobante.id", "bigint NOT NULL"),
            Map.entry("comprobante.ferreteria_id", "bigint NOT NULL"),
            Map.entry("comprobante.tipo", "varchar(20) NOT NULL"),
            Map.entry("comprobante.estado", "varchar(20) NOT NULL"),
            Map.entry("comprobante.numero", "bigint NOT NULL"),
            Map.entry("comprobante.clave_idempotencia", "varchar(36) NOT NULL"),
            Map.entry("comprobante.cliente_id", "bigint NULL"),
            Map.entry("comprobante.usuario_id", "bigint NOT NULL"),
            // V10 (CU-10): la caja de la venta; nula en las ventas anteriores a la caja.
            Map.entry("comprobante.caja_id", "bigint NULL"),
            Map.entry("comprobante.fecha", "datetime(6) NOT NULL"),
            Map.entry("comprobante.neto", "decimal(15,2) NOT NULL"),
            Map.entry("comprobante.iva", "decimal(15,2) NOT NULL"),
            Map.entry("comprobante.total", "decimal(15,2) NOT NULL"),
            // V11 (CU-07 corte 2): subtotal antes de descuentos y descuento global en %.
            Map.entry("comprobante.subtotal", "decimal(15,2) NOT NULL"),
            Map.entry("comprobante.descuento_porcentaje", "decimal(5,2) NOT NULL"),
            // V13 (CU-07, GDS-11): el recargo de la forma de pago, en el comprobante, en cada renglon y en cada pago.
            Map.entry("comprobante.recargo", "decimal(15,2) NOT NULL"),
            // V15 (CU-07, GDS-13): el presupuesto del que salio la venta; nulo en las demas.
            Map.entry("comprobante.presupuesto_id", "bigint NULL"),
            // V17 (CU-07, RM-006): el remito del que salio la venta; nulo en las demas.
            Map.entry("comprobante.remito_id", "bigint NULL"),
            Map.entry("item_comprobante.recargo", "decimal(15,2) NOT NULL"),
            Map.entry("item_comprobante.id", "bigint NOT NULL"),
            Map.entry("item_comprobante.ferreteria_id", "bigint NOT NULL"),
            Map.entry("item_comprobante.comprobante_id", "bigint NOT NULL"),
            Map.entry("item_comprobante.renglon", "int NOT NULL"),
            Map.entry("item_comprobante.articulo_id", "bigint NOT NULL"),
            Map.entry("item_comprobante.descripcion", "varchar(200) NOT NULL"),
            Map.entry("item_comprobante.unidad", "varchar(10) NOT NULL"),
            Map.entry("item_comprobante.cantidad", "decimal(15,3) NOT NULL"),
            Map.entry("item_comprobante.precio_unitario", "decimal(15,2) NOT NULL"),
            Map.entry("item_comprobante.alicuota_iva", "varchar(10) NOT NULL"),
            Map.entry("item_comprobante.total", "decimal(15,2) NOT NULL"),
            Map.entry("item_comprobante.bruto", "decimal(15,2) NOT NULL"),
            Map.entry("item_comprobante.descuento_porcentaje", "decimal(5,2) NOT NULL"),
            Map.entry("item_comprobante.neto", "decimal(15,2) NOT NULL"),
            Map.entry("item_comprobante.iva", "decimal(15,2) NOT NULL"),
            Map.entry("pago.id", "bigint NOT NULL"),
            Map.entry("pago.ferreteria_id", "bigint NOT NULL"),
            Map.entry("pago.comprobante_id", "bigint NOT NULL"),
            Map.entry("pago.medio", "varchar(20) NOT NULL"),
            Map.entry("pago.monto", "decimal(15,2) NOT NULL"),
            Map.entry("pago.forma_pago_id", "bigint NOT NULL"),
            Map.entry("pago.forma_pago_nombre", "varchar(60) NOT NULL"),
            Map.entry("pago.recargo_porcentaje", "decimal(5,2) NOT NULL"),
            Map.entry("pago.recargo", "decimal(15,2) NOT NULL"),
            Map.entry("movimiento_stock.id", "bigint NOT NULL"),
            Map.entry("movimiento_stock.ferreteria_id", "bigint NOT NULL"),
            Map.entry("movimiento_stock.articulo_id", "bigint NOT NULL"),
            Map.entry("movimiento_stock.tipo", "varchar(20) NOT NULL"),
            Map.entry("movimiento_stock.cantidad", "decimal(15,3) NOT NULL"),
            Map.entry("movimiento_stock.comprobante_id", "bigint NULL"),
            // V16 (CU-07, RM-003): el remito que origino el movimiento; nulo en los demas.
            Map.entry("movimiento_stock.remito_id", "bigint NULL"),
            Map.entry("movimiento_stock.usuario_id", "bigint NOT NULL"),
            Map.entry("movimiento_stock.motivo", "varchar(200) NULL"),
            Map.entry("movimiento_stock.fecha", "datetime(6) NOT NULL"),
            Map.entry("numerador_comprobante.id", "bigint NOT NULL"),
            Map.entry("numerador_comprobante.ferreteria_id", "bigint NOT NULL"),
            Map.entry("numerador_comprobante.ultimo_numero", "bigint NOT NULL"));

    @Test
    @DisplayName("cada columna tiene el tipo y la nulabilidad del diseno (importes 15,2; cantidades 15,3)")
    void tiposYNulabilidad() {
        for (Map.Entry<String, String> esperada : COLUMNAS.entrySet()) {
            String[] partes = esperada.getKey().split("\\.");
            assertThat(tipoYNulabilidad(partes[0], partes[1])).as(esperada.getKey()).isEqualTo(esperada.getValue());
        }
    }

    @Test
    @DisplayName("no hay columnas de mas: cada tabla tiene exactamente las del diseno")
    void sinColumnasDeMas() {
        for (String tabla : TABLAS) {
            long esperadas = COLUMNAS.keySet().stream().filter(k -> k.startsWith(tabla + ".")).count();
            Integer reales = jdbc.queryForObject(
                    "select count(*) from information_schema.columns where table_schema = database() and table_name = ?",
                    Integer.class, tabla);
            assertThat(reales).as("columnas de " + tabla).isEqualTo((int) esperadas);
        }
    }

    @Test
    @DisplayName("tipo, estado y medio son VARCHAR sin CHECK (la fase 5 suma valores); solo los descuentos de V11 y los recargos de V13 tienen el suyo")
    void sinCheckEnTipoEstadoYMedio() {
        Integer checks = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where table_schema = database() and constraint_type = 'CHECK' and table_name in "
                        + "('comprobante','item_comprobante','pago','movimiento_stock','numerador_comprobante') "
                        + "and constraint_name not in ('ck_comprobante_descuento','ck_item_comprobante_descuento',"
                        + "'ck_comprobante_recargo','ck_item_comprobante_recargo','ck_pago_recargo')",
                Integer.class);

        assertThat(checks).isZero();
    }

    @Test
    @DisplayName("los indices unicos y de consulta tienen las columnas del diseno")
    void indicesDelDiseno() {
        assertThat(columnasDelIndice("comprobante", "uk_comprobante_numero")).containsExactly("ferreteria_id", "numero");
        assertThat(columnasDelIndice("comprobante", "uk_comprobante_clave"))
                .containsExactly("ferreteria_id", "clave_idempotencia");
        assertThat(columnasDelIndice("comprobante", "ix_comprobante_fecha")).containsExactly("ferreteria_id", "fecha");
        assertThat(columnasDelIndice("item_comprobante", "ix_item_comprobante"))
                .containsExactly("ferreteria_id", "comprobante_id", "renglon");
        assertThat(columnasDelIndice("pago", "ix_pago_comprobante")).containsExactly("ferreteria_id", "comprobante_id");
        assertThat(columnasDelIndice("movimiento_stock", "ix_movimiento_articulo"))
                .containsExactly("ferreteria_id", "articulo_id", "fecha");
        assertThat(columnasDelIndice("numerador_comprobante", "uk_numerador_ferreteria"))
                .containsExactly("ferreteria_id");
    }

    @Test
    @DisplayName("todo indice propio (uk_ / ix_) empieza por ferreteria_id")
    void losIndicesPropiosEmpiezanPorFerreteria() {
        List<Map<String, Object>> propios = jdbc.queryForList(
                "select table_name, index_name, column_name from information_schema.statistics "
                        + "where table_schema = database() and seq_in_index = 1 "
                        + "and table_name in ('comprobante','item_comprobante','pago','movimiento_stock','numerador_comprobante') "
                        + "and (index_name like 'uk\\_%' or index_name like 'ix\\_%') "
                        + "and index_name not in ('uk_comprobante_presupuesto', 'uk_comprobante_remito')");

        assertThat(propios).hasSizeGreaterThanOrEqualTo(7);
        for (Map<String, Object> indice : propios) {
            assertThat(indice.get("COLUMN_NAME")).as("primera columna de " + indice.get("INDEX_NAME"))
                    .isEqualTo("ferreteria_id");
        }
    }

    @Test
    @DisplayName("dos comprobantes de la misma ferreteria con la misma clave: el indice lo rechaza")
    void laClaveEsUnicaPorFerreteria() {
        Long f = ferreteriaId("30-79400001-1", "Migracion V4 clave");
        Long u = usuarioId(f, "v4-clave@test.local");
        String clave = "11111111-1111-4111-8111-111111111111";
        insertarComprobante(f, u, 1, clave);

        assertThatThrownBy(() -> insertarComprobante(f, u, 2, clave)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("dos comprobantes de la misma ferreteria con el mismo numero: el indice lo rechaza")
    void elNumeroEsUnicoPorFerreteria() {
        Long f = ferreteriaId("30-79400002-2", "Migracion V4 numero");
        Long u = usuarioId(f, "v4-numero@test.local");
        insertarComprobante(f, u, 7, "22222222-2222-4222-8222-222222222222");

        assertThatThrownBy(() -> insertarComprobante(f, u, 7, "33333333-3333-4333-8333-333333333333"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("otra ferreteria puede usar la misma clave y el mismo numero")
    void claveYNumeroSeRepitenEntreFerreterias() {
        Long a = ferreteriaId("30-79400003-3", "Migracion V4 A");
        Long b = ferreteriaId("30-79400004-4", "Migracion V4 B");
        Long ua = usuarioId(a, "v4-a@test.local");
        Long ub = usuarioId(b, "v4-b@test.local");
        String clave = "44444444-4444-4444-8444-444444444444";

        insertarComprobante(a, ua, 1, clave);
        insertarComprobante(b, ub, 1, clave);

        Integer cuantos = jdbc.queryForObject(
                "select count(*) from comprobante where clave_idempotencia = ?", Integer.class, clave);
        assertThat(cuantos).isEqualTo(2);
    }

    @Test
    @DisplayName("el numerador es una fila por ferreteria")
    void unNumeradorPorFerreteria() {
        Long f = ferreteriaId("30-79400005-5", "Migracion V4 numerador");
        jdbc.update("insert into numerador_comprobante (ferreteria_id, ultimo_numero) values (?, 0)", f);

        assertThatThrownBy(() -> jdbc.update(
                "insert into numerador_comprobante (ferreteria_id, ultimo_numero) values (?, 5)", f))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("no existen la tabla cliente ni campos de CAE (caja_id llego con V10, CU-10)")
    void sinClienteNiCae() {
        Integer cajaOCae = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() "
                        + "and table_name in ('comprobante','item_comprobante','pago','movimiento_stock','numerador_comprobante') "
                        + "and column_name like 'cae%'",
                Integer.class);
        Integer tablaCliente = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = database() and table_name = 'cliente'",
                Integer.class);

        assertThat(cajaOCae).isZero();
        assertThat(tablaCliente).isZero();
    }

    @Test
    @DisplayName("cliente_id es nulo (Consumidor Final) y no tiene FK: la tabla cliente es del corte 3")
    void clienteIdSinFk() {
        Integer fks = jdbc.queryForObject(
                "select count(*) from information_schema.key_column_usage where table_schema = database() "
                        + "and table_name = 'comprobante' and column_name = 'cliente_id' and referenced_table_name is not null",
                Integer.class);

        assertThat(fks).isZero();
    }

    // ---- V1003 (T-06): movimientos de los articulos demo ----

    private static final String IDS_DEMO = "(1, 2, 3, 4, 5, 6, 7, 8)";

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("el articulo demo con stock 500 tiene un AJUSTE de +500 \"Stock inicial demo\" a nombre del dueno")
    void elArticuloDemoConStockTieneSuAjusteInicial() {
        List<Map<String, Object>> movimientos = jdbc.queryForList(
                "select * from movimiento_stock where articulo_id = 1");

        assertThat(movimientos).hasSize(1);
        Map<String, Object> ajuste = movimientos.get(0);
        assertThat(ajuste.get("TIPO")).isEqualTo("AJUSTE");
        assertThat((BigDecimal) ajuste.get("CANTIDAD")).isEqualByComparingTo("500");
        assertThat(ajuste.get("MOTIVO")).isEqualTo("Stock inicial demo");
        assertThat(ajuste.get("COMPROBANTE_ID")).isNull();
        assertThat(ajuste.get("FERRETERIA_ID")).isEqualTo(1L);
        Long dueno = jdbc.queryForObject(
                "select min(id) from usuario where ferreteria_id = 1 and rol = 'DUENO'", Long.class);
        assertThat(ajuste.get("USUARIO_ID")).isEqualTo(dueno);
    }

    @Test
    @DisplayName("el articulo demo con stock 0 (el 5) no tiene movimiento")
    void elArticuloDemoSinStockNoTieneMovimiento() {
        assertThat(jdbc.queryForObject(
                "select stock_actual from articulo where id = 5", BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject(
                "select count(*) from movimiento_stock where articulo_id = 5", Integer.class)).isZero();
        // Los otros siete si: el vacio de arriba viene de la regla stock <> 0, no de que el script no corrio.
        assertThat(jdbc.queryForObject(
                "select count(*) from movimiento_stock where articulo_id in (1, 2, 3, 4, 6, 7, 8) and tipo = 'AJUSTE'",
                Integer.class)).isEqualTo(7);
    }

    @Test
    @DisplayName("correr V1003 de nuevo no duplica los AJUSTE (NOT EXISTS)")
    void v1003EsReejecutable() {
        Integer antes = jdbc.queryForObject(
                "select count(*) from movimiento_stock where articulo_id in " + IDS_DEMO + " and tipo = 'AJUSTE'",
                Integer.class);

        new ResourceDatabasePopulator(new ClassPathResource("db/dev-data/V1003__movimientos_demo.sql"))
                .execute(dataSource);

        Integer despues = jdbc.queryForObject(
                "select count(*) from movimiento_stock where articulo_id in " + IDS_DEMO + " and tipo = 'AJUSTE'",
                Integer.class);
        assertThat(antes).isEqualTo(7);
        assertThat(despues).isEqualTo(7);
    }

    @Test
    @DisplayName("cache = suma de movimientos para los articulos demo 1 a 8")
    void elCacheEsLaSumaDeMovimientosEnLosDemo() {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select a.id, a.stock_actual, coalesce((select sum(m.cantidad) from movimiento_stock m "
                        + "where m.articulo_id = a.id), 0) as suma from articulo a where a.id in " + IDS_DEMO);

        assertThat(filas).hasSize(8);
        for (Map<String, Object> fila : filas) {
            assertThat((BigDecimal) fila.get("STOCK_ACTUAL")).as("articulo " + fila.get("ID"))
                    .isEqualByComparingTo((BigDecimal) fila.get("SUMA"));
        }
    }

    private void insertarComprobante(Long ferreteriaId, Long usuarioId, long numero, String clave) {
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, "
                + "neto, iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 0, 0, 0, 0)",
                ferreteriaId, numero, clave, usuarioId);
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList(
                "select column_name from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ? "
                        + "order by seq_in_index",
                String.class, tabla, indice);
    }

    private String tipoYNulabilidad(String tabla, String columna) {
        Map<String, Object> fila = jdbc.queryForMap(
                "select column_type, is_nullable from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?",
                tabla, columna);
        return fila.get("COLUMN_TYPE") + ("NO".equals(fila.get("IS_NULLABLE")) ? " NOT NULL" : " NULL");
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long usuarioId(Long ferreteriaId, String email) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Usuario test', 'DUENO')", ferreteriaId, email);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }
}
