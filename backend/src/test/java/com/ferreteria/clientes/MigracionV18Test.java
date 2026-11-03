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
 * Migracion V18 (clientes, CL-001 y CL-002): la tabla cliente, con el documento unico por ferreteria entre los cargados
 * y siempre completo (tipo y numero juntos). Usa un MySQL de Testcontainers propio y una base nueva por test.
 */
class MigracionV18Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNueva() {
        String url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v18", "18"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private void cliente(long ferreteria, String razonSocial, String tipo, String numero) {
        jdbc.update("insert into cliente (ferreteria_id, razon_social, tipo_documento, numero_documento) "
                + "values (?, ?, ?, ?)", ferreteria, razonSocial, tipo, numero);
    }

    @Test
    @DisplayName("las columnas del cliente: tipos, nulabilidad y defaults")
    void columnas() {
        Map<String, String> esperadas = Map.ofEntries(
                Map.entry("razon_social", "varchar(120) NO null"),
                Map.entry("tipo_documento", "varchar(10) YES null"),
                Map.entry("numero_documento", "varchar(11) YES null"),
                Map.entry("condicion_iva", "varchar(30) NO CONSUMIDOR_FINAL"),
                Map.entry("domicilio", "varchar(200) YES null"),
                Map.entry("telefono", "varchar(30) YES null"),
                Map.entry("whatsapp", "varchar(30) YES null"),
                Map.entry("email", "varchar(120) YES null"),
                Map.entry("activo", "tinyint(1) NO 1"));
        List<Map<String, Object>> columnas = jdbc.queryForList(
                "select column_name, column_type, is_nullable, column_default from information_schema.columns "
                        + "where table_schema = database() and table_name = 'cliente'");
        for (Map.Entry<String, String> e : esperadas.entrySet()) {
            Map<String, Object> c = columnas.stream()
                    .filter(f -> e.getKey().equals(f.get("COLUMN_NAME"))).findFirst().orElseThrow();
            assertThat(c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE") + " " + c.get("COLUMN_DEFAULT"))
                    .as(e.getKey()).isEqualTo(esperadas.get(e.getKey()));
        }
    }

    @Test
    @DisplayName("el documento es unico por ferreteria, y otra ferreteria puede tener el mismo")
    void documentoUnicoPorFerreteria() {
        long a = ferreteria("30-70000001-1");
        long b = ferreteria("30-70000002-9");
        cliente(a, "Ana", "DNI", "12345678");

        assertThatThrownBy(() -> cliente(a, "Otra Ana", "DNI", "12345678")).isInstanceOf(DataAccessException.class);
        cliente(b, "Ana de la otra", "DNI", "12345678");
        assertThat(jdbc.queryForObject("select count(*) from cliente", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("varios clientes sin documento conviven (los NULL no chocan)")
    void variosSinDocumento() {
        long a = ferreteria("30-70000001-1");
        cliente(a, "Juan, el del taller", null, null);
        cliente(a, "Pedro, el de la obra", null, null);

        assertThat(jdbc.queryForObject("select count(*) from cliente", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("tipo y numero de documento van juntos: uno solo es rechazado")
    void documentoCompleto() {
        long a = ferreteria("30-70000001-1");

        assertThatThrownBy(() -> cliente(a, "Sin numero", "DNI", null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> cliente(a, "Sin tipo", null, "12345678")).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("el cliente pertenece a una ferreteria que existe")
    void ferreteriaExistente() {
        assertThatThrownBy(() -> cliente(9999L, "Fantasma", null, null)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("sin condicion de IVA ni activo, el cliente nace Consumidor Final y activo")
    void defaults() {
        long a = ferreteria("30-70000001-1");
        cliente(a, "Ana", null, null);

        Map<String, Object> fila = jdbc.queryForMap("select condicion_iva, activo from cliente");
        assertThat(fila.get("condicion_iva")).isEqualTo("CONSUMIDOR_FINAL");
        assertThat(fila.get("activo")).isIn(true, 1);
    }
}
