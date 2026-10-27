package com.ferreteria.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V30: usuario.debe_cambiar_contrasena BOOLEAN NOT NULL DEFAULT FALSE. Aditiva: los usuarios que ya existen
 * quedan sin marca (nadie tiene que cambiar nada al desplegar). Usa un MySQL de Testcontainers propio y una base nueva
 * por test, con las migraciones hasta la V29 aplicadas. CUIT: bloque 30-8530NNNN-1.
 */
class MigracionV30Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private String url;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void arrancar() {
        MYSQL.start();
    }

    @AfterAll
    static void parar() {
        MYSQL.stop();
    }

    @BeforeEach
    void baseNuevaEnLaVersion29() throws SQLException {
        String base = "v30_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        migrarHasta("29");
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    @Test
    @DisplayName("los usuarios que ya existían, Dueño, Vendedor y super admin, quedan sin marca")
    void losExistentesQuedanSinMarca() {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('Ferreteria V30', '30-85300001-1', "
                + "'RESPONSABLE_INSCRIPTO')");
        long ferreteria = jdbc.queryForObject("select id from ferreteria where cuit = '30-85300001-1'", Long.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values "
                + "(?, 'dueno-v30@test.local', 'x', 'Dueño', 'DUENO'), (?, 'vendedor-v30@test.local', 'x', 'Vendedor', 'VENDEDOR'), "
                + "(null, 'admin-v30@test.local', 'x', 'Admin', 'SUPER_ADMIN')", ferreteria, ferreteria);

        migrarHasta("30");

        assertThat(jdbc.queryForList("select email, debe_cambiar_contrasena marca from usuario "
                + "where email like '%-v30@test.local'")).hasSize(3)
                .allSatisfy(fila -> assertThat(fila.get("marca")).as("%s", fila.get("email")).isIn(false, 0, 0L));
    }

    @Test
    @DisplayName("la columna es BOOLEAN NOT NULL con default FALSE: un alta por SQL sin la columna queda sin marca")
    void columnaNoNulaConDefaultFalso() {
        migrarHasta("30");

        Map<String, Object> columna = jdbc.queryForMap("select column_type tipo, is_nullable nulo, column_default defecto "
                + "from information_schema.columns where table_schema = database() and table_name = 'usuario' "
                + "and column_name = 'debe_cambiar_contrasena'");
        assertThat(columna.get("tipo")).isEqualTo("tinyint(1)");
        assertThat(columna.get("nulo")).isEqualTo("NO");
        assertThat(columna.get("defecto")).isEqualTo("0");
    }
}
