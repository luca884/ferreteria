package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConsultaMostrador;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Consultas del mostrador (CU-06) de ArticuloRepository contra MySQL real: coincidencia exacta, dado de
 * baja y busqueda parcial por palabras, siempre acotadas al tenant. Comparte el MySQL de
 * IntegrationTestBase: cada test crea sus propias ferreterias con CUIT y codigos propios.
 */
class ArticuloMostradorRepositoryTest extends IntegrationTestBase {

    private static final AtomicInteger CUITS = new AtomicInteger(74_000_000);

    @Autowired
    ArticuloRepository articuloRepository;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private Long ferreteria() {
        String cuit = "30-%08d-1".formatted(CUITS.incrementAndGet());
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Mostrador " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    /** Articulo sin proveedor ni rubro; el costo y el precio no importan para estas consultas. */
    private Long articulo(Long ferreteriaId, String codigoInterno, String descripcion, String barras,
                          String codigoProveedor, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, "
                        + "descripcion, costo, precio_final, activo) values (?, ?, ?, ?, ?, 100.00, 170.00, ?)",
                ferreteriaId, codigoInterno, barras, codigoProveedor, descripcion, activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigoInterno);
    }

    private Long articulo(Long ferreteriaId, String codigoInterno, String descripcion, String barras) {
        return articulo(ferreteriaId, codigoInterno, descripcion, barras, null, true);
    }

    private static List<String> codigos(List<Articulo> articulos) {
        return articulos.stream().map(Articulo::getCodigoInterno).toList();
    }

    // --- exactosActivosScoped (R-003, R-004) ---

    @Test
    void elExactoEncuentraPorCodigoDeBarras() {
        Long f = ferreteria();
        articulo(f, "TOR-001", "Tornillo", "7790001");
        articulo(f, "MAR-001", "Martillo", "7790002");
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(codigos(articuloRepository.exactosActivosScoped("7790001", 21))).containsExactly("TOR-001");
        assertThat(codigos(articuloRepository.exactosActivosScoped("7790002", 21))).containsExactly("MAR-001");
    }

    @Test
    void elExactoEncuentraPorCodigoInterno() {
        Long f = ferreteria();
        articulo(f, "TOR-014", "Tornillo", "7790001");
        articulo(f, "MAR-001", "Martillo", null);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(codigos(articuloRepository.exactosActivosScoped("TOR-014", 21))).containsExactly("TOR-014");
        assertThat(codigos(articuloRepository.exactosActivosScoped("MAR-001", 21))).containsExactly("MAR-001");
    }

    @Test
    void elExactoNoDistingueMayusculasNiAcentos() {
        Long f = ferreteria();
        articulo(f, "TOR-014", "Tornillo", "ABC-é1");
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(codigos(articuloRepository.exactosActivosScoped("tor-014", 21))).containsExactly("TOR-014");
        assertThat(codigos(articuloRepository.exactosActivosScoped("abc-E1", 21))).containsExactly("TOR-014");
    }

    @Test
    void elExactoNoEsParcialNiMiraLaDescripcionNiElCodigoDeProveedor() {
        Long f = ferreteria();
        articulo(f, "TOR-001", "Tornillo 7790009", "7790001", "PROV-99", true);
        TenantContext.setCurrentFerreteriaId(f);

        // Control: el codigo entero si encuentra, asi que la consulta corre.
        assertThat(codigos(articuloRepository.exactosActivosScoped("7790001", 21))).containsExactly("TOR-001");
        assertThat(articuloRepository.exactosActivosScoped("779000", 21)).isEmpty();
        assertThat(articuloRepository.exactosActivosScoped("7790009", 21)).isEmpty();
        assertThat(articuloRepository.exactosActivosScoped("PROV-99", 21)).isEmpty();
    }

    @Test
    void elExactoExcluyeLosDadosDeBaja() {
        Long f = ferreteria();
        articulo(f, "ACT-001", "Activo", "7790003");
        articulo(f, "OLD-001", "Dado de baja", "7790004", null, false);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(codigos(articuloRepository.exactosActivosScoped("7790003", 21))).containsExactly("ACT-001");
        assertThat(articuloRepository.exactosActivosScoped("7790004", 21)).isEmpty();
        assertThat(articuloRepository.exactosActivosScoped("OLD-001", 21)).isEmpty();
    }

