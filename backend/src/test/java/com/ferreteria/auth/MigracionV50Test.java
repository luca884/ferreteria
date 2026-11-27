package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.PlantillaDeMigracion;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigracionV50Test {

    @Test
    void v50MarcaSoloALaDemoExistenteSinCambiarCredenciales() {
        String url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v50", "49"));
        String password = PlantillaDeMigracion.MYSQL.getPassword();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", password));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('Prueba V50', '30-78999950-1', "
                + "'RESPONSABLE_INSCRIPTO')");
        long ferreteria = jdbc.queryForObject("select id from ferreteria where cuit = '30-78999950-1'", Long.class);
        String demo = "demo@prueba.test";
        String otro = "otra@prueba.test";
        crearDueno(jdbc, ferreteria, demo);
        crearDueno(jdbc, ferreteria, otro);
        var demoAntes = usuario(jdbc, demo);
        var otroAntes = usuario(jdbc, otro);

        Flyway.configure().dataSource(url, "root", password).locations("classpath:db/migration").target("50").load().migrate();

        assertThat(jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, column_default as defecto "
                + "from information_schema.columns where table_schema = database() and table_name = 'usuario' "
                + "and column_name = 'cuenta_demo'"))
                .containsEntry("tipo", "tinyint(1)").containsEntry("nulo", "NO").containsEntry("defecto", "0");
        assertThat(marca(jdbc, demo)).isTrue();
        assertThat(marca(jdbc, otro)).isFalse();
        var demoDespues = usuario(jdbc, demo);
        demoDespues.remove("cuenta_demo");
        assertThat(demoDespues).isEqualTo(demoAntes);
        var otroDespues = usuario(jdbc, otro);
        otroDespues.remove("cuenta_demo");
        assertThat(otroDespues).isEqualTo(otroAntes);

        String nuevo = "nuevo@prueba.test";
        crearDueno(jdbc, ferreteria, nuevo);
        assertThat(marca(jdbc, nuevo)).isFalse();
    }

    private static void crearDueno(JdbcTemplate jdbc, long ferreteria, String email) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, 'Dueño', 'DUENO')",
                ferreteria, email, UsuariosTestBase.HASH_DEMO);
    }

    private static Map<String, Object> usuario(JdbcTemplate jdbc, String email) {
        return new HashMap<>(jdbc.queryForMap("select * from usuario where email = ?", email));
    }

    private static boolean marca(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("select cuenta_demo from usuario where email = ?", Boolean.class, email);
    }
}
