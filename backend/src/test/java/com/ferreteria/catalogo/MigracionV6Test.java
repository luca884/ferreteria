package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Unidad;
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
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Migracion V6 (importacion flexible): ajustes por articulo, ultima importacion de cada articulo, moneda anterior
 * del historial y los contadores nuevos de la importacion. Aditiva: no rellena filas. El arranque del contexto ES
 * la validacion de Hibernate (ddl-auto=validate). CUIT: bloque 30-7810xxxx.
 */
class MigracionV6Test extends IntegrationTestBase {

    @Autowired
    ArticuloRepository articuloRepository;

    @Autowired
    HistorialPrecioRepository historialPrecioRepository;

    @AfterEach
    void limpiarElTenant() {
        TenantContext.clear();
    }

    // ---- articulo.ajuste1 / ajuste2 / ajuste3 ----

    @Test
    @DisplayName("articulo.ajuste1, 2 y 3 son DECIMAL(6,2) NULL")
    void tipoDeLosAjustes() {
        assertThat(tipoYNulabilidad("articulo", "ajuste1")).isEqualTo("decimal(6,2) NULL");
        assertThat(tipoYNulabilidad("articulo", "ajuste2")).isEqualTo("decimal(6,2) NULL");
        assertThat(tipoYNulabilidad("articulo", "ajuste3")).isEqualTo("decimal(6,2) NULL");
    }

    @Test
    @DisplayName("el CHECK de cada ajuste acepta -99,99, 1000 y NULL, y rechaza -100 y 1000,01")
    void checkDeLosAjustes() {
        Long f = ferreteriaId("30-78100001-1", "Migracion V6 ajustes");

        for (int n = 1; n <= 3; n++) {
            String columna = "ajuste" + n;
            insertarConAjuste(f, "V6-A" + n + "-MIN", columna, new BigDecimal("-99.99"));
            insertarConAjuste(f, "V6-A" + n + "-MAX", columna, new BigDecimal("1000"));
            insertarConAjuste(f, "V6-A" + n + "-NULL", columna, null);
            int indice = n;
            assertThatThrownBy(() -> insertarConAjuste(f, "V6-A" + indice + "-M100", columna, new BigDecimal("-100")))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_articulo_ajuste" + n);
            assertThatThrownBy(() -> insertarConAjuste(f, "V6-A" + indice + "-M1000", columna, new BigDecimal("1000.01")))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_articulo_ajuste" + n);

            assertThat(jdbc.queryForList(
                "select coalesce(cast(" + columna + " as char), 'NULL') from articulo "
                    + "where ferreteria_id = ? and codigo_interno like ?", String.class, f, "V6-A" + n + "-%"))
                .containsExactlyInAnyOrder("-99.99", "1000.00", "NULL");
        }
    }

    // ---- articulo.ultima_importacion_id ----

    @Test
    @DisplayName("articulo.ultima_importacion_id es BIGINT NULL con indice (ferreteria_id, ultima_importacion_id)")
    void ultimaImportacion() {
        assertThat(tipoYNulabilidad("articulo", "ultima_importacion_id")).isEqualTo("bigint NULL");
        assertThat(columnasDelIndice("articulo", "ix_articulo_ultima_importacion"))
            .containsExactly("ferreteria_id", "ultima_importacion_id");
    }

