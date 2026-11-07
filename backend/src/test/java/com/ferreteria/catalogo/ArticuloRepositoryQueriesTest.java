package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, null, false, POR_DESCRIPCION);

        assertThat(codigos(pagina)).containsExactly("MART-001", "DESC-1", "DESC-2", "TORN-001");
        assertThat(pagina.getTotalElements()).isEqualTo(4);
    }

    @Test
    void incluirInactivosTraeTambienLosDadosDeBaja() {
        catalogoDeBusqueda("30-71000003-3", "30-71000004-4");

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, null, true, POR_DESCRIPCION);

        assertThat(codigos(pagina)).contains("OLD-001").hasSize(5);
    }

    @Test
    void laBusquedaNoDistingueMayusculasYBuscaEnDescripcionYEnLosTresCodigos() {
        catalogoDeBusqueda("30-71000005-5", "30-71000006-6");

        assertThat(codigos(articuloRepository.buscarScoped("%TORNI%", null, false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%mart-0%", null, false, POR_DESCRIPCION)))
                .containsExactly("MART-001");
        assertThat(codigos(articuloRepository.buscarScoped("%7790001000012%", null, false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%ab-12%", null, false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
    }

    @Test
    void laBusquedaCombinadaConInactivosEncuentraElDadoDeBaja() {
        catalogoDeBusqueda("30-71000007-7", "30-71000008-8");

        assertThat(codigos(articuloRepository.buscarScoped("%torni%", null, false, POR_DESCRIPCION)))
                .containsExactly("TORN-001");
        assertThat(codigos(articuloRepository.buscarScoped("%torni%", null, true, POR_DESCRIPCION)))
                .containsExactly("TORN-001", "OLD-001");
    }

    @Test
    void elComodinEscapadoSeBuscaComoTextoLiteral() {
        catalogoDeBusqueda("30-71000009-9", "30-71000010-0");

        // "%50\%%" = contiene "50%": no debe traer "50x".
        assertThat(codigos(articuloRepository.buscarScoped("%50\\%%", null, false, POR_DESCRIPCION)))
                .containsExactly("DESC-1");
        // Sin escapar, el % de "50%" seria un comodin y trae las dos ofertas.
        assertThat(codigos(articuloRepository.buscarScoped("%50%%", null, false, POR_DESCRIPCION)))
                .containsExactly("DESC-1", "DESC-2");
    }

    @Test
    void laPaginacionCuentaElTotalDeLaFerreteria() {
        catalogoDeBusqueda("30-71000011-1", "30-71000012-2");

        Page<Articulo> pagina = articuloRepository.buscarScoped(null, null, false, PageRequest.of(1, 3, Sort.by("descripcion")));

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

    // --- Importacion (corte 2): codigos de barras ocupados por articulos activos (bloque 30-7814xxxx) ---

    @Test
    void codigosDeBarrasOcupadosDevuelveSoloLosDeArticulosActivosDeLaFerreteria() {
        Long a = ferreteria("30-78140011-1");
        Long b = ferreteria("30-78140012-2");
        articulo(a, "BAR-1", "Activo", "7791000000011", null, null, null, null, true);
        articulo(a, "BAR-2", "Inactivo", "7791000000028", null, null, null, null, false);
        articulo(a, "BAR-3", "Sin barras", null, null, null, null, null, true);
        articulo(b, "BAR-B", "De B", "7791000000035", null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        List<String> ocupados = articuloRepository.codigosBarrasActivosOcupadosScoped(
                List.of("7791000000011", "7791000000028", "7791000000035", "7791000000042"));

        // El inactivo no cuenta (igual que en el alta manual), el de B tampoco y el que nadie tiene, menos.
        assertThat(ocupados).containsExactly("7791000000011");
    }

    @Test
    void codigosDeBarrasOcupadosAceptaListaVaciaYNoVeLosDeOtraFerreteria() {
        Long a = ferreteria("30-78140013-3");
        Long b = ferreteria("30-78140014-4");
        articulo(b, "BAR-B", "De B", "7791000000066", null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);

        assertThat(articuloRepository.codigosBarrasActivosOcupadosScoped(List.of("7791000000066"))).isEmpty();
        assertThat(articuloRepository.codigosBarrasActivosOcupadosScoped(List.of())).isEmpty();
    }

    @Test
    void codigosDeBarrasOcupadosPartePorLotesDe1000() {
        Long a = ferreteria("30-78140015-5");
        articulo(a, "LOTE-B-5", "Primer lote", "9000000000005", null, null, null, null, true);
        articulo(a, "LOTE-B-1500", "Segundo lote", "9000000001500", null, null, null, null, true);
        articulo(a, "LOTE-B-2499", "Tercer lote", "9000000002499", null, null, null, null, true);
        articulo(a, "LOTE-B-FUERA", "No se pide", "8999999999999", null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);
        List<String> pedidos = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            pedidos.add(String.format("9%012d", i));
        }

        List<String> ocupados = articuloRepository.codigosBarrasActivosOcupadosScoped(pedidos);

        assertThat(ocupados).containsExactlyInAnyOrder("9000000000005", "9000000001500", "9000000002499");
    }

    @Test
    void codigosDeBarrasOcupadosSinTenantFalla() {
        TenantContext.clear();

        assertThatThrownBy(() -> articuloRepository.codigosBarrasActivosOcupadosScoped(List.of("7791000000011")))
                .isInstanceOf(MissingTenantException.class);
    }

    // --- Actualizacion masiva (CU-05): filtrarScoped / contarScoped con el filtro compartido ---

    /** Ids de los articulos de las dos ferreterias del catalogo de filtro, para armar los filtros. */
    private record CatalogoDeFiltro(Long ferreteriaA, Long provP1, Long provP2, Long rubroR1, Long rubroR2,
                                    Long provB, Long rubroB) {}

    /**
     * A tiene 2 proveedores, 2 rubros y 10 articulos (uno dado de baja); B tiene otro proveedor, otro rubro
     * y 3 articulos, con textos parecidos. El tenant queda en A. Insertados en orden de id.
     * <pre>
     * F-01 Tornillo hexagonal   P1 R1      F-06 Descuento 50%     P2 -
     * F-02 Tuerca hexagonal     P1 R1      F-07 Descuento 50x     P2 -
     * F-03 Cable unipolar       P1 R2      F-08 Sin proveedor     -  -
     * F-04 Pintura latex        P2 R2      F-09 Cable_negro       -  -
     * F-05 Tornillo viejo (baja) P1 R1     F-10 CableXnegro       -  -
     * </pre>
     */
    private CatalogoDeFiltro catalogoDeFiltro(String cuit, String cuitAjena) {
        Long a = ferreteria(cuit);
        Long b = ferreteria(cuitAjena);
        Long p1 = proveedor(a, "Bulonera");
        Long p2 = proveedor(a, "Pinturas");
        Long r1 = rubro(a, "Buloneria");
        Long r2 = rubro(a, "Pintureria");
        Long provB = proveedor(b, "Bulonera");
        Long rubroB = rubro(b, "Buloneria");
        articulo(a, "F-01", "Tornillo hexagonal", null, p1, null, r1, null, true);
        articulo(a, "F-02", "Tuerca hexagonal", null, p1, null, r1, null, true);
        articulo(a, "F-03", "Cable unipolar", null, p1, null, r2, null, true);
        articulo(a, "F-04", "Pintura latex", null, p2, null, r2, null, true);
        articulo(a, "F-05", "Tornillo viejo", null, p1, null, r1, null, false);
        articulo(a, "F-06", "Descuento 50% oferta", null, p2, null, null, null, true);
        articulo(a, "F-07", "Descuento 50x oferta", null, p2, null, null, null, true);
        articulo(a, "F-08", "Sin proveedor", null, null, null, null, null, true);
        articulo(a, "F-09", "Cable_negro", null, null, null, null, null, true);
        articulo(a, "F-10", "CableXnegro", null, null, null, null, null, true);
        articulo(b, "B-01", "Tornillo ajeno", null, provB, null, rubroB, null, true);
        articulo(b, "B-02", "Cable ajeno", null, provB, null, rubroB, null, true);
        articulo(b, "B-03", "Sin proveedor ajeno", null, null, null, null, null, true);
        TenantContext.setCurrentFerreteriaId(a);
        return new CatalogoDeFiltro(a, p1, p2, r1, r2, provB, rubroB);
    }

    private static List<String> codigos(List<Articulo> articulos) {
        return articulos.stream().map(Articulo::getCodigoInterno).toList();
    }

    @Test
    void filtrarPorProveedorTraeSoloLosActivosDeEseProveedorEnOrdenDeId() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000031-1", "30-71000032-2");

        List<Articulo> p1 = articuloRepository.filtrarScoped(new FiltroArticulos(c.provP1(), null, null, false));
        List<Articulo> p2 = articuloRepository.filtrarScoped(new FiltroArticulos(c.provP2(), null, null, false));

        // F-05 es de P1 pero esta dado de baja.
        assertThat(codigos(p1)).containsExactly("F-01", "F-02", "F-03");
        assertThat(codigos(p2)).containsExactly("F-04", "F-06", "F-07");
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(c.provP1(), null, null, false))).isEqualTo(3);
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(c.provP2(), null, null, false))).isEqualTo(3);
    }

    @Test
    void filtrarPorRubroTraeSoloLosActivosDeEseRubro() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000033-3", "30-71000034-4");

        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, c.rubroR1(), null, false))))
                .containsExactly("F-01", "F-02");
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, c.rubroR2(), null, false))))
                .containsExactly("F-03", "F-04");
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(null, c.rubroR1(), null, false))).isEqualTo(2);
    }

    @Test
    void proveedorYRubroYTextoSeCombinanConAnd() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000035-5", "30-71000036-6");

        FiltroArticulos soloProveedor = new FiltroArticulos(c.provP1(), null, null, false);
        FiltroArticulos proveedorYRubro = new FiltroArticulos(c.provP1(), c.rubroR1(), null, false);
        FiltroArticulos lostres = new FiltroArticulos(c.provP1(), c.rubroR1(), "hexagonal", false);
        FiltroArticulos lostresMasAcotado = new FiltroArticulos(c.provP1(), c.rubroR1(), "tuerca", false);
        FiltroArticulos sinCoincidencia = new FiltroArticulos(c.provP1(), c.rubroR2(), "tuerca", false);

        assertThat(codigos(articuloRepository.filtrarScoped(soloProveedor))).containsExactly("F-01", "F-02", "F-03");
        assertThat(codigos(articuloRepository.filtrarScoped(proveedorYRubro))).containsExactly("F-01", "F-02");
        assertThat(codigos(articuloRepository.filtrarScoped(lostres))).containsExactly("F-01", "F-02");
        assertThat(codigos(articuloRepository.filtrarScoped(lostresMasAcotado))).containsExactly("F-02");
        // P1 y "tuerca" existen por separado (F-02), pero F-02 es del rubro R1, no del R2: el AND lo deja afuera.
        assertThat(articuloRepository.filtrarScoped(sinCoincidencia)).isEmpty();
        assertThat(articuloRepository.contarScoped(sinCoincidencia)).isZero();
        assertThat(articuloRepository.contarScoped(lostresMasAcotado)).isEqualTo(1);
    }

    @Test
    void losComodinesDelTextoSeBuscanComoTextoLiteral() {
        catalogoDeFiltro("30-71000037-7", "30-71000038-8");

        // "50%" es literal: trae F-06 y no F-07 ("50x").
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, null, "50%", false))))
                .containsExactly("F-06");
        // "e_n" es literal: trae "Cable_negro" y no "CableXnegro".
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, null, "e_n", false))))
                .containsExactly("F-09");
        // Control: "50x" y "eXn" si traen su articulo, asi que la busqueda corre.
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, null, "50x", false))))
                .containsExactly("F-07");
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(null, null, "e_n", false))).isEqualTo(1);
    }

    @Test
    void losDadosDeBajaQuedanAfueraPorDefectoYEntranConLaBandera() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000039-9", "30-71000040-0");

        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(c.provP1(), null, null, false))))
                .doesNotContain("F-05");
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(c.provP1(), null, null, true))))
                .containsExactly("F-01", "F-02", "F-03", "F-05");
        assertThat(codigos(articuloRepository.filtrarScoped(new FiltroArticulos(null, null, "tornillo", true))))
                .containsExactly("F-01", "F-05");
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(c.provP1(), null, null, true))).isEqualTo(4);
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(c.provP1(), null, null, false))).isEqualTo(3);
    }

    @Test
    void filtrarDevuelveLosArticulosEnOrdenAscendenteDeId() {
        catalogoDeFiltro("30-71000041-1", "30-71000042-2");

        List<Articulo> todos = articuloRepository.filtrarScoped(new FiltroArticulos(null, null, null, true));

        assertThat(todos).hasSize(10);
        assertThat(todos).extracting(Articulo::getId).isSorted();
        assertThat(codigos(todos)).containsExactly(
                "F-01", "F-02", "F-03", "F-04", "F-05", "F-06", "F-07", "F-08", "F-09", "F-10");
    }

    @Test
    void contarCoincideConElTamanoDeFiltrar() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000043-3", "30-71000044-4");
        List<FiltroArticulos> filtros = List.of(
                new FiltroArticulos(null, null, null, false),
                new FiltroArticulos(null, null, null, true),
                new FiltroArticulos(c.provP1(), null, null, false),
                new FiltroArticulos(null, c.rubroR2(), null, false),
                new FiltroArticulos(c.provP2(), c.rubroR2(), null, false),
                new FiltroArticulos(null, null, "cable", false),
                new FiltroArticulos(c.provP1(), c.rubroR1(), "hexagonal", true));
        List<Long> esperados = List.of(9L, 10L, 3L, 2L, 1L, 3L, 2L);

        List<Long> contados = filtros.stream().map(articuloRepository::contarScoped).toList();
        List<Long> cargados = filtros.stream().map(f -> (long) articuloRepository.filtrarScoped(f).size()).toList();

        assertThat(contados).containsExactlyElementsOf(esperados);
        assertThat(cargados).containsExactlyElementsOf(esperados);
    }

    @Test
    void elProveedorOElRubroDeOtraFerreteriaNoTraeNadaAunqueExistan() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000045-5", "30-71000046-6");
        Long articulosDeB = jdbc.queryForObject(
                "select count(*) from articulo where proveedor_id = ? and rubro_id = ?",
                Long.class, c.provB(), c.rubroB());

        // B si tiene articulos de ese proveedor y rubro (control), pero desde A no se ven.
        assertThat(articulosDeB).isEqualTo(2);
        assertThat(articuloRepository.filtrarScoped(new FiltroArticulos(c.provB(), null, null, true))).isEmpty();
        assertThat(articuloRepository.filtrarScoped(new FiltroArticulos(null, c.rubroB(), null, true))).isEmpty();
        assertThat(articuloRepository.filtrarScoped(new FiltroArticulos(c.provB(), c.rubroB(), "tornillo", true)))
                .isEmpty();
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(c.provB(), null, null, true))).isZero();
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(null, c.rubroB(), null, true))).isZero();
    }

    @Test
    void elFiltroVacioTraeSoloLosArticulosDeLaFerreteriaDelContexto() {
        CatalogoDeFiltro c = catalogoDeFiltro("30-71000047-7", "30-71000048-8");
        FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        List<Articulo> deA = articuloRepository.filtrarScoped(vacio);
        TenantContext.setCurrentFerreteriaId(jdbc.queryForObject(
                "select id from ferreteria where cuit = '30-71000048-8'", Long.class));
        List<Articulo> deB = articuloRepository.filtrarScoped(vacio);

        assertThat(deA).hasSize(9).extracting(Articulo::getFerreteriaId).containsOnly(c.ferreteriaA());
        assertThat(codigos(deA)).doesNotContain("B-01", "B-02", "B-03");
        assertThat(codigos(deB)).containsExactly("B-01", "B-02", "B-03");
        assertThat(articuloRepository.contarScoped(vacio)).isEqualTo(3);
    }

    @Test
    void filtrarSinFiltrosTraeElMismoContenidoQueBuscarSinTexto() {
        catalogoDeFiltro("30-71000049-9", "30-71000050-0");
        PageRequest todo = PageRequest.of(0, 100, Sort.by("id"));

        List<Articulo> filtrados = articuloRepository.filtrarScoped(new FiltroArticulos(null, null, null, false));
        Page<Articulo> buscados = articuloRepository.buscarScoped(null, null, false, todo);

        assertThat(filtrados).hasSize(9);
        assertThat(codigos(filtrados)).containsExactlyElementsOf(codigos(buscados));
        assertThat(articuloRepository.contarScoped(new FiltroArticulos(null, null, null, false)))
                .isEqualTo(buscados.getTotalElements());
    }

    // --- Paridad del criterio de busqueda entre el listado (buscarScoped) y el alcance masivo (filtrarScoped) ---

    @Test
    void elMismoTextoDevuelveLosMismosArticulosEnElListadoYEnElAlcanceMasivo() {
        Long a = ferreteria("30-71000053-3");
        articulo(a, "P-01", "Árbol de levas", null, null, null, null, null, true);
        articulo(a, "P-02", "Arbol de transmision", null, null, null, null, null, true);
        articulo(a, "P-03", "Oferta 50% de descuento", null, null, null, null, null, true);
        articulo(a, "P-04", "Oferta 50x de descuento", null, null, null, null, null, true);
        articulo(a, "P-05", "Ruta C:\\temp", null, null, null, null, null, true);
        articulo(a, "P-06", "Perfil_ranurado", null, null, "ZZ_9", null, null, true);
        articulo(a, "P-07", "PerfilXranurado", null, null, "ZZ9", null, null, true);
        articulo(a, "P-08", "Arbol viejo", "7790001000555", null, null, null, null, false);
        TenantContext.setCurrentFerreteriaId(a);
        PageRequest todo = PageRequest.of(0, 100, Sort.by("id"));
        // texto -> codigos esperados (los mismos en los dos caminos); incluye los inactivos
        Map<String, List<String>> esperados = new LinkedHashMap<>();
        esperados.put("ARBOL", List.of("P-01", "P-02", "P-08"));
        esperados.put("árbol", List.of("P-01", "P-02", "P-08"));
        esperados.put("  arbol de  ", List.of("P-01", "P-02"));
        esperados.put("50%", List.of("P-03"));
        esperados.put("c:\\t", List.of("P-05"));
        esperados.put("\\", List.of("P-05"));
        esperados.put("l_r", List.of("P-06"));
        esperados.put("zz_9", List.of("P-06"));
        esperados.put("7790001000555", List.of("P-08"));
        esperados.put("no existe", List.of());

        esperados.forEach((q, codigos) -> {
            FiltroArticulos filtro = new FiltroArticulos(null, null, q, true);

            Page<Articulo> listado = articuloRepository.buscarScoped(FiltroArticulos.patronDeBusqueda(q), null, true, todo);
            List<Articulo> alcance = articuloRepository.filtrarScoped(filtro);

            assertThat(codigos(listado)).as("listado con q=[%s]", q).containsExactlyElementsOf(codigos);
            assertThat(codigos(alcance)).as("alcance con q=[%s]", q).containsExactlyElementsOf(codigos);
            assertThat(articuloRepository.contarScoped(filtro)).as("conteo con q=[%s]", q)
                    .isEqualTo(listado.getTotalElements());
        });
        assertThat(esperados).hasSize(10);
    }

    @Test
    void sinTenantEnElContextoFiltrarYContarFallan() {
        catalogoDeFiltro("30-71000051-1", "30-71000052-2");
        TenantContext.clear();
        FiltroArticulos vacio = new FiltroArticulos(null, null, null, false);

        assertThatThrownBy(() -> articuloRepository.filtrarScoped(vacio)).isInstanceOf(MissingTenantException.class);
        assertThatThrownBy(() -> articuloRepository.contarScoped(vacio)).isInstanceOf(MissingTenantException.class);
    }
}