    @Test
    void elCodigoDeBarrasNoEsUnicoYDosActivosVuelvenOrdenadosPorDescripcionYId() {
        Long f = ferreteria();
        Long segundo = articulo(f, "B-02", "Bulon", "7790005");
        Long primero = articulo(f, "B-01", "Arandela", "7790005");
        Long mismaDescripcion = articulo(f, "B-03", "Bulon", "7790005");
        articulo(f, "B-04", "Otro", "7790006");
        TenantContext.setCurrentFerreteriaId(f);

        List<Articulo> exactos = articuloRepository.exactosActivosScoped("7790005", 21);

        assertThat(exactos).extracting(Articulo::getId).containsExactly(primero, segundo, mismaDescripcion);
    }

    @Test
    void elExactoAplicaElLimiteQueSePide() {
        Long f = ferreteria();
        for (int i = 1; i <= 5; i++) {
            articulo(f, "L-0" + i, "Articulo " + i, "7790007");
        }
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(codigos(articuloRepository.exactosActivosScoped("7790007", 3)))
                .containsExactly("L-01", "L-02", "L-03");
        assertThat(articuloRepository.exactosActivosScoped("7790007", 21)).hasSize(5);
    }

    // --- hayDadoDeBajaConCodigoScoped (R-005) ---