    @Test
    @DisplayName("ultima_importacion_id tiene FK a importacion_lista(id): un id inexistente falla y uno real entra")
    void fkDeLaUltimaImportacion() {
        Long f = ferreteriaId("30-78100002-2", "Migracion V6 fk");
        Long importacionId = insertarImportacion(f);

        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.key_column_usage where table_schema = database() "
                + "and table_name = 'articulo' and column_name = 'ultima_importacion_id' "
                + "and referenced_table_name = 'importacion_lista' and referenced_column_name = 'id'",
            Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
            "insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "ultima_importacion_id) values (?, 'V6-FK-MAL', 'Articulo', 10.00, 17.00, 999999999)", f))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "ultima_importacion_id) values (?, 'V6-FK-BIEN', 'Articulo', 10.00, 17.00, ?)", f, importacionId);
        assertThat(jdbc.queryForObject(
            "select ultima_importacion_id from articulo where ferreteria_id = ? and codigo_interno = 'V6-FK-BIEN'",
            Long.class, f)).isEqualTo(importacionId);
    }

    // ---- historial_precio.moneda_anterior ----

    @Test
    @DisplayName("historial_precio.moneda_anterior es VARCHAR(3) NULL: solo se llena en un cambio de moneda")
    void monedaAnteriorDelHistorial() {
        assertThat(tipoYNulabilidad("historial_precio", "moneda_anterior")).isEqualTo("varchar(3) NULL");

        Long f = ferreteriaId("30-78100003-3", "Migracion V6 historial");
        Long articuloId = insertarArticulo(f, "V6-HIS");
        Long usuarioId = insertarUsuario(f, "v6-his@test.local");
        jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_anterior, costo_nuevo, "
            + "precio_anterior, precio_nuevo, origen, moneda, moneda_anterior, usuario_id) "
            + "values (?, ?, 1000.00, 10.00, 1580.00, 15730.00, 'IMPORTACION', 'USD', 'ARS', ?)", f, articuloId, usuarioId);
        jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_nuevo, precio_nuevo, origen, "
            + "usuario_id) values (?, ?, 10.00, 15730.00, 'MANUAL', ?)", f, articuloId, usuarioId);

        List<Map<String, Object>> filas = jdbc.queryForList(
            "select origen, moneda, moneda_anterior from historial_precio where articulo_id = ? order by id", articuloId);
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).get("moneda")).isEqualTo("USD");
        assertThat(filas.get(0).get("moneda_anterior")).isEqualTo("ARS");
        assertThat(filas.get(1).get("moneda")).isEqualTo("ARS");
        assertThat(filas.get(1).get("moneda_anterior")).isNull();
    }

    // ---- importacion_lista: contadores nuevos ----

    @Test
    @DisplayName("importacion_lista.cambian_moneda, corrigen y salteadas son INT NOT NULL DEFAULT 0")
    void contadoresNuevos() {
        for (String columna : List.of("cambian_moneda", "corrigen", "salteadas")) {
            assertThat(tipoYNulabilidad("importacion_lista", columna)).isEqualTo("int NOT NULL");
            assertThat(columna("importacion_lista", columna, "column_default")).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("una importacion insertada sin los contadores nuevos los deja en 0")
    void contadoresPorDefecto() {
        Long f = ferreteriaId("30-78100004-4", "Migracion V6 contadores");
        Long importacionId = insertarImportacion(f);

        Map<String, Object> fila = jdbc.queryForMap(
            "select cambian_moneda, corrigen, salteadas from importacion_lista where id = ?", importacionId);
        assertThat(((Number) fila.get("cambian_moneda")).intValue()).isZero();
        assertThat(((Number) fila.get("corrigen")).intValue()).isZero();
        assertThat(((Number) fila.get("salteadas")).intValue()).isZero();
    }

    // ---- aditiva ----

    @Test
    @DisplayName("aditiva: los articulos que ya existian (los 8 de demo, ids 1 a 8) quedan con NULL en las cuatro columnas nuevas")
    void losExistentesQuedanEnNull() {
        // La base se comparte y no se revierte: las importaciones de otros tests marcan sus propios articulos, asi que
        // solo se miran los de db/dev-data/V1001 (ids 1 a 8), que son los que ya existian cuando corrio V6.
        Integer total = jdbc.queryForObject("select count(*) from articulo where id <= 8", Integer.class);
        Integer conAlgunValor = jdbc.queryForObject(
            "select count(*) from articulo where id <= 8 and (ajuste1 is not null "
                + "or ajuste2 is not null or ajuste3 is not null or ultima_importacion_id is not null)", Integer.class);

        assertThat(total).isPositive();
        assertThat(conAlgunValor).isZero();
    }

    @Test
    @DisplayName("aditiva: V1 a V26 estan aplicadas, en orden y sin fallas (los datos demo, V1000 en adelante, no cuentan)")
    void historialDeMigraciones() {
        List<Map<String, Object>> migraciones = jdbc.queryForList(
            "select version, success from flyway_schema_history where version is not null "
                + "and cast(version as unsigned) < 1000 order by installed_rank");

        assertThat(migraciones).extracting(m -> m.get("version"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
        assertThat(migraciones).allSatisfy(m -> assertThat(m.get("success")).isIn(true, 1));
    }

    @Test
    @DisplayName("aditiva: un articulo insertado como antes, sin las columnas nuevas, entra con NULL y el precio intacto")
    void insertarComoAntes() {
        Long f = ferreteriaId("30-78100005-5", "Migracion V6 aditiva");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final) "
            + "values (?, 'V6-ADI', 'Como antes', 100.00, 170.00)", f);

        Map<String, Object> fila = jdbc.queryForMap(
            "select ajuste1, ajuste2, ajuste3, ultima_importacion_id, precio_final from articulo "
                + "where ferreteria_id = ? and codigo_interno = 'V6-ADI'", f);
        assertThat(fila.get("ajuste1")).isNull();
        assertThat(fila.get("ajuste2")).isNull();
        assertThat(fila.get("ajuste3")).isNull();
        assertThat(fila.get("ultima_importacion_id")).isNull();
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("170");
    }

    // ---- las entidades leen y escriben las columnas nuevas ----

    @Test
    @DisplayName("la entidad Articulo guarda y relee los ajustes y la ultima importacion")
    void articuloConEntidad() {
        Long f = ferreteriaId("30-78100006-6", "Migracion V6 entidad articulo");
        Long importacionId = insertarImportacion(f);
        TenantContext.setCurrentFerreteriaId(f);

        Articulo nuevo = new Articulo(f);
        nuevo.actualizar(new DatosArticulo("V6-ENT", "7791234567890", null, "Articulo con ajustes", null, null,
                new BigDecimal("880.65"), null, AlicuotaIva.IVA_10_5, Unidad.METRO, BigDecimal.ZERO, null, true),
                new ContextoPrecio(null, new BigDecimal("30"), new BigDecimal("10")));
        nuevo.fijarAjustes(new Ajustes(new BigDecimal("-5"), new BigDecimal("3.5"), null));
        nuevo.marcarImportacion(importacionId);
        Articulo guardado = articuloRepository.save(nuevo);

        Articulo leido = articuloRepository.findScoped(guardado.getId()).orElseThrow();
        assertThat(leido.getAjuste1()).isEqualByComparingTo("-5");
        assertThat(leido.getAjuste2()).isEqualByComparingTo("3.5");
        assertThat(leido.getAjuste3()).isNull();
        assertThat(leido.getAjustes()).isEqualTo(new Ajustes(new BigDecimal("-5"), new BigDecimal("3.5"), null));
        assertThat(leido.getUltimaImportacionId()).isEqualTo(importacionId);
        Map<String, Object> fila = jdbc.queryForMap(
                "select ajuste1, ajuste2, ajuste3, ultima_importacion_id from articulo where id = ?", guardado.getId());
        assertThat((BigDecimal) fila.get("ajuste1")).isEqualByComparingTo("-5");
        assertThat((BigDecimal) fila.get("ajuste2")).isEqualByComparingTo("3.5");
        assertThat(fila.get("ajuste3")).isNull();
        assertThat(((Number) fila.get("ultima_importacion_id")).longValue()).isEqualTo(importacionId);
    }

    @Test
    @DisplayName("un articulo de antes de V6 se lee con ajustes en null y sin ultima importacion")
    void articuloAnteriorSeLeeSinAjustes() {
        Long f = ferreteriaId("30-78100007-7", "Migracion V6 entidad vieja");
        Long articuloId = insertarArticulo(f, "V6-VIEJO");
        TenantContext.setCurrentFerreteriaId(f);

        Articulo leido = articuloRepository.findScoped(articuloId).orElseThrow();

        assertThat(leido.getAjustes()).isEqualTo(Ajustes.NINGUNO);
        assertThat(leido.getUltimaImportacionId()).isNull();
    }

    @Test
    @DisplayName("la entidad HistorialPrecio guarda y relee la moneda anterior: IMPORTACION 1.000 ARS a 10 USD, $ 1.580 a $ 15.730")
    void historialConMonedaAnterior() {
        Long f = ferreteriaId("30-78100008-8", "Migracion V6 entidad historial");
        Long articuloId = insertarArticulo(f, "V6-HIS-ENT");
        jdbc.update("update articulo set moneda = 'USD' where id = ?", articuloId);
        TenantContext.setCurrentFerreteriaId(f);
        Articulo articulo = articuloRepository.findScoped(articuloId).orElseThrow();

        HistorialPrecio guardado = historialPrecioRepository.save(HistorialPrecio.de(articulo,
                new CambioPrecio(new BigDecimal("1000.00"), new BigDecimal("10.00"), new BigDecimal("1580.00"),
                        new BigDecimal("15730.00")),
                OrigenPrecio.IMPORTACION, null, Moneda.ARS));
        historialPrecioRepository.save(HistorialPrecio.de(articulo,
                new CambioPrecio(new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("15730.00"),
                        new BigDecimal("18880.00")),
                OrigenPrecio.IMPORTACION, null));

        List<HistorialPrecio> historial = historialPrecioRepository.findAllScoped().stream()
                .filter(h -> h.getArticuloId().equals(articuloId))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        assertThat(historial).hasSize(2);
        assertThat(historial.get(0).getId()).isEqualTo(guardado.getId());
        assertThat(historial.get(0).getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.get(0).getMonedaAnterior()).isEqualTo(Moneda.ARS);
        assertThat(historial.get(0).getCostoAnterior()).isEqualByComparingTo("1000");
        assertThat(historial.get(0).getCostoNuevo()).isEqualByComparingTo("10");
        assertThat(historial.get(0).getPrecioAnterior()).isEqualByComparingTo("1580");
        assertThat(historial.get(0).getPrecioNuevo()).isEqualByComparingTo("15730");
        assertThat(historial.get(1).getMoneda()).isEqualTo(Moneda.USD);
        assertThat(historial.get(1).getMonedaAnterior()).isNull();
    }

    @Test
    @DisplayName("las entradas de historial que ya existian (MANUAL, IMPORTACION, COTIZACION) se leen sin moneda anterior")
    void historialAnteriorSinMonedaAnterior() {
        Long f = ferreteriaId("30-78100009-9", "Migracion V6 historial viejo");
        Long articuloId = insertarArticulo(f, "V6-HIS-VIEJO");
        for (String origen : List.of("MANUAL", "IMPORTACION", "COTIZACION")) {
            jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_nuevo, precio_nuevo, origen, "
                    + "usuario_id) values (?, ?, 10.00, 17.00, ?, NULL)", f, articuloId, origen);
        }
        TenantContext.setCurrentFerreteriaId(f);

        List<HistorialPrecio> historial = historialPrecioRepository.findAllScoped().stream()
                .filter(h -> h.getArticuloId().equals(articuloId)).toList();

        assertThat(historial).extracting(HistorialPrecio::getOrigen)
                .containsExactlyInAnyOrder(OrigenPrecio.MANUAL, OrigenPrecio.IMPORTACION, OrigenPrecio.COTIZACION);
        assertThat(historial).allSatisfy(h -> assertThat(h.getMonedaAnterior()).isNull());
        assertThat(historial).allSatisfy(h -> assertThat(h.getMoneda()).isEqualTo(Moneda.ARS));
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
                + "where table_schema = database() and table_name = ? and index_name = ? order by seq_in_index",
            String.class, tabla, indice);
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
            + "values (?, ?, 'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social",
            razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long insertarArticulo(Long ferreteriaId, String codigoInterno) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final) "
            + "values (?, ?, 'Articulo de prueba', 10.00, 17.00) on duplicate key update descripcion = descripcion",
            ferreteriaId, codigoInterno);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
            Long.class, ferreteriaId, codigoInterno);
    }

    private void insertarConAjuste(Long ferreteriaId, String codigoInterno, String columna, BigDecimal valor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, " + columna
            + ") values (?, ?, 'Articulo con ajuste', 10.00, 17.00, ?) "
            + "on duplicate key update " + columna + " = values(" + columna + ")",
            ferreteriaId, codigoInterno, valor);
    }

    private Long insertarUsuario(Long ferreteriaId, String email) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
            + "values (?, ?, 'sin-login', 'Usuario de prueba', 'DUENO') on duplicate key update nombre = nombre",
            ferreteriaId, email);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private Long insertarImportacion(Long ferreteriaId) {
        Long usuarioId = insertarUsuario(ferreteriaId, "v6-imp-" + ferreteriaId + "@test.local");
        String proveedor = "Proveedor V6 " + ferreteriaId;
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, proveedor);
        Long proveedorId = jdbc.queryForObject(
            "select max(id) from proveedor where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, proveedor);
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
            + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, 'v6.xlsx', 0, 0, 0, 0, 0, 0, 'APLICADA')",
            ferreteriaId, proveedorId, usuarioId);
        return jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ?", Long.class,
            ferreteriaId);
    }
}
