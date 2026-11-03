package com.ferreteria.support;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bases de prueba para los tests de migraciones ({@code Migracion*Test}): un solo MySQL 8.4 para todas las clases y, por
 * cada version, una base plantilla migrada una sola vez por JVM. Cada test recibe un clon propio de la plantilla, armado con
 * {@code SHOW CREATE TABLE} (no {@code CREATE TABLE ... LIKE}, que no copia las FKs) y los datos de cada tabla, incluido el
 * historial de Flyway: para Flyway y para el test es la misma base que si se hubiera migrado desde cero.
 * {@link PlantillaDeMigracionTest} lo compara contra una base migrada en cada version.
 *
 * <p>Como en {@link IntegrationTestBase}, el contenedor arranca en un bloque static y lo limpia Ryuk al terminar la JVM:
 * no se para entre clases.
 */
public final class PlantillaDeMigracion {

    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final Map<String, String> PLANTILLAS = new ConcurrentHashMap<>();

    static {
        MYSQL.start();
    }

    private PlantillaDeMigracion() {
    }

    /** Una base nueva y vacia (utf8mb4); devuelve su nombre. */
    public static String baseVacia(String prefijo) {
        String base = prefijo + "_" + CONTADOR.incrementAndGet();
        ejecutar("create database " + base + " character set utf8mb4");
        return base;
    }

    /** Una base nueva con db/migration aplicada hasta {@code version}, clonada de su plantilla; devuelve su nombre. */
    public static String baseEnLaVersion(String prefijo, String version) {
        String plantilla = PLANTILLAS.computeIfAbsent(version, PlantillaDeMigracion::crearPlantilla);
        String base = baseVacia(prefijo);
        clonar(plantilla, base);
        return base;
    }

    /** URL JDBC de una base del contenedor. */
    public static String url(String base) {
        return MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
    }

    /**
     * Parte de la plantilla ya creada de la version anterior mas cercana (si hay) y migra solo lo que falta: armar todas las
     * versiones cuesta una sola migracion completa. computeIfAbsent no llama a otro computeIfAbsent: la anterior se busca
     * entre las que ya estan.
     */
    private static String crearPlantilla(String version) {
        MigrationVersion objetivo = MigrationVersion.fromVersion(version);
        String plantilla = baseVacia("plantilla_v" + version.replace('.', '_'));
        PLANTILLAS.entrySet().stream()
            .filter(e -> MigrationVersion.fromVersion(e.getKey()).compareTo(objetivo) < 0)
            .max(Comparator.comparing(e -> MigrationVersion.fromVersion(e.getKey())))
            .ifPresent(anterior -> clonar(anterior.getValue(), plantilla));
        Flyway.configure().dataSource(url(plantilla), "root", MYSQL.getPassword()).locations("classpath:db/migration")
            .target(version).load().migrate();
        return plantilla;
    }

    /** Copia todas las tablas de {@code plantilla} en {@code base}, que tiene que existir y estar vacia. */
    static void clonar(String plantilla, String base) {
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("select table_name from information_schema.tables where table_schema = '"
                + plantilla + "' and table_type = 'BASE TABLE' order by table_name")) {
                while (rs.next()) {
                    tablas.add(rs.getString(1));
                }
            }
            List<String> creates = new ArrayList<>();
            for (String tabla : tablas) {
                try (ResultSet rs = s.executeQuery("show create table `" + plantilla + "`.`" + tabla + "`")) {
                    rs.next();
                    creates.add(rs.getString(2));
                }
            }
            s.execute("use `" + base + "`");
            s.execute("set foreign_key_checks = 0");
            for (String create : creates) {
                s.execute(create);
            }
            for (String tabla : tablas) {
                s.execute("insert into `" + tabla + "` select * from `" + plantilla + "`.`" + tabla + "`");
            }
            s.execute("set foreign_key_checks = 1");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo clonar " + plantilla + " en " + base, e);
        }
    }

    private static void ejecutar(String sql) {
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static Connection conexion() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }
}
