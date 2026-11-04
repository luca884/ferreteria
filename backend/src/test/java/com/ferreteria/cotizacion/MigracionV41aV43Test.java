package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.PlantillaDeMigracion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migraciones V41 (umbral del aviso del dolar), V42 (contacto del proveedor) y V43 (telefono del usuario) sobre datos
 * existentes (mejoras-ferreteros-bloque-1). Base nueva por test en la V40, siembra por JDBC y recien despues migra a la
 * V43. CUIT: bloque 30-7899999x.
 */
class MigracionV41aV43Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final List<String> CONTACTO = List.of("contacto", "telefono", "whatsapp", "email");

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion40() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v41", "40"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa43() {
        flyway("classpath:db/migration").target("43").load().migrate();
    }

    @Test
    @DisplayName("una ferreteria de antes queda con el aviso al 5 %, sin contacto ni telefono, y con todo lo demas igual")
    void losDatosExistentesQuedanIgual() {
        long f = ferreteria("30-78999991-1");
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
            + "values (?, 'dueno-v41@test.local', 'x', 'Dueño', 'DUENO')", f);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor V41')", f);
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id, vigente, vigente_casa, vigente_lado) "
            + "values (?, 1500, 'oficial', 'VENTA')", f);
        Map<String, Object> configuracionAntes = fila("configuracion_cotizacion", f);
        Map<String, Object> usuarioAntes = fila("usuario", f);
        Map<String, Object> proveedorAntes = fila("proveedor", f);

        migrarALa43();

        Map<String, Object> configuracion = new HashMap<>(fila("configuracion_cotizacion", f));
        assertThat(configuracion.remove("umbral_desvio_porcentaje")).hasToString("5.00");
        assertThat(configuracion).isEqualTo(configuracionAntes);

        Map<String, Object> usuario = new HashMap<>(fila("usuario", f));
        assertThat(usuario.remove("telefono")).isNull();
        assertThat(usuario).isEqualTo(usuarioAntes);

        Map<String, Object> proveedor = new HashMap<>(fila("proveedor", f));
        CONTACTO.forEach(columna -> assertThat(proveedor.remove(columna)).as(columna).isNull());
        assertThat(proveedor).isEqualTo(proveedorAntes);
    }

    @Test
    @DisplayName("el aviso del dolar acepta de 1 a 50 y rechaza 0.99, 50.01 y NULL")
    void elUmbralTieneLimites() {
        long f = ferreteria("30-78999991-2");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (?)", f);
        migrarALa43();
        String fijar = "update configuracion_cotizacion set umbral_desvio_porcentaje = ? where ferreteria_id = ?";

        assertThatThrownBy(() -> jdbc.update(fijar, "0.99", f)).hasMessageContaining("ck_configuracion_cotizacion_umbral");
        assertThatThrownBy(() -> jdbc.update(fijar, "50.01", f)).hasMessageContaining("ck_configuracion_cotizacion_umbral");
        assertThatThrownBy(() -> jdbc.update(fijar, null, f)).isNotNull();
        assertThatCode(() -> jdbc.update(fijar, "1", f)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(fijar, "50", f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V41-V43 entran por out-of-order y los datos quedan igual")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String proveedores = "select id, ferreteria_id, nombre, cuit from proveedor order by id";
        String usuarios = "select id, ferreteria_id, email, nombre, rol from usuario order by id";
        List<Map<String, Object>> proveedoresAntes = jdbc.queryForList(proveedores);
        List<Map<String, Object>> usuariosAntes = jdbc.queryForList(usuarios);
        assertThat(usuariosAntes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(proveedores)).isEqualTo(proveedoresAntes);
        assertThat(jdbc.queryForList(usuarios)).isEqualTo(usuariosAntes);
        assertThat(jdbc.queryForObject("select count(*) from configuracion_cotizacion "
            + "where umbral_desvio_porcentaje <> 5.00", Integer.class)).isZero();
    }

    private Map<String, Object> fila(String tabla, long ferreteriaId) {
        return jdbc.queryForMap("select * from " + tabla + " where ferreteria_id = ?", ferreteriaId);
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }
}