    @Test
    void hayDadoDeBajaConCodigoDeBarrasOInternoDeUnInactivo() {
        Long f = ferreteria();
        articulo(f, "OLD-001", "Dado de baja", "7790010", null, false);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790010")).isTrue();
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("old-001")).isTrue();
    }

    @Test
    void noHayDadoDeBajaSiElCodigoSoloEstaEnActivosOEnUnaCoincidenciaParcial() {
        Long f = ferreteria();
        articulo(f, "ACT-001", "Activo", "7790011");
        articulo(f, "OLD-001", "Tornillo 7790012", "7790013", "PROV-OLD", false);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790011")).isFalse();
        // La descripcion y el codigo de proveedor de un inactivo no cuentan, ni tampoco un pedazo del codigo.
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790012")).isFalse();
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("PROV-OLD")).isFalse();
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("77900")).isFalse();
        // Control: el codigo entero del inactivo si cuenta.
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790013")).isTrue();
    }

    @Test
    void elDadoDeBajaDeOtraFerreteriaNoCuenta() {
        Long a = ferreteria();
        Long b = ferreteria();
        articulo(b, "OLD-B", "Baja de B", "7790014", null, false);
        TenantContext.setCurrentFerreteriaId(a);

        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790014")).isFalse();
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("OLD-B")).isFalse();
        TenantContext.setCurrentFerreteriaId(b);
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790014")).isTrue();
    }

    // --- coincidenciasMostradorScoped (R-006, R-007) ---

    private List<String> parcial(String q) {
        return codigos(articuloRepository.coincidenciasMostradorScoped(ConsultaMostrador.de(q), 21));
    }

    @Test
    void lasPalabrasSePidenTodasYEnCualquierOrden() {
        Long f = ferreteria();
        articulo(f, "T-01", "Tornillo autoperforante 1/4", null);
        articulo(f, "T-02", "Tornillo hexagonal 3/8", null);
        articulo(f, "T-03", "Tuerca 1/4", null);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("tornillo 1/4")).containsExactly("T-01");
        assertThat(parcial("1/4 tornillo")).containsExactly("T-01");
        // AND: "tornillo" solo no alcanza para "tornillo hexagonal" si el otro articulo no tiene "hexagonal".
        assertThat(parcial("tornillo hexagonal")).containsExactly("T-02");
        assertThat(parcial("tornillo")).containsExactly("T-01", "T-02");
        assertThat(parcial("1/4")).containsExactly("T-01", "T-03");
    }

    @Test
    void cadaPalabraPuedeCaerEnUnCampoDistinto() {
        Long f = ferreteria();
        articulo(f, "TOR-014", "Tornillo", "7790015", "PROV-99", true);
        articulo(f, "TOR-015", "Tornillo", "7790016", "PROV-98", true);
        TenantContext.setCurrentFerreteriaId(f);

        // descripcion + codigo de proveedor + codigo de barras + codigo interno, cada palabra en uno.
        assertThat(parcial("tornillo prov-99 7790015 tor-014")).containsExactly("TOR-014");
        assertThat(parcial("tornillo prov-98")).containsExactly("TOR-015");
        assertThat(parcial("779001")).containsExactly("TOR-014", "TOR-015");
    }

    @Test
    void elCodigoDeProveedorEntraEnLaBusquedaParcial() {
        Long f = ferreteria();
        articulo(f, "TOR-001", "Tornillo", null, "PROV-99", true);
        articulo(f, "TOR-002", "Tornillo", null, "PROV-12", true);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("prov-99")).containsExactly("TOR-001");
        assertThat(parcial("ov-1")).containsExactly("TOR-002");
    }

    @Test
    void laBusquedaNoDistingueMayusculasNiTildes() {
        Long f = ferreteria();
        articulo(f, "A-01", "Árbol de levas", null);
        articulo(f, "A-02", "Martillo", null);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("ARBOL")).containsExactly("A-01");
        assertThat(parcial("árbol LEVAS")).containsExactly("A-01");
        assertThat(parcial("martillo")).containsExactly("A-02");
    }

    @Test
    void losComodinesSeBuscanComoTextoLiteral() {
        Long f = ferreteria();
        articulo(f, "D-01", "Oferta 50% de descuento", null);
        articulo(f, "D-02", "Oferta 50 unidades", null);
        articulo(f, "D-03", "Perfil a_b", null);
        articulo(f, "D-04", "Perfil axb", null);
        articulo(f, "D-05", "Ruta c:\\temp", null);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("50%")).containsExactly("D-01");
        assertThat(parcial("a_b")).containsExactly("D-03");
        assertThat(parcial("c:\\t")).containsExactly("D-05");
        // Control: sin el comodin las dos ofertas aparecen, asi que la busqueda corre.
        assertThat(parcial("oferta")).containsExactlyInAnyOrder("D-01", "D-02");
    }

    @Test
    void losDadosDeBajaNoAparecen() {
        Long f = ferreteria();
        articulo(f, "ACT-01", "Tornillo 1/4", null);
        articulo(f, "OLD-01", "Tornillo 1/4 viejo", "7790017", "PROV-OLD", false);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("tornillo 1/4")).containsExactly("ACT-01");
        assertThat(parcial("7790017")).isEmpty();
        assertThat(parcial("prov-old")).isEmpty();
    }

    @Test
    void sinCoincidenciasDevuelveListaVacia() {
        Long f = ferreteria();
        articulo(f, "T-01", "Tornillo", null);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(parcial("martillo")).isEmpty();
        // La palabra que existe no alcanza si la otra no esta.
        assertThat(parcial("tornillo martillo")).isEmpty();
    }

    @Test
    void elOrdenEsPorDescripcionYLuegoPorId() {
        Long f = ferreteria();
        Long bulonB = articulo(f, "O-01", "Bulon", null);
        Long arandela = articulo(f, "O-02", "Arandela", null);
        Long bulonA = articulo(f, "O-03", "Bulon", null);
        TenantContext.setCurrentFerreteriaId(f);

        List<Articulo> encontrados = articuloRepository.coincidenciasMostradorScoped(ConsultaMostrador.de("o"), 21);

        assertThat(encontrados).extracting(Articulo::getId).containsExactly(arandela, bulonB, bulonA);
    }

    @Test
    void elLimiteSeAplicaYConVeinticincoCoincidenciasPidiendoVeintiunoVuelvenVeintiuno() {
        Long f = ferreteria();
        for (int i = 1; i <= 25; i++) {
            articulo(f, "LIM-%02d".formatted(i), "Cable %02d".formatted(i), null);
        }
        TenantContext.setCurrentFerreteriaId(f);

        List<Articulo> pedidos = articuloRepository.coincidenciasMostradorScoped(ConsultaMostrador.de("cable"), 21);

        assertThat(pedidos).hasSize(21);
        assertThat(pedidos.get(0).getCodigoInterno()).isEqualTo("LIM-01");
        assertThat(pedidos.get(20).getCodigoInterno()).isEqualTo("LIM-21");
        assertThat(articuloRepository.coincidenciasMostradorScoped(ConsultaMostrador.de("cable"), 5)).hasSize(5);
    }

    @Test
    void conSietePalabrasLaQuintaSeBuscaJuntoConLasSiguientes() {
        Long f = ferreteria();
        articulo(f, "S-01", "w1 w2 w3 w4 w5 w6 w7", null);
        articulo(f, "S-02", "w1 w2 w3 w4 w5 w7 w6", null);
        TenantContext.setCurrentFerreteriaId(f);

        // "w5 w6 w7" contiguo y en ese orden: solo el primero. Las 4 primeras si van sueltas y en cualquier orden.
        assertThat(parcial("w1 w2 w3 w4 w5 w6 w7")).containsExactly("S-01");
        assertThat(parcial("w4 w3 w2 w1 w5 w6 w7")).containsExactly("S-01");
        assertThat(parcial("w1 w2 w3 w4 w5")).containsExactly("S-01", "S-02");
    }

    // --- Aislamiento entre ferreterias y sin tenant (R-010) ---

    /** A y B con articulos casi iguales; el tenant queda en A. */
    private Long[] ferreteriasConLoMismo() {
        Long a = ferreteria();
        Long b = ferreteria();
        articulo(a, "ACT-A", "Martillo de A", "7790020");
        articulo(b, "ACT-B", "Martillo de B", "7790020", "PROV-B", true);
        articulo(b, "OLD-B", "Taladro viejo de B", "7790021", null, false);
        TenantContext.setCurrentFerreteriaId(a);
        return new Long[] {a, b};
    }

    @Test
    void lasTresConsultasExcluyenLosArticulosDeOtraFerreteria() {
        Long[] ferreterias = ferreteriasConLoMismo();

        // Exacto: el codigo de barras de B existe en A tambien, pero solo viene el de A.
        assertThat(codigos(articuloRepository.exactosActivosScoped("7790020", 21))).containsExactly("ACT-A");
        assertThat(articuloRepository.exactosActivosScoped("ACT-B", 21)).isEmpty();
        // Dado de baja: el inactivo de B no cuenta para A.
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790021")).isFalse();
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("OLD-B")).isFalse();
        // Parcial: por descripcion, codigo de proveedor y codigo de barras solo se ve lo de A.
        assertThat(parcial("martillo")).containsExactly("ACT-A");
        assertThat(parcial("de b")).isEmpty();
        assertThat(parcial("prov-b")).isEmpty();
        assertThat(parcial("taladro")).isEmpty();

        // Control: con el tenant en B se ve lo de B, asi que los datos estaban.
        TenantContext.setCurrentFerreteriaId(ferreterias[1]);
        assertThat(codigos(articuloRepository.exactosActivosScoped("7790020", 21))).containsExactly("ACT-B");
        assertThat(articuloRepository.hayDadoDeBajaConCodigoScoped("7790021")).isTrue();
        assertThat(parcial("prov-b")).containsExactly("ACT-B");
    }

    @Test
    void sinTenantEnElContextoLasTresConsultasFallan() {
        ferreteriasConLoMismo();
        TenantContext.clear();
        ConsultaMostrador consulta = ConsultaMostrador.de("martillo");

        assertThatThrownBy(() -> articuloRepository.exactosActivosScoped("7790020", 21))
                .isInstanceOf(MissingTenantException.class);
        assertThatThrownBy(() -> articuloRepository.hayDadoDeBajaConCodigoScoped("7790021"))
                .isInstanceOf(MissingTenantException.class);
        assertThatThrownBy(() -> articuloRepository.coincidenciasMostradorScoped(consulta, 21))
                .isInstanceOf(MissingTenantException.class);
    }
}
