package com.ferreteria.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El clon que entrega {@link PlantillaDeMigracion} tiene que ser indistinguible de una base migrada desde cero: si no, los
 * tests de migraciones darian verde sin probar la base real. Para cada version de db/migration compara un clon con una base
 * migrada directo a esa version: tablas, columnas, indices, FKs, CHECK, opciones de tabla, AUTO_INCREMENT, datos y el
 * historial de Flyway (sin horas ni duraciones).
 */
class PlantillaDeMigracionTest {

    private static final String ESQUEMA = """
        select 'tabla', table_name, concat_ws('|', engine, table_collation, create_options, table_comment)
          from information_schema.tables where table_schema = ? and table_type = 'BASE TABLE'
        union all
        select 'columna', concat(table_name, '.', column_name), concat_ws('|', ordinal_position, column_type, is_nullable,
               coalesce(column_default, '<null>'), extra, coalesce(character_set_name, ''), coalesce(collation_name, ''),
               column_comment, coalesce(generation_expression, ''))
          from information_schema.columns where table_schema = ?
        union all
        select 'indice', concat(table_name, '.', index_name, '.', seq_in_index), concat_ws('|', non_unique,
               coalesce(column_name, ''), coalesce(sub_part, ''), coalesce(expression, ''), index_type, is_visible)
          from information_schema.statistics where table_schema = ?
        union all
        select 'restriccion', concat(table_name, '.', constraint_name), concat_ws('|', constraint_type, enforced)
          from information_schema.table_constraints where table_schema = ?
        union all
        select 'fk-columna', concat(table_name, '.', constraint_name, '.', ordinal_position), concat_ws('|', column_name,
               coalesce(referenced_table_schema = table_schema, ''), coalesce(referenced_table_name, ''),
               coalesce(referenced_column_name, ''))
          from information_schema.key_column_usage where table_schema = ?
        union all
        select 'fk-regla', concat(table_name, '.', constraint_name), concat_ws('|', update_rule, delete_rule,
               referenced_table_name, unique_constraint_name)
          from information_schema.referential_constraints where constraint_schema = ?
        union all
        select 'check', constraint_name, check_clause
          from information_schema.check_constraints where constraint_schema = ?
        union all
        select 'auto_increment', table_name, coalesce(auto_increment, 1)
          from information_schema.tables where table_schema = ? and table_type = 'BASE TABLE'
        union all
        select 'objeto', concat(routine_type, '.', routine_name), '' from information_schema.routines where routine_schema = ?
        union all
        select 'objeto', concat('VIEW.', table_name), '' from information_schema.views where table_schema = ?
        union all
        select 'objeto', concat('TRIGGER.', trigger_name), '' from information_schema.triggers where trigger_schema = ?
        union all
        select 'objeto', concat('EVENT.', event_name), '' from information_schema.events where event_schema = ?
        """;

    @Test
    @DisplayName("en cada version, el clon tiene el mismo esquema, AUTO_INCREMENT, datos e historial que una base migrada desde cero")
    void elClonEsIgualAUnaBaseMigrada() {
        List<String> versiones = versiones();
        assertThat(versiones).hasSizeGreaterThanOrEqualTo(40);

        String directa = PlantillaDeMigracion.baseVacia("directa");
        for (String version : versiones) {
            migrar(directa, version);
            String clon = PlantillaDeMigracion.baseEnLaVersion("clon", version);

            assertThat(diferencias(esquema(directa), esquema(clon))).as("esquema en la V%s", version).isEmpty();
            assertThat(diferencias(datos(directa), datos(clon))).as("datos en la V%s", version).isEmpty();
            assertThat(historial(clon)).as("historial en la V%s", version).isEqualTo(historial(directa));
        }
    }

    /** Las claves cuyo valor difiere (o falta de un lado), con «directa => clon». */
    private static Map<String, String> diferencias(Map<String, ?> directa, Map<String, ?> clon) {
        Map<String, String> diferencias = new TreeMap<>();
        java.util.Set<String> claves = new java.util.TreeSet<>(directa.keySet());
        claves.addAll(clon.keySet());
        for (String clave : claves) {
            if (!java.util.Objects.equals(directa.get(clave), clon.get(clave))) {
                diferencias.put(clave, directa.get(clave) + " => " + clon.get(clave));
            }
        }
        return diferencias;
    }

    @Test
    @DisplayName("el clon tiene la misma charset y collation por defecto que una base migrada")
    void mismaCharsetYCollation() {
        String directa = PlantillaDeMigracion.baseVacia("directa_charset");
        migrar(directa, "40");
        String clon = PlantillaDeMigracion.baseEnLaVersion("clon_charset", "40");

        assertThat(charsetYCollation(clon)).isEqualTo(charsetYCollation(directa));
    }

