package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.tenant.TenantContext;
import java.util.ArrayList;
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

    // --- Importacion de listas (CU-04): artefactos de un proveedor y codigos internos ocupados ---

    @Test
    void deProveedorTraeActivosEInactivosSoloDeEseProveedorYEsaFerreteria() {
        Long a = ferreteria("30-71000023-3");
        Long b = ferreteria("30-71000024-4");
        Long provA1 = proveedor(a, "Proveedor 1");
        Long provA2 = proveedor(a, "Proveedor 2");
        Long provB = proveedor(b, "Proveedor 1");
        articulo(a, "P1-ACT", "Activo del 1", null, provA1, "C-1", null, null, true);
        articulo(a, "P1-INA", "Inactivo del 1", null, provA1, "C-2", null, null, false);
        articulo(a, "P1-SIN", "Del 1 sin codigo de proveedor", null, provA1, null, null, null, true);
        articulo(a, "P2-ACT", "Activo del 2", null, provA2, "C-1", null, null, true);
        articulo(a, "SIN-PROV", "Sin proveedor", null, null, null, null, null, true);
        articulo(b, "B-ACT", "De B", null, provB, "C-1", null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        List<Articulo> delProveedor = articuloRepository.deProveedorScoped(provA1);

        assertThat(delProveedor).extracting(Articulo::getCodigoInterno)
                .containsExactlyInAnyOrder("P1-ACT", "P1-INA", "P1-SIN");
        assertThat(articuloRepository.deProveedorScoped(provA2))
                .extracting(Articulo::getCodigoInterno).containsExactly("P2-ACT");
        // El proveedor de B pedido desde A no devuelve nada: el scope del tenant lo deja afuera.
        assertThat(articuloRepository.deProveedorScoped(provB)).isEmpty();
    }

    @Test
    void deProveedorDeUnProveedorSinArticulosDevuelveListaVacia() {
        Long a = ferreteria("30-71000025-5");
        Long vacio = proveedor(a, "Sin articulos");
        TenantContext.setCurrentFerreteriaId(a);

        assertThat(articuloRepository.deProveedorScoped(vacio)).isEmpty();
    }

    @Test
    void codigosInternosOcupadosDevuelveLosExistentesIncluidosLosInactivosComoEstanGuardados() {
        Long a = ferreteria("30-71000026-6");
        articulo(a, "VALV-1", "Valvula", null, null, null, null, null, true);
        articulo(a, "VALV-2", "Valvula baja", null, null, null, null, null, false);
        TenantContext.setCurrentFerreteriaId(a);

        List<String> ocupados = articuloRepository.codigosInternosOcupadosScoped(
                List.of("VALV-1", "VALV-2", "VALV-3"));

        assertThat(ocupados).containsExactlyInAnyOrder("VALV-1", "VALV-2");
    }

    @Test
    void codigosInternosOcupadosNoDistingueMayusculasNiAcentos() {
        Long a = ferreteria("30-71000027-7");
        articulo(a, "TÉE-10", "Te de 10", null, null, null, null, null, true);
        articulo(a, "ABC-1", "Abc", null, null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        List<String> ocupados = articuloRepository.codigosInternosOcupadosScoped(List.of("tee-10", "abc-1", "xyz-9"));

        // Vuelven tal como estan guardados.
        assertThat(ocupados).containsExactlyInAnyOrder("TÉE-10", "ABC-1");
    }

    @Test
    void codigosInternosOcupadosPartePorLotesDe1000() {
        Long a = ferreteria("30-71000028-8");
        articulo(a, "LOTE-0005", "Primer lote", null, null, null, null, null, true);
        articulo(a, "LOTE-1500", "Segundo lote", null, null, null, null, null, true);
        articulo(a, "LOTE-2499", "Tercer lote", null, null, null, null, null, true);
        articulo(a, "LOTE-FUERA", "No se pide", null, null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);
        List<String> pedidos = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            pedidos.add(String.format("LOTE-%04d", i));
        }

        List<String> ocupados = articuloRepository.codigosInternosOcupadosScoped(pedidos);

        assertThat(ocupados).containsExactlyInAnyOrder("LOTE-0005", "LOTE-1500", "LOTE-2499");
    }

    @Test
    void codigosInternosOcupadosEstaAcotadoAlTenantYAceptaListaVacia() {
        Long a = ferreteria("30-71000029-9");
        Long b = ferreteria("30-71000030-0");
        articulo(b, "SOLO-DE-B", "De B", null, null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        assertThat(articuloRepository.codigosInternosOcupadosScoped(List.of("SOLO-DE-B"))).isEmpty();
        assertThat(articuloRepository.codigosInternosOcupadosScoped(List.of())).isEmpty();
    }
}
