package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.PlantillaDeMigracion;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V48 (totp-super-admin): el código de la app del super admin en {@code usuario}. Base nueva por test en la V47,
 * siembra por JDBC y recien despues migra a la V48. CUIT: bloque 30-7899997x.
 */
class MigracionV48Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final String RESET = "update usuario set totp_secreto = null, totp_activado_en = null, "
            + "totp_ultimo_paso = null where id = ?";

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion47() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v48", "47"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarALa48() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("48").load().migrate();
    }

    @Test
    @DisplayName("usuario suma el secreto cifrado (VARBINARY), cuándo se activó y el último paso usado, todos NULL sin default")
    void columnasNuevas() {
        migrarALa48();

        assertThat(columna("totp_secreto")).containsEntry("tipo", "varbinary(64)").containsEntry("nulo", "YES")
                .containsEntry("defecto", null);
        assertThat(columna("totp_activado_en")).containsEntry("tipo", "datetime(6)").containsEntry("nulo", "YES")
                .containsEntry("defecto", null);
        assertThat(columna("totp_ultimo_paso")).containsEntry("tipo", "bigint").containsEntry("nulo", "YES")
                .containsEntry("defecto", null);
    }

    @Test
    @DisplayName("los usuarios de antes (el super admin también) quedan igual y sin el código de la app")
    void losExistentesQuedanIgual() {
        long f = ferreteria("30-78999971-1");
        long dueno = usuario(f, "v48-dueno@test.local", "DUENO");
        long admin = usuario(null, "v48-admin@test.local", "SUPER_ADMIN");
        Map<String, Object> duenoAntes = jdbc.queryForMap("select * from usuario where id = ?", dueno);
        Map<String, Object> adminAntes = jdbc.queryForMap("select * from usuario where id = ?", admin);

        migrarALa48();

        for (Map.Entry<Long, Map<String, Object>> antes : Map.of(dueno, duenoAntes, admin, adminAntes).entrySet()) {
            Map<String, Object> despues = new HashMap<>(jdbc.queryForMap("select * from usuario where id = ?",
                    antes.getKey()));
            assertThat(despues.remove("totp_secreto")).isNull();
            assertThat(despues.remove("totp_activado_en")).isNull();
            assertThat(despues.remove("totp_ultimo_paso")).isNull();
            assertThat(despues).isEqualTo(antes.getValue());
        }
    }

    @Test
    @DisplayName("solo un SUPER_ADMIN tiene código de la app; activado y último paso van juntos y con el secreto; el reset "
            + "del README los borra")
    void reglasDelCodigo() {
        long f = ferreteria("30-78999971-2");
        migrarALa48();
        long dueno = usuario(f, "v48-dueno-2@test.local", "DUENO");
        long admin = usuario(null, "v48-admin-2@test.local", "SUPER_ADMIN");
        String secreto = "update usuario set totp_secreto = x'0102' where id = ?";
        String activar = "update usuario set totp_activado_en = '2026-11-09 12:00:00', totp_ultimo_paso = 100 where id = ?";

        assertThatThrownBy(() -> jdbc.update(secreto, dueno)).hasMessageContaining("ck_usuario_totp");
        assertThatThrownBy(() -> jdbc.update(activar, admin)).as("activado sin secreto").hasMessageContaining(
                "ck_usuario_totp");
        assertThatCode(() -> jdbc.update(secreto, admin)).as("el alta pendiente").doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("update usuario set totp_ultimo_paso = 100 where id = ?", admin))
                .as("último paso sin activar").hasMessageContaining("ck_usuario_totp");
        assertThatCode(() -> jdbc.update(activar, admin)).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("update usuario set totp_secreto = null where id = ?", admin))
                .as("medio reset").hasMessageContaining("ck_usuario_totp");
        assertThatThrownBy(() -> jdbc.update("update usuario set rol = 'DUENO', ferreteria_id = ? where id = ?", f, admin))
                .hasMessageContaining("ck_usuario_totp");
        assertThatCode(() -> jdbc.update(RESET, admin)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rollback-v48-totp-super-admin.sql saca las columnas, el CHECK y la V48 del historial, y deja lo demas")
    void rollback() throws Exception {
        migrarALa48();
        long admin = usuario(null, "v48-admin-3@test.local", "SUPER_ADMIN");
        jdbc.update("update usuario set totp_secreto = x'0102', totp_activado_en = '2026-11-09 12:00:00', "
                + "totp_ultimo_paso = 100 where id = ?", admin);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(StreamUtils.copyToString(new ClassPathResource("auth/rollback-v48-totp-super-admin.sql")
                    .getInputStream(), StandardCharsets.UTF_8));
        }

        assertThat(columnasDeUsuario()).doesNotContain("totp_secreto", "totp_activado_en", "totp_ultimo_paso");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.check_constraints "
                + "where constraint_schema = database() and constraint_name = 'ck_usuario_totp'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '48'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select email from usuario where id = ?", String.class, admin))
                .isEqualTo("v48-admin-3@test.local");
        // Y V48 se puede volver a aplicar.
        migrarALa48();
        assertThat(jdbc.queryForObject("select totp_secreto from usuario where id = ?", byte[].class, admin)).isNull();
    }

    private Map<String, Object> columna(String nombre) {
        return jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, column_default as defecto "
                + "from information_schema.columns where table_schema = database() and table_name = 'usuario' "
                + "and column_name = ?", nombre);
    }

    private List<String> columnasDeUsuario() {
        return jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_schema = database() and table_name = 'usuario'", String.class);
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private long usuario(Long ferreteria, String email, String rol) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', ?, ?)",
                ferreteria, email, "Usuario " + email, rol);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }
}
