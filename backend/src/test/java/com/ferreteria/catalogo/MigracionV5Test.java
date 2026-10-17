package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Migracion V5 (moneda y cotizacion): moneda del articulo y del historial, usuario del historial opcional y las
 * tablas cotizacion (global) y configuracion_cotizacion (por ferreteria). El arranque del contexto ES la
 * validacion de Hibernate (ddl-auto=validate).
 */
class MigracionV5Test extends IntegrationTestBase {

    @Autowired
    ArticuloRepository articuloRepository;

    @Autowired
    HistorialPrecioRepository historialPrecioRepository;

    /** La tabla cotizacion es global y la base no se revierte: cada test borra lo que insertó. */
    @AfterEach
    void limpiarCotizaciones() {
        jdbc.update("delete from cotizacion where casa like 'v5-%'");
    }

    // ---- articulo.moneda ----

    @Test
    @DisplayName("articulo.moneda es VARCHAR(3) NOT NULL con default ARS")
    void monedaDelArticulo() {
        assertThat(tipoYNulabilidad("articulo", "moneda")).isEqualTo("varchar(3) NOT NULL");
        assertThat(columna("articulo", "moneda", "column_default")).isEqualTo("ARS");
    }

    @Test
    @DisplayName("los articulos que ya existian (demo) quedan en ARS")
    void losExistentesQuedanEnPesos() {
        // Solo los articulos demo (ids 1 a 8 de V1001): la base se comparte y no se revierte, y otros tests dejan
        // articulos propios en USD. Contar toda la tabla hacia depender el resultado del orden de las clases.
        Integer total = jdbc.queryForObject(
                "select count(*) from articulo where id between 1 and 8", Integer.class);
        Integer enPesos = jdbc.queryForObject(
                "select count(*) from articulo where id between 1 and 8 and moneda = 'ARS'", Integer.class);

        assertThat(total).isEqualTo(8);
        assertThat(enPesos).isEqualTo(total);
    }

    @Test
    @DisplayName("el CHECK de moneda acepta ARS y USD y rechaza cualquier otra, como EUR")
    void checkDeMoneda() {
        Long f = ferreteriaId("30-73000001-1", "Migracion V5 moneda");

        insertarArticulo(f, "V5-ARS", "ARS");
        insertarArticulo(f, "V5-USD", "USD");
        assertThatThrownBy(() -> insertarArticulo(f, "V5-EUR", "EUR"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_articulo_moneda");

        assertThat(jdbc.queryForList(
                "select moneda from articulo where ferreteria_id = ? order by codigo_interno", String.class, f))
                .containsExactly("ARS", "USD");
    }

    @Test
    @DisplayName("un articulo insertado sin moneda queda en ARS")
    void sinMonedaQuedaEnPesos() {
        Long f = ferreteriaId("30-73000002-2", "Migracion V5 default");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final) "
                + "values (?, 'V5-DEF', 'Sin moneda', 100.00, 170.00)", f);

        assertThat(jdbc.queryForObject(
                "select moneda from articulo where ferreteria_id = ?", String.class, f)).isEqualTo("ARS");
    }

    @Test
    @DisplayName("hay un indice (ferreteria_id, moneda) en articulo")
    void indiceDeMoneda() {
        assertThat(columnasDelIndice("articulo", "ix_articulo_moneda")).containsExactly("ferreteria_id", "moneda");
    }

    // ---- historial_precio ----

