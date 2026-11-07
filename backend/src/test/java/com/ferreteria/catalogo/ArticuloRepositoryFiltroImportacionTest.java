package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.support.ExplainDelFiltro;
import com.ferreteria.support.SqlCapturado;
import com.ferreteria.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El filtro por importacion de la actualizacion masiva (corte 3, R-201) contra MySQL real: son los articulos cuya
 * ULTIMA importacion es esa, combinado con AND con proveedor, rubro, texto e inactivos, y usando el indice
 * (ferreteria_id, ultima_importacion_id). Ferreterias propias, bloque 30-78406xxx (subserie 0x).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ArticuloRepositoryFiltroImportacionTest {

    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ArticuloRepository articuloRepository;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Filtro importacion " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long usuario(Long ferreteriaId) {
        String email = "dueno" + ferreteriaId + "@filtro-importacion.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno', 'DUENO')", ferreteriaId, email, HASH_DEMO);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private Long proveedor(Long ferreteriaId, String nombre) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, nombre);
        return jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, nombre);
    }

    private Long rubro(Long ferreteriaId, String nombre) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, 35.00)",
                ferreteriaId, nombre);
        return jdbc.queryForObject(
                "select id from rubro where ferreteria_id = ? and nombre = ?", Long.class, ferreteriaId, nombre);
    }

    private Long importacion(Long ferreteriaId, Long usuarioId, Long proveedorId, String archivo) {
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
                + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 'APLICADA')",
                ferreteriaId, proveedorId, usuarioId, archivo);
        return jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ? and archivo = ?",
                Long.class, ferreteriaId, archivo);
    }

    private Long articulo(Long ferreteriaId, String codigo, String descripcion, Long proveedorId, Long rubroId,
                          Long importacionId, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, rubro_id, proveedor_id, costo, "
                + "precio_final, activo, ultima_importacion_id) values (?, ?, ?, ?, ?, 100.00, 170.00, ?, ?)",
                ferreteriaId, codigo, descripcion, rubroId, proveedorId, activo, importacionId);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }

    private static List<String> codigos(List<Articulo> articulos) {
        return articulos.stream().map(Articulo::getCodigoInterno).toList();
    }

    /** I1 toco por ultima vez a A y C; I2 (posterior) a B; D se cargo a mano; E es de otra ferreteria. */
    private record Escenario(Long ferreteriaId, Long proveedorP, Long proveedorQ, Long rubroTornilleria, Long i1,
                             Long i2) {
    }

    private Escenario escenario(String cuit, String cuitAjena) {
        Long fid = ferreteria(cuit);
        Long ajena = ferreteria(cuitAjena);
        Long usuario = usuario(fid);
        Long p = proveedor(fid, "P");
        Long q = proveedor(fid, "Q");
        Long tornilleria = rubro(fid, "Tornilleria");
        Long i1 = importacion(fid, usuario, p, "i1.csv");
        Long i2 = importacion(fid, usuario, p, "i2.csv");
        articulo(fid, "A", "Tornillo A", p, tornilleria, i1, true);
        articulo(fid, "B", "Tornillo B", p, tornilleria, i2, true);
        articulo(fid, "C", "Clavo C", p, null, i1, true);
        articulo(fid, "D", "Alta manual", p, null, null, true);
        Long iAjena = importacion(ajena, usuario(ajena), proveedor(ajena, "Ajeno"), "ajena.csv");
        articulo(ajena, "E", "Ajeno E", null, null, iAjena, true);
        TenantContext.setCurrentFerreteriaId(fid);
        return new Escenario(fid, p, q, tornilleria, i1, i2);
    }

    private FiltroArticulos filtro(Escenario e, Long proveedorId, Long rubroId, String q, boolean inactivos,
                                   Long importacionId) {
        return new FiltroArticulos(proveedorId, rubroId, q, inactivos, importacionId);
    }

    @Test
    void filtrarPorUnaImportacionTraeSoloLosQueEsaTocoPorUltimaVez() {
        Escenario e = escenario("30-78406001-1", "30-78406002-2");

        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, false, e.i1()))))
                .containsExactly("A", "C");
        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, false, e.i2()))))
                .containsExactly("B");
    }

    @Test
    void sinImportacionNoFiltraYEntranTodosLosActivosDeLaFerreteria() {
        Escenario e = escenario("30-78406003-3", "30-78406004-4");

        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, false, null))))
                .containsExactly("A", "B", "C", "D");
    }

    @Test
    void unArticuloDeAltaManualOSinImportacionNuncaEntra() {
        Escenario e = escenario("30-78406005-5", "30-78406006-6");

        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, true, e.i1()))))
                .doesNotContain("D");
        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, true, e.i2()))))
                .doesNotContain("D");
    }

    @Test
    void seCombinaConProveedorConAnd() {
        Escenario e = escenario("30-78406007-7", "30-78406008-8");

        assertThat(articuloRepository.filtrarScoped(filtro(e, e.proveedorQ(), null, null, false, e.i1()))).isEmpty();
        assertThat(articuloRepository.contarScoped(filtro(e, e.proveedorQ(), null, null, false, e.i1()))).isZero();
        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, e.proveedorP(), null, null, false, e.i1()))))
                .containsExactly("A", "C");
    }

    @Test
    void seCombinaConRubroYConTexto() {
        Escenario e = escenario("30-78406009-9", "30-78406010-0");

        assertThat(codigos(articuloRepository.filtrarScoped(
                filtro(e, null, e.rubroTornilleria(), null, false, e.i1())))).containsExactly("A");
        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, "clavo", false, e.i1()))))
                .containsExactly("C");
        assertThat(articuloRepository.filtrarScoped(filtro(e, null, null, "clavo", false, e.i2()))).isEmpty();
    }

    @Test
    void losInactivosEntranSoloConIncluirInactivos() {
        Escenario e = escenario("30-78406011-1", "30-78406012-2");
        jdbc.update("update articulo set activo = false where ferreteria_id = ? and codigo_interno = 'A'",
                e.ferreteriaId());

        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, false, e.i1()))))
                .containsExactly("C");
        assertThat(codigos(articuloRepository.filtrarScoped(filtro(e, null, null, null, true, e.i1()))))
                .containsExactly("A", "C");
    }

    @Test
    void contarYFiltrarDanLoMismo() {
        Escenario e = escenario("30-78406013-3", "30-78406014-4");

        for (Long importacion : new Long[] {e.i1(), e.i2(), null}) {
            FiltroArticulos filtro = filtro(e, null, null, null, false, importacion);
            assertThat(articuloRepository.contarScoped(filtro))
                    .isEqualTo(articuloRepository.filtrarScoped(filtro).size());
        }
        assertThat(articuloRepository.contarScoped(filtro(e, null, null, null, false, e.i1()))).isEqualTo(2);
    }

    @Test
    void unaImportacionDeOtraFerreteriaNoTraeNada() {
        Escenario e = escenario("30-78406015-5", "30-78406016-6");
        Long deLaAjena = jdbc.queryForObject("select max(id) from importacion_lista where archivo = 'ajena.csv'",
                Long.class);

        assertThat(articuloRepository.filtrarScoped(filtro(e, null, null, null, true, deLaAjena))).isEmpty();
        assertThat(articuloRepository.contarScoped(filtro(e, null, null, null, true, deLaAjena))).isZero();
    }

    @Test
    void elListadoIgnoraElFiltroPorImportacion() {
        Escenario e = escenario("30-78406017-7", "30-78406018-8");

        assertThat(codigos(articuloRepository.buscarScoped(null, null, false, PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("codigoInterno"))).getContent()))
                .containsExactly("A", "B", "C", "D");
    }

    // ---- EXPLAIN sobre el SQL real (lote 5b, T-248, C-226) ----

    private static FiltroArticulos filtroPorImportacion(Long importacionId) {
        return new FiltroArticulos(null, null, null, false, importacionId);
    }

    private static Map<String, Object> valores(Long ferreteriaId, Long importacionId) {
        Map<String, Object> valores = new HashMap<>();
        valores.put("fid", ferreteriaId);
        valores.put("proveedorId", null);
        valores.put("rubroId", null);
        valores.put("importacionId", importacionId);
        valores.put("q", null);
        valores.put("incluirInactivos", false);
        return valores;
    }

    @Test
    @DisplayName("SqlCapturado devuelve el SQL real del filtro, con ultima_importacion_id y los ? is null")
    void capturaElSqlRealDelFiltro() {
        Escenario e = escenario("30-78406021-1", "30-78406022-2");
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());

        List<String> sql = SqlCapturado.durante(
                () -> articuloRepository.filtrarScoped(filtroPorImportacion(e.i1())));

        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).startsWith("select").contains("from articulo")
                .contains("ultima_importacion_id").contains("? is null");
    }

    @Test
    @DisplayName("afuera de durante no captura nada")
    void afueraDeDuranteNoCapturaNada() {
        Escenario e = escenario("30-78406023-3", "30-78406024-4");
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());
        articuloRepository.filtrarScoped(filtroPorImportacion(e.i1()));

        List<String> adentro = SqlCapturado.durante(() -> { });
        articuloRepository.filtrarScoped(filtroPorImportacion(e.i1()));

        assertThat(adentro).isEmpty();
        assertThat(SqlCapturado.durante(() -> { })).isEmpty();
    }

    @Test
    void laConsultaRealUsaUnIndicePorImportacion() {
        Escenario e = escenario("30-78406019-9", "30-78406020-0");
        // Volumen suficiente para que el optimizador no prefiera un recorrido completo.
        for (int i = 0; i < 300; i++) {
            articulo(e.ferreteriaId(), "R" + i, "Relleno " + i, e.proveedorP(), null, e.i2(), true);
        }
        jdbc.execute("analyze table articulo");
        TenantContext.setCurrentFerreteriaId(e.ferreteriaId());
        List<String> sql = SqlCapturado.durante(
                () -> articuloRepository.filtrarScoped(filtroPorImportacion(e.i1())));

        List<Map<String, Object>> plan = ExplainDelFiltro.explain(jdbc, sql, valores(e.ferreteriaId(), e.i1()));

        assertThat(plan).hasSize(1);
        // El optimizador puede elegir este indice o el de la clave foranea (solo ultima_importacion_id): los dos
        // buscan por la importacion; lo que no puede pasar es un recorrido completo de la tabla.
        assertThat(plan.get(0).get("possible_keys").toString()).contains("ix_articulo_ultima_importacion");
        assertThat(plan.get(0).get("type")).isEqualTo("ref");
        assertThat(plan.get(0).get("key").toString()).endsWith("ultima_importacion");
    }
}
