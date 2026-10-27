package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * El MySQL de los tests corre con los valores de fabrica de mysql:8.4. MySQLContainer le copia por defecto el my.cnf de
 * Testcontainers (mysql-default-conf), que achica max_allowed_packet a 1 MB y el buffer pool a 16 MB;
 * src/test/resources/mysql-default-conf/my.cnf lo tapa con uno sin directivas.
 */
class MySQLDeLosTestsTest extends IntegrationTestBase {

    @Test
    void usaLosValoresDeFabricaDeMysql84() {
        tieneLosValoresDeFabrica(jdbc);
    }

    /** Los MigracionV*Test y otros levantan su propio MySQLContainer: el my.cnf del proyecto tambien les llega. */
    @Test
    void unMySQLContainerPropioTambienLosUsa() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            tieneLosValoresDeFabrica(new JdbcTemplate(
                new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())));
        }
    }

    private static void tieneLosValoresDeFabrica(JdbcTemplate mysql) {
        assertThat(mysql.queryForObject("SELECT @@max_allowed_packet", Long.class)).isEqualTo(64L * 1024 * 1024);
        assertThat(mysql.queryForObject("SELECT @@innodb_buffer_pool_size", Long.class)).isEqualTo(128L * 1024 * 1024);
    }
}
