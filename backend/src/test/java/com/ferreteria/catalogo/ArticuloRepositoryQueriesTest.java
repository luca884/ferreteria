package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Consultas de ArticuloRepository contra MySQL real: busqueda con LIKE (mayusculas, comodines
 * escapados, inactivos), unicidades y herederos de margen de rubro, siempre acotadas al tenant.
 * Levanta su propio MySQL como MigracionV2Test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ArticuloRepositoryQueriesTest {

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

    private static final PageRequest POR_DESCRIPCION = PageRequest.of(0, 20, Sort.by("descripcion"));

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Consultas " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
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

    private Long articulo(Long ferreteriaId, String codigoInterno, String descripcion, String barras,
                          Long proveedorId, String codigoProveedor, Long rubroId, String margen, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, "
                        + "descripcion, rubro_id, proveedor_id, costo, margen_porcentaje, precio_final, activo) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 100.00, ?, 170.00, ?)",
                ferreteriaId, codigoInterno, barras, codigoProveedor, descripcion, rubroId, proveedorId, margen, activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigoInterno);
    }

    private static List<String> codigos(Page<Articulo> pagina) {
        return pagina.getContent().stream().map(Articulo::getCodigoInterno).toList();
    }

    /** Ferreteria con un catalogo chico y otra ferreteria con un articulo parecido que nunca debe aparecer. */
    private Long catalogoDeBusqueda(String cuit, String cuitAjena) {
        Long a = ferreteria(cuit);
        Long b = ferreteria(cuitAjena);
        Long prov = proveedor(a, "Bulonera");
        articulo(a, "TORN-001", "Tornillo hexagonal 1/4", "7790001000012", prov, "AB-12", null, null, true);
        articulo(a, "MART-001", "Martillo", null, null, null, null, null, true);
        articulo(a, "OLD-001", "Tornillo viejo", "7790001000099", null, null, null, null, false);
        articulo(a, "DESC-1", "Oferta 50% de descuento", null, null, null, null, null, true);
        articulo(a, "DESC-2", "Oferta 50x de descuento", null, null, null, null, null, true);
        articulo(b, "TORN-001", "Tornillo ajeno", "7790001000012", null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);
        return a;
    }

    @Test
    void sinFiltroDevuelveSoloLosActivosDeLaFerreteriaOrdenadosPorDescripcion() {
        catalogoDeBusqueda("30-71000001-1", "30-71000002-2");

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, false, POR_DESCRIPCION);

        assertThat(codigos(pagina)).containsExactly("MART-001", "DESC-1", "DESC-2", "TORN-001");
        assertThat(pagina.getTotalElements()).isEqualTo(4);
    }

    @Test
    void incluirInactivosTraeTambienLosDadosDeBaja() {
        catalogoDeBusqueda("30-71000003-3", "30-71000004-4");

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, true, POR_DESCRIPCION);

        assertThat(codigos(pagina)).contains("OLD-001").hasSize(5);
    }

    @Test
    void laBusquedaNoDistingueMayusculasYBuscaEnDescripcionYEnLosTresCodigos() {
        catalogoDeBusqueda("30-71000005-5", "30-71000006-6");

        assertThat(codigos(articuloRepository.buscarScoped("%TORNI%", false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%mart-0%", false, POR_DESCRIPCION)))
                .containsExactly("MART-001");
        assertThat(codigos(articuloRepository.buscarScoped("%7790001000012%", false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%ab-12%", false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
    }

    @Test
    void laBusquedaCombinadaConInactivosEncuentraElDadoDeBaja() {
        catalogoDeBusqueda("30-71000007-7", "30-71000008-8");

        assertThat(codigos(articuloRepository.buscarScoped("%torni%", false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%torni%", true, POR_DESCRIPCION)))
                .containsExactly("TORN-001", "OLD-001");
    }

    @Test
    void elComodinEscapadoSeBuscaComoTextoLiteral() {
        catalogoDeBusqueda("30-71000009-9", "30-71000010-0");

        // "%50\%%" = contiene "50%": no debe traer "50x".
        assertThat(codigos(articuloRepository.buscarScoped("%50\\%%", false, POR_DESCRIPCION)))
                .containsExactly("DESC-1");
        // Sin escapar, el % de "50%" seria un comodin y trae las dos ofertas.
        assertThat(codigos(articuloRepository.buscarScoped("%50%%", false, POR_DESCRIPCION)))
                .containsExactly("DESC-1", "DESC-2");
    }

    @Test
    void laPaginacionCuentaElTotalDeLaFerreteria() {
        catalogoDeBusqueda("30-71000011-1", "30-71000012-2");

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, false, PageRequest.of(1, 3, Sort.by("descripcion")));

        assertThat(pagina.getContent()).hasSize(1);
        assertThat(pagina.getTotalElements()).isEqualTo(4);
        assertThat(pagina.getTotalPages()).isEqualTo(2);
    }

    @Test
    void elCodigoInternoCuentaTambienLosInactivosYExcluyeAlPropio() {
        Long a = catalogoDeBusqueda("30-71000013-3", "30-71000014-4");
        Long propio = jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ? and codigo_interno = 'OLD-001'", Long.class, a);

        assertThat(articuloRepository.existeCodigoInternoScoped("OLD-001", null)).isTrue();
        assertThat(articuloRepository.existeCodigoInternoScoped("OLD-001", propio)).isFalse();
        assertThat(articuloRepository.existeCodigoInternoScoped("NO-EXISTE", null)).isFalse();
    }

    @Test
    void elCodigoInternoDeOtraFerreteriaNoCuenta() {
        Long a = ferreteria("30-71000015-5");
        Long b = ferreteria("30-71000016-6");
        articulo(b, "SOLO-B", "De B", null, null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        assertThat(articuloRepository.existeCodigoInternoScoped("SOLO-B", null)).isFalse();
    }

    @Test
    void elCodigoDeBarrasSoloCuentaEntreActivosYExcluyeAlPropio() {
        Long a = catalogoDeBusqueda("30-71000017-7", "30-71000018-8");
        Long tornillo = jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ? and codigo_interno = 'TORN-001'", Long.class, a);

        assertThat(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", null)).isTrue();
        assertThat(articuloRepository.existeCodigoBarrasActivoScoped("7790001000012", tornillo)).isFalse();
        // El barras del dado de baja no bloquea.
        assertThat(articuloRepository.existeCodigoBarrasActivoScoped("7790001000099", null)).isFalse();
    }

    @Test
    void elCodigoDeProveedorEsPorProveedorYExcluyeAlPropio() {
        Long a = catalogoDeBusqueda("30-71000019-9", "30-71000020-0");
        Long bulonera = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ? and nombre = 'Bulonera'", Long.class, a);
        Long otroProveedor = proveedor(a, "Otro proveedor");
        Long tornillo = jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ? and codigo_interno = 'TORN-001'", Long.class, a);

        assertThat(articuloRepository.existeCodigoProveedorScoped(bulonera, "AB-12", null)).isTrue();
        assertThat(articuloRepository.existeCodigoProveedorScoped(bulonera, "AB-12", tornillo)).isFalse();
        assertThat(articuloRepository.existeCodigoProveedorScoped(otroProveedor, "AB-12", null)).isFalse();
    }

    @Test
    void losHerederosSonLosActivosDelRubroSinMargenPropio() {
        Long a = ferreteria("30-71000021-1");
        Long b = ferreteria("30-71000022-2");
        Long rubroA = rubro(a, "Buloneria");
        Long otroRubroA = rubro(a, "Pintureria");
        Long rubroB = rubro(b, "Buloneria");
        articulo(a, "HER-1", "Hereda 1", null, null, null, rubroA, null, true);
        articulo(a, "HER-2", "Hereda 2", null, null, null, rubroA, null, true);
        articulo(a, "PROPIO", "Margen propio", null, null, null, rubroA, "40.00", true);
        articulo(a, "CERO", "Margen propio en cero", null, null, null, rubroA, "0.00", true);
        articulo(a, "BAJA", "Dado de baja", null, null, null, rubroA, null, false);
        articulo(a, "OTRO", "Otro rubro", null, null, null, otroRubroA, null, true);
        articulo(a, "SINRUBRO", "Sin rubro", null, null, null, null, null, true);
        articulo(b, "AJENO", "De otra ferreteria", null, null, null, rubroB, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        List<Articulo> herederos = articuloRepository.heredanMargenDeRubroScoped(rubroA);

        assertThat(herederos).extracting(Articulo::getCodigoInterno).containsExactlyInAnyOrder("HER-1", "HER-2");
        // Aunque se pida el rubro de B, el scope del tenant lo deja afuera.
        assertThat(articuloRepository.heredanMargenDeRubroScoped(rubroB)).isEmpty();
    }
}
