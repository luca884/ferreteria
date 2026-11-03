package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V16 (CU-07, G55, RM-001 y RM-003): las tablas del remito, su numerador propio y el remito que origina un
 * movimiento de stock. Usa un MySQL de Testcontainers propio y una base nueva por test.
 */
class MigracionV16Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNueva() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v16", "16"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V16', 'DUENO')", id, "v16-" + cuit + "@test.local");
        return id;
    }

    private void remito(long ferreteria, long numero, String clave) {
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into remito (ferreteria_id, numero, clave_idempotencia, usuario_id, estado, "
                + "subtotal, descuento_porcentaje, neto, iva, total) "
                + "values (?, ?, ?, ?, 'EMITIDO', 121, 0, 100, 21, 121)", ferreteria, numero, clave, usuario);
    }

    @Test
    @DisplayName("las columnas del remito: tipos, nulabilidad y defaults")
    void columnasDelRemito() {
        Map<String, String> esperadas = Map.ofEntries(
                Map.entry("remito.numero", "bigint NO null"),
                Map.entry("remito.clave_idempotencia", "varchar(36) NO null"),
                Map.entry("remito.estado", "varchar(20) NO null"),
                Map.entry("remito.cliente_nombre", "varchar(80) YES null"),
                Map.entry("remito.observaciones", "varchar(200) YES null"),
                Map.entry("remito.subtotal", "decimal(15,2) NO null"),
                Map.entry("remito.descuento_porcentaje", "decimal(5,2) NO null"),
                Map.entry("remito.total", "decimal(15,2) NO null"),
                Map.entry("item_remito.cantidad", "decimal(15,3) NO null"),
                Map.entry("item_remito.precio_unitario", "decimal(15,2) NO null"),
                Map.entry("item_remito.alicuota_iva", "varchar(10) NO null"),
                Map.entry("item_remito.descuento_porcentaje", "decimal(5,2) NO null"),
                Map.entry("item_remito.descripcion", "varchar(200) NO null"),
                Map.entry("numerador_remito.ultimo_numero", "bigint NO null"),
                Map.entry("movimiento_stock.remito_id", "bigint YES null"));
        for (Map.Entry<String, String> e : esperadas.entrySet()) {
            String[] tablaColumna = e.getKey().split("\\.");
            Map<String, Object> fila = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, "
                    + "column_default as defecto from information_schema.columns where table_schema = database() "
                    + "and table_name = ? and column_name = ?", tablaColumna[0], tablaColumna[1]);
            assertThat(fila.get("tipo") + " " + fila.get("nulo") + " " + fila.get("defecto")).as(e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("el numero y la clave son unicos por ferreteria, y otra ferreteria puede repetirlos")
    void numeroYClaveUnicosPorFerreteria() {
        long a = ferreteria("30-71600001-1");
        long b = ferreteria("30-71600002-2");
        remito(a, 1, "clave-1");

        assertThatThrownBy(() -> remito(a, 1, "clave-2")).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_remito_numero");
        assertThatThrownBy(() -> remito(a, 2, "clave-1")).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_remito_clave");
        remito(b, 1, "clave-1");

        assertThat(jdbc.queryForObject("select count(*) from remito", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("un renglon no puede apuntar a un remito ni a un articulo que no existen")
    void renglonConReferenciasValidas() {
        long f = ferreteria("30-71600003-3");

        assertThatThrownBy(() -> jdbc.update("insert into item_remito (ferreteria_id, remito_id, renglon, "
                + "articulo_id, descripcion, unidad, cantidad, precio_unitario, alicuota_iva, bruto, "
                + "descuento_porcentaje, total, neto, iva) values (?, 999999, 1, 999999, 'x', 'UNIDAD', 1, 121, "
                + "'IVA_21', 121, 0, 121, 100, 21)", f)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_item_remito_remito");
    }

    @Test
    @DisplayName("un movimiento de stock no puede apuntar a un remito que no existe")
    void movimientoConRemitoValido() {
        long f = ferreteria("30-71600004-4");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                + "stock_actual) values (?, 'v16-tornillo', 'Tornillo', 100.00, 170.00, 'UNIDAD', 10)", f);
        long articulo = jdbc.queryForObject("select max(id) from articulo where ferreteria_id = ?", Long.class, f);

        assertThatThrownBy(() -> jdbc.update("insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, "
                + "remito_id, usuario_id) values (?, ?, 'REMITO', -1, 999999, ?)", f, articulo, usuario))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_movimiento_remito");
    }

    @Test
    @DisplayName("el numerador es uno por ferreteria")
    void numeradorUnoPorFerreteria() {
        long f = ferreteria("30-71600005-5");
        jdbc.update("insert into numerador_remito (ferreteria_id, ultimo_numero) values (?, 1)", f);

        assertThatThrownBy(() -> jdbc.update("insert into numerador_remito (ferreteria_id, ultimo_numero) "
                + "values (?, 2)", f)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_numerador_remito_ferreteria");
    }

    @Test
    @DisplayName("los remitos se buscan por indices que empiezan por la ferreteria")
    void indices() {
        List<String> porEstado = jdbc.queryForList("select column_name from information_schema.statistics "
                + "where table_schema = database() and table_name = 'remito' and index_name = 'ix_remito_estado' "
                + "order by seq_in_index", String.class);
        List<String> renglones = jdbc.queryForList("select column_name from information_schema.statistics "
                + "where table_schema = database() and table_name = 'item_remito' "
                + "and index_name = 'ix_item_remito' order by seq_in_index", String.class);

        assertThat(porEstado).containsExactly("ferreteria_id", "estado", "fecha");
        assertThat(renglones).containsExactly("ferreteria_id", "remito_id", "renglon");
    }
}
