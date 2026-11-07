package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.PlantillaDeMigracion;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V47 (arreglos-mostrador-caja, S3): quien anulo el remito y cuando. Base nueva por test en la V46, siembra por
 * JDBC y recien despues migra a la V47. CUIT: bloque 30-7899996x.
 */
class MigracionV47Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion46() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v47", "46"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarALa47() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("47").load().migrate();
    }

    @Test
    @DisplayName("los remitos de antes quedan igual, tambien los ya anulados, sin quien ni cuando")
    void losRemitosExistentesQuedanIgual() {
        long f = ferreteria("30-78999961-1");
        long emitido = remito(f, 1, "EMITIDO");
        long anulado = remito(f, 2, "ANULADO");
        Map<String, Object> emitidoAntes = jdbc.queryForMap("select * from remito where id = ?", emitido);
        Map<String, Object> anuladoAntes = jdbc.queryForMap("select * from remito where id = ?", anulado);

        migrarALa47();

        for (long id : new long[] {emitido, anulado}) {
            Map<String, Object> despues = new HashMap<>(jdbc.queryForMap("select * from remito where id = ?", id));
            assertThat(despues.remove("fecha_anulacion")).isNull();
            assertThat(despues.remove("usuario_anulacion_id")).isNull();
            assertThat(despues).isEqualTo(id == emitido ? emitidoAntes : anuladoAntes);
        }
    }

    @Test
    @DisplayName("quien y cuando van juntos, solo en un ANULADO y con un usuario de la misma ferreteria")
    void laAnulacionTieneReglas() {
        long f = ferreteria("30-78999961-2");
        long otra = ferreteria("30-78999961-3");
        long emitido = remito(f, 1, "EMITIDO");
        long anulado = remito(f, 2, "ANULADO");
        migrarALa47();
        long dueno = usuario(f);
        long ajeno = usuario(otra);
        String fijar = "update remito set fecha_anulacion = ?, usuario_anulacion_id = ? where id = ?";

        assertThatThrownBy(() -> jdbc.update(fijar, "2026-11-07 10:00:00", null, anulado))
                .hasMessageContaining("ck_remito_anulacion");
        assertThatThrownBy(() -> jdbc.update(fijar, null, dueno, anulado)).hasMessageContaining("ck_remito_anulacion");
        assertThatThrownBy(() -> jdbc.update(fijar, "2026-11-07 10:00:00", dueno, emitido))
                .hasMessageContaining("ck_remito_anulacion");
        assertThatThrownBy(() -> jdbc.update(fijar, "2026-11-07 10:00:00", ajeno, anulado))
                .hasMessageContaining("fk_remito_usuario_anulacion");
        assertThatCode(() -> jdbc.update(fijar, "2026-11-07 10:00:00", dueno, anulado)).doesNotThrowAnyException();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V47', 'DUENO')", id, "v47-" + cuit + "@test.local");
        return id;
    }

    private long usuario(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private long remito(long ferreteria, int numero, String estado) {
        jdbc.update("insert into remito (ferreteria_id, numero, clave_idempotencia, usuario_id, estado, subtotal, "
                + "descuento_porcentaje, neto, iva, total) values (?, ?, ?, ?, ?, 121, 0, 100, 21, 121)",
                ferreteria, numero, "v47-" + ferreteria + "-" + numero, usuario(ferreteria), estado);
        return jdbc.queryForObject("select id from remito where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }
}
