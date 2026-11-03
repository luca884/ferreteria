package com.ferreteria.clientes;

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
 * Migracion V19 (CL-004): la venta, el presupuesto y el remito referencian al cliente y guardan una copia de su nombre.
 * La copia admite una razon social entera (120). Usa un MySQL de Testcontainers propio y una base nueva por test.
 */
class MigracionV19Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNueva() {
        String url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v19", "19"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap(
                "select column_type, is_nullable from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE");
    }

    private String fkDe(String tabla, String columna) {
        List<Map<String, Object>> fks = jdbc.queryForList(
                "select referenced_table_name from information_schema.key_column_usage "
                        + "where table_schema = database() and table_name = ? and column_name = ? "
                        + "and referenced_table_name is not null", tabla, columna);
        return fks.isEmpty() ? null : (String) fks.get(0).get("REFERENCED_TABLE_NAME");
    }

    @Test
    @DisplayName("la venta guarda cliente_id (ahora con FK al cliente) y una copia de su nombre")
    void ventaConCliente() {
        assertThat(columna("comprobante", "cliente_id")).isEqualTo("bigint YES");
        assertThat(columna("comprobante", "cliente_nombre")).isEqualTo("varchar(120) YES");
        assertThat(fkDe("comprobante", "cliente_id")).isEqualTo("cliente");
    }

    @Test
    @DisplayName("el presupuesto y el remito suman cliente_id con FK, y su copia del nombre pasa de 80 a 120")
    void presupuestoYRemito() {
        for (String tabla : List.of("presupuesto", "remito")) {
            assertThat(columna(tabla, "cliente_id")).as(tabla).isEqualTo("bigint YES");
            assertThat(fkDe(tabla, "cliente_id")).as(tabla).isEqualTo("cliente");
            assertThat(columna(tabla, "cliente_nombre")).as(tabla).isEqualTo("varchar(120) YES");
        }
    }

    @Test
    @DisplayName("un remito no puede apuntar a un cliente que no existe")
    void remitoConClienteInexistente() {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('F', '30-70000001-1', 'RESPONSABLE_INSCRIPTO')");
        long f = jdbc.queryForObject("select id from ferreteria", Long.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, 'v19@test.local', 'x', 'D', 'DUENO')", f);
        long u = jdbc.queryForObject("select id from usuario", Long.class);

        assertThatThrownBy(() -> jdbc.update("insert into remito (ferreteria_id, numero, clave_idempotencia, usuario_id, "
                + "estado, subtotal, descuento_porcentaje, neto, iva, total, cliente_id) "
                + "values (?, 1, 'c', ?, 'EMITIDO', 121, 0, 100, 21, 121, 9999)", f, u))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("un cliente con documentos no se puede borrar de la base: solo se desactiva")
    void clienteReferenciadoNoSeBorra() {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('F', '30-70000001-1', 'RESPONSABLE_INSCRIPTO')");
        long f = jdbc.queryForObject("select id from ferreteria", Long.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, 'v19@test.local', 'x', 'D', 'DUENO')", f);
        long u = jdbc.queryForObject("select id from usuario", Long.class);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, 'Ana')", f);
        long c = jdbc.queryForObject("select id from cliente", Long.class);
        jdbc.update("insert into remito (ferreteria_id, numero, clave_idempotencia, usuario_id, estado, subtotal, "
                + "descuento_porcentaje, neto, iva, total, cliente_id) "
                + "values (?, 1, 'c', ?, 'EMITIDO', 121, 0, 100, 21, 121, ?)", f, u, c);

        assertThatThrownBy(() -> jdbc.update("delete from cliente where id = ?", c)).isInstanceOf(DataAccessException.class);
    }
}
