package com.ferreteria.catalogo;

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
 * Migracion V44 (oferta del articulo, ofertas S1) sobre datos existentes. Base nueva por test en la V43, siembra por
 * JDBC y recien despues migra a la V44. CUIT: bloque 30-7899998x.
 */
class MigracionV44Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final List<String> OFERTA = List.of("oferta_tipo", "oferta_valor", "oferta_hasta");

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion43() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v44", "43"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa44() {
        flyway("classpath:db/migration").target("44").load().migrate();
    }

    @Test
    @DisplayName("un articulo de antes queda sin oferta y con todo lo demas igual")
    void losDatosExistentesQuedanIgual() {
        long f = ferreteria("30-78999981-1");
        long articulo = articulo(f, "V44-001");
        Map<String, Object> antes = fila(articulo);

        migrarALa44();

        Map<String, Object> despues = new HashMap<>(fila(articulo));
        OFERTA.forEach(columna -> assertThat(despues.remove(columna)).as(columna).isNull());
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("la oferta va completa o vacia, con un tipo conocido, valor mayor a 0 y el % por debajo de 100")
    void laOfertaTieneLimites() {
        long f = ferreteria("30-78999981-2");
        long articulo = articulo(f, "V44-002");
        migrarALa44();
        String fijar = "update articulo set oferta_tipo = ?, oferta_valor = ?, oferta_hasta = ? where id = ?";

        assertThatThrownBy(() -> jdbc.update(fijar, "PRECIO_FIJO", null, null, articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatThrownBy(() -> jdbc.update(fijar, null, "10", null, articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatThrownBy(() -> jdbc.update(fijar, null, null, "2026-11-30", articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatThrownBy(() -> jdbc.update(fijar, "REGALO", "10", null, articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatThrownBy(() -> jdbc.update(fijar, "DESCUENTO_MONTO", "0", null, articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatThrownBy(() -> jdbc.update(fijar, "DESCUENTO_PORCENTAJE", "100", null, articulo))
                .hasMessageContaining("ck_articulo_oferta");
        assertThatCode(() -> jdbc.update(fijar, "DESCUENTO_PORCENTAJE", "99.99", "2026-11-30", articulo))
                .doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(fijar, "PRECIO_FIJO", "150", null, articulo)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(fijar, null, null, null, articulo)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V44 entra por out-of-order y los articulos quedan sin oferta")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String articulos = "select id, ferreteria_id, codigo_interno, precio_final from articulo order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(articulos);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(articulos)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from articulo where oferta_tipo is not null", Integer.class))
                .isZero();
    }

    private Map<String, Object> fila(long articulo) {
        return jdbc.queryForMap("select * from articulo where id = ?", articulo);
    }

    private long articulo(long ferreteriaId, String codigo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, "
                + "unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 100.00, 163.35, 'IVA_21', "
                + "'UNIDAD', 10, 0, 1)", ferreteriaId, codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                ferreteriaId, codigo);
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }
}
