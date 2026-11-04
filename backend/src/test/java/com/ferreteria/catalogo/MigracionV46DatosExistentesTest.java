package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.PlantillaDeMigracion;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Regresion de CU-13: una compra y un articulo con precio manual y ajustes anteriores a V46 quedan iguales. */
class MigracionV46DatosExistentesTest {

    @Test
    void compraYArticuloConAjustesAnterioresQuedanIguales() {
        String url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v46regresion", "45"));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root",
            PlantillaDeMigracion.MYSQL.getPassword()));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('F', '30-78401469-0', "
            + "'RESPONSABLE_INSCRIPTO')");
        Long f = jdbc.queryForObject("select id from ferreteria where cuit = '30-78401469-0'", Long.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, 'p@v46.x', "
            + "'x', 'P', 'DUENO')", f);
        Long u = jdbc.queryForObject("select id from usuario where email = 'p@v46.x'", Long.class);
        jdbc.update("insert into proveedor (ferreteria_id, nombre, cuit, activo) values (?, 'Prov', null, true)", f);
        Long p = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "stock_actual, precio_manual, ajustes_precio) values (?, 'V46P', 'A', 900.00, 1420.00, 10, true, "
            + "'[{\"nombre\":\"Flete\",\"tipo\":\"PORCENTAJE\",\"valor\":10.00}]')", f);
        Long a = jdbc.queryForObject("select id from articulo where codigo_interno = 'V46P'", Long.class);
        jdbc.update("insert into compra (ferreteria_id, proveedor_id, proveedor_nombre, tipo, punto_venta, numero, "
            + "fecha, moneda, neto, clave_idempotencia, usuario_id) values (?, ?, 'Prov', 'FA', 3, 77, '2026-10-01', "
            + "'ARS', 1800.00, '11111111-1111-1111-1111-111111111111', ?)", f, p, u);
        Long c = jdbc.queryForObject("select id from compra where ferreteria_id = ?", Long.class, f);
        Map<String, Object> compraAntes = jdbc.queryForMap("select * from compra where id = ?", c);
        Map<String, Object> articuloAntes = jdbc.queryForMap("select * from articulo where id = ?", a);

        Flyway.configure().dataSource(url, "root", PlantillaDeMigracion.MYSQL.getPassword())
            .locations("classpath:db/migration").target("46").load().migrate();

        Map<String, Object> compraDespues = new HashMap<>(jdbc.queryForMap("select * from compra where id = ?", c));
        Map<String, Object> articuloDespues = new HashMap<>(jdbc.queryForMap("select * from articulo where id = ?", a));
        assertThat(compraDespues.remove("recargos")).isEqualTo("[]");
        assertThat(articuloDespues.remove("recargos_costo")).isEqualTo("[]");
        assertThat(compraDespues).isEqualTo(compraAntes);
        assertThat(articuloDespues).isEqualTo(articuloAntes);
    }
}