    @Test
    @DisplayName("historial_precio.usuario_id admite NULL, la FK al usuario se mantiene y la moneda es ARS por defecto")
    void historialSinUsuario() {
        assertThat(tipoYNulabilidad("historial_precio", "usuario_id")).isEqualTo("bigint NULL");
        assertThat(tipoYNulabilidad("historial_precio", "moneda")).isEqualTo("varchar(3) NOT NULL");
        assertThat(columna("historial_precio", "moneda", "column_default")).isEqualTo("ARS");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.key_column_usage where table_schema = database() "
                        + "and table_name = 'historial_precio' and column_name = 'usuario_id' "
                        + "and referenced_table_name = 'usuario'", Integer.class)).isEqualTo(1);

        Long f = ferreteriaId("30-73000003-3", "Migracion V5 historial");
        Long articuloId = insertarArticulo(f, "V5-HIS", "USD");
        jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_nuevo, precio_nuevo, origen, "
                + "usuario_id) values (?, ?, 10.00, 15730.00, 'COTIZACION', NULL)", f, articuloId);

        Map<String, Object> fila = jdbc.queryForMap(
                "select usuario_id, moneda, origen from historial_precio where articulo_id = ?", articuloId);
        assertThat(fila.get("usuario_id")).isNull();
        assertThat(fila.get("moneda")).isEqualTo("ARS");
        assertThat(fila.get("origen")).isEqualTo("COTIZACION");
    }

    @Test
    @DisplayName("la FK de usuario_id sigue rechazando un usuario que no existe")
    void laFkDelUsuarioSigue() {
        Long f = ferreteriaId("30-73000004-4", "Migracion V5 fk");
        Long articuloId = insertarArticulo(f, "V5-FK", "ARS");

        assertThatThrownBy(() -> jdbc.update(
                "insert into historial_precio (ferreteria_id, articulo_id, costo_nuevo, precio_nuevo, origen, "
                        + "usuario_id) values (?, ?, 10.00, 17.00, 'MANUAL', 999999999)", f, articuloId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("la entidad HistorialPrecio se guarda y se relee con usuario NULL, origen COTIZACION y moneda USD")
    void historialConEntidadSinUsuario() {
        Long f = ferreteriaId("30-73000008-8", "Migracion V5 historial entidad");
        Long articuloId = insertarArticulo(f, "V5-ENT", "USD");

        TenantContext.setCurrentFerreteriaId(f);
        try {
            Articulo articulo = articuloRepository.findScoped(articuloId).orElseThrow();
            historialPrecioRepository.save(HistorialPrecio.de(articulo,
                    new CambioPrecio(new BigDecimal("10.00"), new BigDecimal("10.00"),
                            new BigDecimal("15730.00"), new BigDecimal("17310.00")),
                    OrigenPrecio.COTIZACION, null));

            List<HistorialPrecio> historial = historialPrecioRepository.findAllScoped();
            assertThat(historial).hasSize(1);
            assertThat(historial.get(0).getUsuarioId()).isNull();
            assertThat(historial.get(0).getOrigen()).isEqualTo(OrigenPrecio.COTIZACION);
            assertThat(historial.get(0).getMoneda()).isEqualTo(Moneda.USD);
            assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("17310");
        } finally {
            TenantContext.clear();
        }
    }

    // ---- cotizacion (global) ----

    @Test
    @DisplayName("cotizacion: compra y venta DECIMAL(15,4) NULL, fechas DATETIME(6) y sin ferreteria_id")
    void tablaCotizacion() {
        assertThat(tipoYNulabilidad("cotizacion", "compra")).isEqualTo("decimal(15,4) NULL");
        assertThat(tipoYNulabilidad("cotizacion", "venta")).isEqualTo("decimal(15,4) NULL");
        assertThat(tipoYNulabilidad("cotizacion", "actualizada_en")).isEqualTo("datetime(6) NOT NULL");
        assertThat(tipoYNulabilidad("cotizacion", "obtenida_en")).isEqualTo("datetime(6) NOT NULL");
        assertThat(tipoYNulabilidad("cotizacion", "moneda")).isEqualTo("varchar(3) NOT NULL");
        assertThat(tipoYNulabilidad("cotizacion", "fuente")).isEqualTo("varchar(20) NOT NULL");
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = database() "
                        + "and table_name = 'cotizacion' and column_name = 'ferreteria_id'", Integer.class)).isZero();
        assertThat(columnasDelIndice("cotizacion", "uk_cotizacion_moneda_casa")).containsExactly("moneda", "casa");
    }

    @Test
    @DisplayName("cotizacion: (moneda, casa) es unico y los cuatro decimales se guardan sin perdida")
    void cotizacionUnicaPorMonedaYCasa() {
        insertarCotizacion("USD", "v5-unica", "1000.5000", "1001.2500");

        assertThat(jdbc.queryForObject(
                "select compra from cotizacion where casa = 'v5-unica'", java.math.BigDecimal.class))
                .isEqualByComparingTo("1000.5000");
        assertThat(jdbc.queryForObject(
                "select venta from cotizacion where casa = 'v5-unica'", java.math.BigDecimal.class))
                .isEqualByComparingTo("1001.2500");
        assertThatThrownBy(() -> insertarCotizacion("USD", "v5-unica", "1.0000", "2.0000"))
                .isInstanceOf(DuplicateKeyException.class);
        // La misma casa en otra moneda no choca.
        insertarCotizacion("EUR", "v5-unica", "1100.0000", "1150.0000");
    }

    // ---- configuracion_cotizacion (por ferreteria) ----

    @Test
    @DisplayName("configuracion_cotizacion: una fila por ferreteria y con FK a la ferreteria")
    void configuracionUnicaPorFerreteria() {
        Long f = ferreteriaId("30-73000005-5", "Migracion V5 config unica");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (?)", f);

        assertThat(columnasDelIndice("configuracion_cotizacion", "uk_configuracion_cotizacion_ferreteria"))
                .containsExactly("ferreteria_id");
        assertThatThrownBy(() -> jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (?)", f))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (999999999)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("configuracion_cotizacion: los valores por defecto son activa, oficial venta, 08:00 y visibles USD:oficial")
    void configuracionPorDefecto() throws Exception {
        Long f = ferreteriaId("30-73000006-6", "Migracion V5 config defaults");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (?)", f);

        Map<String, Object> fila = jdbc.queryForMap(
                "select activa, casa_precios, lado_precios, visibles, "
                        + "time_format(hora_recalculo, '%H:%i') as hora, vigente, vigente_casa, vigente_lado, "
                        + "vigente_desde, ultimo_recalculo_dia from configuracion_cotizacion where ferreteria_id = ?", f);

        assertThat(fila.get("activa")).isIn(true, 1);
        assertThat(fila.get("casa_precios")).isEqualTo("oficial");
        assertThat(fila.get("lado_precios")).isEqualTo("VENTA");
        assertThat(objectMapper.readTree((String) fila.get("visibles")))
                .isEqualTo(objectMapper.readTree("[\"USD:oficial\"]"));
        assertThat(fila.get("hora")).isEqualTo("08:00");
        assertThat(fila.get("vigente")).isNull();
        assertThat(fila.get("vigente_casa")).isNull();
        assertThat(fila.get("vigente_lado")).isNull();
        assertThat(fila.get("vigente_desde")).isNull();
        assertThat(fila.get("ultimo_recalculo_dia")).isNull();
    }

    @Test
    @DisplayName("configuracion_cotizacion: tipos de las columnas del dolar vigente")
    void configuracionTipos() {
        assertThat(tipoYNulabilidad("configuracion_cotizacion", "vigente")).isEqualTo("decimal(15,4) NULL");
        assertThat(tipoYNulabilidad("configuracion_cotizacion", "vigente_desde")).isEqualTo("datetime(6) NULL");
        assertThat(tipoYNulabilidad("configuracion_cotizacion", "ultimo_recalculo_dia")).isEqualTo("date NULL");
        assertThat(tipoYNulabilidad("configuracion_cotizacion", "visibles")).isEqualTo("json NOT NULL");
        assertThat(tipoYNulabilidad("configuracion_cotizacion", "hora_recalculo")).isEqualTo("time NOT NULL");
    }

    @Test
    @DisplayName("configuracion_cotizacion: el CHECK rechaza un lado distinto de COMPRA o VENTA y un vigente <= 0")
    void configuracionChecks() {
        Long f = ferreteriaId("30-73000007-7", "Migracion V5 config checks");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id) values (?)", f);

        jdbc.update("update configuracion_cotizacion set lado_precios = 'COMPRA', vigente = 1000.5000 "
                + "where ferreteria_id = ?", f);
        assertThatThrownBy(() -> jdbc.update(
                "update configuracion_cotizacion set lado_precios = 'MEDIO' where ferreteria_id = ?", f))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_configuracion_cotizacion_lado");
        assertThatThrownBy(() -> jdbc.update(
                "update configuracion_cotizacion set vigente = 0 where ferreteria_id = ?", f))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_configuracion_cotizacion_vigente");
        assertThatThrownBy(() -> jdbc.update(
                "update configuracion_cotizacion set vigente = -5 where ferreteria_id = ?", f))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_configuracion_cotizacion_vigente");
    }

    @Test
    @DisplayName("la migracion no rellena filas: las ferreterias existentes no tienen configuracion hasta que la piden")
    void sinBackfill() {
        Integer ferreterias = jdbc.queryForObject("select count(*) from ferreteria", Integer.class);
        Integer sinConfiguracion = jdbc.queryForObject(
                "select count(*) from ferreteria f where not exists "
                        + "(select 1 from configuracion_cotizacion c where c.ferreteria_id = f.id)", Integer.class);

        assertThat(ferreterias).isPositive();
        assertThat(sinConfiguracion).isPositive();
    }

    // ---- ayudas ----

    private String tipoYNulabilidad(String tabla, String columna) {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select column_type as tipo, is_nullable as nulo from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        assertThat(filas).as(tabla + "." + columna).hasSize(1);
        return filas.get(0).get("tipo") + ("NO".equals(filas.get(0).get("nulo")) ? " NOT NULL" : " NULL");
    }

    private String columna(String tabla, String columna, String atributo) {
        return jdbc.queryForObject(
                "select " + atributo + " from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?",
                String.class, tabla, columna);
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList(
                "select column_name from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ? "
                        + "order by seq_in_index",
                String.class, tabla, indice);
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
                razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long insertarArticulo(Long ferreteriaId, String codigoInterno, String moneda) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, moneda) "
                + "values (?, ?, 'Articulo de prueba', 10.00, 15730.00, ?)", ferreteriaId, codigoInterno, moneda);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigoInterno);
    }

    private void insertarCotizacion(String moneda, String casa, String compra, String venta) {
        jdbc.update("insert into cotizacion (moneda, casa, nombre, compra, venta, fuente, actualizada_en, obtenida_en) "
                + "values (?, ?, 'Prueba', ?, ?, 'DOLARAPI', '2026-10-16 08:00:00', '2026-10-16 08:05:00')",
                moneda, casa, compra, venta);
    }
}
