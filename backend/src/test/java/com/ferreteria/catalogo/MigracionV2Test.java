package com.ferreteria.catalogo;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migracion V2 (CU-03): configuracion de precios en ferreteria, proveedor, articulo e historial.
 * Levanta su propio MySQL con Testcontainers y deja que Hibernate valide (ddl-auto=validate) las
 * entidades contra el esquema que dejo Flyway: si una entidad no coincide con V2, el contexto no arranca.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MigracionV2Test {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FerreteriaRepository ferreteriaRepository;

    @Autowired
    ProveedorRepository proveedorRepository;

    @Autowired
    ArticuloRepository articuloRepository;

    @Autowired
    HistorialPrecioRepository historialPrecioRepository;

    @Test
    void lasFerreteriasExistentesRecibenLaConfiguracionPorDefecto() {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select margen_defecto_porcentaje from ferreteria");

        assertThat(filas).isNotEmpty();
        // El multiplo de $10 que dejaba V2 lo pasa a 0 la V9 (H11): lo prueba SinRedondeoIntegrationTest.
        assertThat(filas).allSatisfy(fila ->
                assertThat(fila.get("margen_defecto_porcentaje")).hasToString("30.00"));
    }

    @Test
    void existenLasTablasNuevas() {
        List<String> tablas = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()",
                String.class);

        assertThat(tablas).contains("proveedor", "articulo", "historial_precio");
    }

    @Test
    void laClaveUnicaDelCodigoDeProveedorEmpiezaPorFerreteriaYProveedor() {
        assertThat(columnasDelIndice("articulo", "uk_articulo_codigo_proveedor"))
                .containsExactly("ferreteria_id", "proveedor_id", "codigo_proveedor");
    }

    @Test
    void losIndicesEmpiezanPorFerreteriaId() {
        assertThat(columnasDelIndice("proveedor", "uk_proveedor_nombre"))
                .containsExactly("ferreteria_id", "nombre");
        assertThat(columnasDelIndice("articulo", "uk_articulo_codigo_interno"))
                .containsExactly("ferreteria_id", "codigo_interno");
        assertThat(columnasDelIndice("articulo", "ix_articulo_codigo_barras"))
                .containsExactly("ferreteria_id", "codigo_barras");
        assertThat(columnasDelIndice("articulo", "ix_articulo_rubro"))
                .containsExactly("ferreteria_id", "rubro_id");
        assertThat(columnasDelIndice("historial_precio", "ix_historial_articulo"))
                .containsExactly("ferreteria_id", "articulo_id", "fecha");
    }

    @Test
    void lasTablasDeNegocioExigenFerreteriaYLosMontosSonDecimal() {
        assertThat(columna("proveedor", "ferreteria_id", "is_nullable")).isEqualTo("NO");
        assertThat(columna("articulo", "ferreteria_id", "is_nullable")).isEqualTo("NO");
        assertThat(columna("historial_precio", "ferreteria_id", "is_nullable")).isEqualTo("NO");

        assertThat(columna("articulo", "costo", "column_type")).isEqualTo("decimal(15,2)");
        assertThat(columna("articulo", "precio_final", "column_type")).isEqualTo("decimal(15,2)");
        assertThat(columna("articulo", "stock_actual", "column_type")).isEqualTo("decimal(15,3)");
        assertThat(columna("historial_precio", "costo_nuevo", "column_type")).isEqualTo("decimal(15,2)");
        assertThat(columna("historial_precio", "precio_nuevo", "column_type")).isEqualTo("decimal(15,2)");
        // Alta: los valores anteriores no existen todavia.
        assertThat(columna("historial_precio", "costo_anterior", "is_nullable")).isEqualTo("YES");
        assertThat(columna("historial_precio", "precio_anterior", "is_nullable")).isEqualTo("YES");
    }

    @Test
    void elCodigoInternoEsUnicoPorFerreteriaPeroSePuedeRepetirEntreFerreterias() {
        Long a = ferreteriaId("30-70000001-1", "Migracion A");
        Long b = ferreteriaId("30-70000002-2", "Migracion B");

        insertarArticulo(a, "UNI-001", null, null);
        insertarArticulo(b, "UNI-001", null, null);

        assertThatThrownBy(() -> insertarArticulo(a, "UNI-001", null, null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void elCodigoDeProveedorChocaSoloConProveedorYCodigo() {
        Long f = ferreteriaId("30-70000003-3", "Migracion C");
        Long p1 = proveedorId(f, "Proveedor 1");
        Long p2 = proveedorId(f, "Proveedor 2");

        insertarArticulo(f, "PRV-001", p1, "AB-12");
        insertarArticulo(f, "PRV-002", p2, "AB-12");
        // Sin proveedor o sin codigo, NULL no choca.
        insertarArticulo(f, "PRV-003", null, "AB-12");
        insertarArticulo(f, "PRV-004", null, "AB-12");
        insertarArticulo(f, "PRV-005", p1, null);
        insertarArticulo(f, "PRV-006", p1, null);

        assertThatThrownBy(() -> insertarArticulo(f, "PRV-007", p1, "AB-12"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void lasAlicuotasDeIvaSonLasDeArca() {
        assertThat(AlicuotaIva.values()).extracting(a -> a.porcentaje().toPlainString())
                .containsExactly("0.00", "2.50", "5.00", "10.50", "21.00", "27.00");
    }

    @Test
    void laUnidadEsUnaListaFija() {
        assertThat(Unidad.values()).extracting(Enum::name)
                .containsExactly("UNIDAD", "METRO", "KILO", "LITRO");
    }

    @Test
    void elOrigenDelPrecioTieneLosCincoValores() {
        // COTIZACION se suma con el corte de moneda y cotizacion (V5) y COMPRA con CU-13 (V23): origen es VARCHAR(20),
        // sin CHECK.
        assertThat(OrigenPrecio.values()).extracting(Enum::name)
                .containsExactly("IMPORTACION", "MASIVO", "MANUAL", "COTIZACION", "COMPRA");
    }

    @Test
    void laFerreteriaExponeSuConfiguracionDePrecios() {
        Ferreteria ferreteria = ferreteriaRepository.findById(1L).orElseThrow();

        assertThat(ferreteria.getMargenDefectoPorcentaje()).isEqualByComparingTo("30");
        assertThat(ferreteria.getRedondeoMultiplo()).isEqualByComparingTo("10");
        assertThat(new ConfiguracionPrecios(ferreteria.getMargenDefectoPorcentaje(), ferreteria.getRedondeoMultiplo())
                .redondeoMultiplo()).isEqualByComparingTo("10");
    }

    @Test
    void lasEntidadesSeMapeanContraElEsquema() {
        Long f = ferreteriaId("30-70000004-4", "Migracion D");
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, 'mapeo-v2@test.local', 'x', 'Dueno', 'DUENO')", f);
        Long usuarioId = jdbc.queryForObject("select id from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into proveedor (ferreteria_id, nombre, cuit) values (?, 'Sinteplast', '30-12345678-9')", f);
        Long proveedorId = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, "
                + "descripcion, proveedor_id, costo, margen_porcentaje, alicuota_iva, precio_final, unidad, "
                + "stock_actual, stock_minimo, ubicacion) values (?, 'MAP-001', '7790001000012', 'AB-12', "
                + "'Cable', ?, 100.00, 35.00, 'IVA_10_5', 150.00, 'METRO', 12.500, 3.000, 'Estante 4')",
                f, proveedorId);
        Long articuloId = jdbc.queryForObject("select id from articulo where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into historial_precio (ferreteria_id, articulo_id, costo_nuevo, precio_nuevo, "
                + "origen, usuario_id) values (?, ?, 100.00, 150.00, 'MANUAL', ?)", f, articuloId, usuarioId);

        TenantContext.setCurrentFerreteriaId(f);
        try {
            Proveedor proveedor = proveedorRepository.findScoped(proveedorId).orElseThrow();
            assertThat(proveedor.getNombre()).isEqualTo("Sinteplast");
            assertThat(proveedor.getCuit()).isEqualTo("30-12345678-9");
            assertThat(proveedor.isActivo()).isTrue();
            assertThat(proveedor.getFerreteriaId()).isEqualTo(f);

            Articulo articulo = articuloRepository.findScoped(articuloId).orElseThrow();
            assertThat(articulo.getCodigoInterno()).isEqualTo("MAP-001");
            assertThat(articulo.getCodigoBarras()).isEqualTo("7790001000012");
            assertThat(articulo.getCodigoProveedor()).isEqualTo("AB-12");
            assertThat(articulo.getDescripcion()).isEqualTo("Cable");
            assertThat(articulo.getRubroId()).isNull();
            assertThat(articulo.getProveedorId()).isEqualTo(proveedorId);
            assertThat(articulo.getCosto()).isEqualByComparingTo("100");
            assertThat(articulo.getMargenPorcentaje()).isEqualByComparingTo("35");
            assertThat(articulo.getAlicuotaIva()).isEqualTo(AlicuotaIva.IVA_10_5);
            assertThat(articulo.getPrecioFinal()).isEqualByComparingTo("150");
            assertThat(articulo.getUnidad()).isEqualTo(Unidad.METRO);
            assertThat(articulo.getStockActual()).isEqualByComparingTo("12.5");
            assertThat(articulo.getStockMinimo()).isEqualByComparingTo("3");
            assertThat(articulo.getUbicacion()).isEqualTo("Estante 4");
            assertThat(articulo.isActivo()).isTrue();
            assertThat(articulo.getCreadoEn()).isNotNull();

            HistorialPrecio historial = historialPrecioRepository.findAllScoped().get(0);
            assertThat(historial.getArticuloId()).isEqualTo(articuloId);
            assertThat(historial.getCostoAnterior()).isNull();
            assertThat(historial.getCostoNuevo()).isEqualByComparingTo("100");
            assertThat(historial.getPrecioAnterior()).isNull();
            assertThat(historial.getPrecioNuevo()).isEqualByComparingTo("150");
            assertThat(historial.getOrigen()).isEqualTo(OrigenPrecio.MANUAL);
            assertThat(historial.getUsuarioId()).isEqualTo(usuarioId);
            assertThat(historial.getFecha()).isNotNull();
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> columnasDelIndice(String tabla, String indice) {
        return jdbc.queryForList(
                "select column_name from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ? "
                        + "order by seq_in_index",
                String.class, tabla, indice);
    }

    private String columna(String tabla, String columna, String atributo) {
        return jdbc.queryForObject(
                "select " + atributo + " from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?",
                String.class, tabla, columna);
    }

    private Long ferreteriaId(String cuit, String razonSocial) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", razonSocial, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long proveedorId(Long ferreteriaId, String nombre) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, nombre);
        return jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, nombre);
    }

    private void insertarArticulo(Long ferreteriaId, String codigoInterno, Long proveedorId, String codigoProveedor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_proveedor, descripcion, "
                        + "proveedor_id, costo, precio_final) values (?, ?, ?, 'Articulo de prueba', ?, 100.00, 170.00)",
                ferreteriaId, codigoInterno, codigoProveedor, proveedorId);
    }
}