    @Test
    @DisplayName("el clon conserva el hueco del AUTO_INCREMENT que dejan filas borradas: el proximo id es el mismo")
    void conservaElHuecoDelAutoIncrement() {
        String origen = PlantillaDeMigracion.baseEnLaVersion("hueco", "1");
        JdbcTemplate jdbcOrigen = jdbc(origen);
        jdbcOrigen.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
            + "values ('Borrada', '30-71234567-9', 'RESPONSABLE_INSCRIPTO')");
        jdbcOrigen.update("delete from ferreteria");
        String clon = PlantillaDeMigracion.baseVacia("hueco_clon");
        PlantillaDeMigracion.clonar(origen, clon);

        assertThat(esquema(clon).get("auto_increment ferreteria")).isEqualTo("2");
        assertThat(esquema(clon)).isEqualTo(esquema(origen));
    }

    private static Map<String, Object> charsetYCollation(String base) {
        return jdbc(base).queryForMap("select default_character_set_name, default_collation_name "
            + "from information_schema.schemata where schema_name = ?", base);
    }

    @Test
    @DisplayName("cada clon es una base propia: lo que escribe un test no lo ve otro clon de la misma version")
    void cadaClonEsIndependiente() {
        String uno = PlantillaDeMigracion.baseEnLaVersion("uno", "1");
        String otro = PlantillaDeMigracion.baseEnLaVersion("otro", "1");
        assertThat(uno).isNotEqualTo(otro);

        jdbc(uno).update("insert into ferreteria (razon_social, cuit, condicion_iva) "
            + "values ('Clon Uno', '30-71234567-1', 'RESPONSABLE_INSCRIPTO')");

        assertThat(jdbc(otro).queryForObject("select count(*) from ferreteria where razon_social = 'Clon Uno'", Long.class))
            .isZero();
        assertThat(jdbc(PlantillaDeMigracion.baseEnLaVersion("tercero", "1"))
            .queryForObject("select count(*) from ferreteria where razon_social = 'Clon Uno'", Long.class)).isZero();
    }

    @Test
    @DisplayName("un clon sigue migrando con Flyway hasta la ultima version, con el historial validado")
    void elClonSigueMigrando() {
        String clon = PlantillaDeMigracion.baseEnLaVersion("sigue", "20");

        flyway(clon).ignoreMigrationPatterns("*:pending").load().validate();
        Flyway flyway = flyway(clon).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(versiones().size() - 20);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(versiones().get(versiones().size() - 1));
    }

    private static List<String> versiones() {
        List<String> versiones = new ArrayList<>();
        for (MigrationInfo info : flyway(PlantillaDeMigracion.baseVacia("info")).load().info().all()) {
            versiones.add(info.getVersion().getVersion());
        }
        return versiones;
    }

    private static void migrar(String base, String version) {
        flyway(base).target(version).load().migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String base) {
        return Flyway.configure().dataSource(PlantillaDeMigracion.url(base), "root", PlantillaDeMigracion.MYSQL.getPassword())
            .locations("classpath:db/migration");
    }

    private static JdbcTemplate jdbc(String base) {
        return new JdbcTemplate(new DriverManagerDataSource(PlantillaDeMigracion.url(base), "root",
            PlantillaDeMigracion.MYSQL.getPassword()));
    }

    private static Map<String, String> esquema(String base) {
        JdbcTemplate jdbc = jdbc(base);
        jdbc.execute("set session information_schema_stats_expiry = 0");
        Object[] args = new Object[12];
        java.util.Arrays.fill(args, base);
        Map<String, String> esquema = new TreeMap<>();
        jdbc.query(ESQUEMA, rs -> {
            esquema.put(rs.getString(1) + " " + rs.getString(2), rs.getString(3));
        }, args);
        return esquema;
    }

    private static Map<String, List<Map<String, Object>>> datos(String base) {
        JdbcTemplate jdbc = jdbc(base);
        Map<String, List<Map<String, Object>>> datos = new TreeMap<>();
        for (String tabla : jdbc.queryForList("select table_name from information_schema.tables where table_schema = ? "
            + "and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'", String.class, base)) {
            datos.put(tabla, jdbc.queryForList("select * from `" + tabla + "` order by 1"));
        }
        return datos;
    }

    private static List<Map<String, Object>> historial(String base) {
        return jdbc(base).queryForList("select installed_rank, version, description, type, script, checksum, success "
            + "from flyway_schema_history order by installed_rank");
    }
}
