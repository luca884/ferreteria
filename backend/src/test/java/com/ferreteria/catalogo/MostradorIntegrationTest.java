package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Buscar articulo en el mostrador (CU-06) por HTTP contra MySQL real: GET /api/articulos/mostrador?q=.
 * A = El Tornillo, B = La Tuerca. La base se comparte y no se revierte: cada test crea sus articulos con
 * codigos unicos.
 */
class MostradorIntegrationTest extends IntegrationTestBase {

    private String duenoA;
    private String duenoB;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    // --- Helpers ---

    private long ferreteriaDe(String email) {
        return jdbc.queryForObject("select ferreteria_id from usuario where email = ?", Long.class, email);
    }

    /** GET con q como parametro (MockMvc lo codifica: no se arma la URL a mano, que doblaria el % y el +). */
    private ResultActions buscarResult(String token, String q) throws Exception {
        return mvc.perform(get("/api/articulos/mostrador").param("q", q).header("Authorization", "Bearer " + token));
    }

    private JsonNode buscar(String token, String q) throws Exception {
        String body = buscarResult(token, q).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }


    /** Articulo activo con lo justo para el mostrador; costo 100 y margen propio 50 (para probar que no salen). */
    private long articulo(long ferreteriaId, String codigoInterno, String descripcion, String barras,
                          String codigoProveedor, boolean activo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, descripcion, "
                        + "costo, margen_porcentaje, precio_final, unidad, stock_actual, stock_minimo, ubicacion, activo) "
                        + "values (?, ?, ?, ?, ?, 100.00, 50.00, 1234.50, 'METRO', 2.5, 5, 'Estante 3', ?)",
                ferreteriaId, codigoInterno, barras, codigoProveedor, descripcion, activo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigoInterno);
    }

    private static List<String> campos(JsonNode objeto) {
        List<String> nombres = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(nombres::add);
        return nombres;
    }

    // --- Acceso y contrato (R-001, R-002, R-009) ---

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/articulos/mostrador").param("q", "algo").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sinCoincidenciasDaSinResultadosConElContratoCompleto() throws Exception {
        JsonNode r = buscar(vendedorA, unico("NOEXISTE"));

        assertThat(r.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(r.get("items").isArray()).isTrue();
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("hayMas").isBoolean()).isTrue();
        assertThat(r.get("hayMas").asBoolean()).isFalse();
    }

    @Test
    void elVendedorYElDuenoPuedenBuscar() throws Exception {
        String codigo = unico("ACC");
        long ferreteriaA = ferreteriaDe("dueno@tornillo.demo");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, descripcion, costo, precio_final) "
                + "values (?, ?, ?, 'Articulo de acceso', 100.00, 170.00)", ferreteriaA, codigo, codigo);

        JsonNode vendedor = buscar(vendedorA, codigo);
        JsonNode dueno = buscar(duenoA, codigo);

        assertThat(vendedor.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(vendedor.get("items")).hasSize(1);
        assertThat(vendedor.get("items").get(0).get("codigoInterno").asText()).isEqualTo(codigo);
        assertThat(dueno.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(dueno.get("items").get(0).get("id").asLong()).isEqualTo(vendedor.get("items").get(0).get("id").asLong());
    }

    @Test
    void qAusenteVacioOEnBlancoDaSinResultados() throws Exception {
        // Hay articulos en la ferreteria: un q en blanco no devuelve el catalogo.
        List<JsonNode> respuestas = List.of(
                getJson("/api/articulos/mostrador", vendedorA),
                buscar(vendedorA, ""),
                buscar(vendedorA, "   "),
                buscar(vendedorA, "\t\n"));

        for (JsonNode r : respuestas) {
            assertThat(r.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
            assertThat(r.get("items")).isEmpty();
            assertThat(r.get("hayMas").asBoolean()).isFalse();
        }
    }

    @Test
    void mostradorNoSeInterpretaComoElIdDeUnArticulo() throws Exception {
        getResult("/api/articulos/mostrador", vendedorA).andExpect(status().isOk());
        // Control: un id que no es numerico sigue dando 400 en la otra ruta.
        getResult("/api/articulos/abc", vendedorA).andExpect(status().isBadRequest());
    }

    @Test
    void elSuperAdminSinFerreteriaRecibe403AunqueQEsteEnBlanco() throws Exception {
        String superAdmin = login("admin@demo.local");

        buscarResult(superAdmin, " ").andExpect(status().isForbidden());
        buscarResult(superAdmin, "tornillo").andExpect(status().isForbidden());
    }

    // --- DTO fino: sin costo ni margen (R-008, R-009) ---

    @Test
    void elVendedorYElDuenoRecibenElMismoDtoFinoSinCostoNiMargen() throws Exception {
        String codigo = unico("FINO");
        long id = articulo(ferreteriaDe("dueno@tornillo.demo"), codigo, "Cable fino", codigo, "PROV-" + codigo, true);

        for (String token : new String[] {vendedorA, duenoA}) {
            JsonNode r = buscar(token, codigo);

            assertThat(r.get("tipo").asText()).isEqualTo("EXACTO");
            JsonNode a = r.get("items").get(0);
            assertThat(campos(a)).containsExactlyInAnyOrder("id", "codigoInterno", "codigoBarras", "codigoProveedor",
                    "descripcion", "precioFinal", "unidad", "stockActual", "stockMinimo", "ubicacion", "activo",
                    "costoAl");
            assertThat(campos(a)).doesNotContain("costo", "margen", "margenPorcentaje", "margenPropio",
                    "precioListaProveedor", "alicuotaIva", "rubro", "rubroId", "proveedor", "proveedorId");
            assertThat(a.get("activo").asBoolean()).isTrue();
            assertThat(a.get("id").asLong()).isEqualTo(id);
            assertThat(a.get("codigoInterno").asText()).isEqualTo(codigo);
            assertThat(a.get("descripcion").asText()).isEqualTo("Cable fino");
            assertThat(a.get("unidad").asText()).isEqualTo("METRO");
            assertThat(a.get("stockActual").decimalValue()).isEqualByComparingTo("2.5");
            assertThat(a.get("stockMinimo").decimalValue()).isEqualByComparingTo("5");
            assertThat(a.get("ubicacion").asText()).isEqualTo("Estante 3");
        }
    }

    @Test
    void elPrecioFinalEsElGuardadoAunqueLaFormulaDariaOtro() throws Exception {
        String codigo = unico("PRECIO");
        // costo 100 + margen propio 50 + IVA 21 daria 181.50 (o 190 redondeado): lo guardado es 1234.50.
        articulo(ferreteriaDe("dueno@tornillo.demo"), codigo, "Precio guardado", codigo, null, true);

        BigDecimal precio = buscar(vendedorA, codigo).get("items").get(0).get("precioFinal").decimalValue();

        assertThat(precio).isEqualByComparingTo("1234.50");
    }

    @Test
    void laListaTambienUsaElDtoFino() throws Exception {
        String barras = unico("DOS");
        long ferreteriaA = ferreteriaDe("dueno@tornillo.demo");
        articulo(ferreteriaA, unico("L1"), "Lista uno", barras, null, true);
        articulo(ferreteriaA, unico("L2"), "Lista dos", barras, null, true);

        JsonNode r = buscar(vendedorA, barras);

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(r.get("items")).hasSize(2);
        r.get("items").forEach(a -> assertThat(campos(a)).doesNotContain("costo", "margenPorcentaje"));
    }

    // --- Casos funcionales con base real (R-002 a R-007) ---
    // Los articulos demo de A tambien estan en la base: cada consulta lleva el token unico del test como una
    // palabra mas (AND), asi que solo puede traer lo que el test creo.

    private long ferreteriaA() {
        return ferreteriaDe("dueno@tornillo.demo");
    }

    private static List<String> descripciones(JsonNode resultado) {
        List<String> descripciones = new ArrayList<>();
        resultado.get("items").forEach(a -> descripciones.add(a.get("descripcion").asText()));
        return descripciones;
    }

    @Test
    void elCodigoDeBarrasEscaneadoConEspaciosYSaltoDeLineaEsExacto() throws Exception {
        String barras = unico("7790001");
        long id = articulo(ferreteriaA(), unico("I"), "Tornillo escaneado", barras, null, true);

        JsonNode r = buscar(vendedorA, " " + barras + "\n");

        assertThat(r.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(r.get("items")).hasSize(1);
        assertThat(r.get("items").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(r.get("hayMas").asBoolean()).isFalse();
    }

    @Test
    void elCodigoInternoTambienEsExactoYNoDistingueMayusculas() throws Exception {
        String interno = unico("TOR");
        long id = articulo(ferreteriaA(), interno, "Tornillo por codigo interno", null, null, true);

        assertThat(buscar(vendedorA, interno).get("tipo").asText()).isEqualTo("EXACTO");
        JsonNode enMinusculas = buscar(vendedorA, interno.toLowerCase());
        assertThat(enMinusculas.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(enMinusculas.get("items").get(0).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void otroArticuloConElCodigoEnLaDescripcionNoCambiaElExacto() throws Exception {
        String barras = unico("7790001");
        long primero = articulo(ferreteriaA(), unico("I"), "Tornillo de verdad", barras, null, true);
        articulo(ferreteriaA(), unico("I"), "Caja de tornillos " + barras, null, null, true);

        JsonNode r = buscar(vendedorA, barras);

        assertThat(r.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(r.get("items")).hasSize(1);
        assertThat(r.get("items").get(0).get("id").asLong()).isEqualTo(primero);
    }

    @Test
    void unActivoYUnDadoDeBajaConElMismoCodigoDanElExactoDelActivo() throws Exception {
        String barras = unico("7790001");
        long activo = articulo(ferreteriaA(), unico("I"), "Activo", barras, null, true);
        articulo(ferreteriaA(), unico("I"), "Dado de baja", barras, null, false);

        JsonNode r = buscar(vendedorA, barras);

        assertThat(r.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(r.get("items").get(0).get("id").asLong()).isEqualTo(activo);
    }

    @Test
    void elCodigoDeProveedorNoEsExactoPeroEntraEnLaListaParcial() throws Exception {
        String codigoProveedor = unico("PROV");
        long id = articulo(ferreteriaA(), unico("I"), "Tornillo del proveedor", null, codigoProveedor, true);

        JsonNode r = buscar(vendedorA, codigoProveedor.toLowerCase());

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(r.get("items")).hasSize(1);
        assertThat(r.get("items").get(0).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void dosActivosConElMismoCodigoDeBarrasDanUnaListaConSoloEsos() throws Exception {
        String barras = unico("7790002");
        articulo(ferreteriaA(), unico("I"), "Bulon B", barras, null, true);
        articulo(ferreteriaA(), unico("I"), "Arandela A", barras, null, true);
        articulo(ferreteriaA(), unico("I"), "Otro " + barras.substring(0, 6), unico("7790099"), null, true);

        JsonNode r = buscar(vendedorA, barras);

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(descripciones(r)).containsExactly("Arandela A", "Bulon B");
        assertThat(r.get("hayMas").asBoolean()).isFalse();
    }

    // --- Dado de baja: aparece en la lista, marcado (H06) ---

    @Test
    void elCodigoExactoDeUnInactivoLoMuestraEnLaListaConActivoFalse() throws Exception {
        String barras = unico("7790003");
        articulo(ferreteriaA(), unico("I"), "Articulo viejo", barras, null, false);

        JsonNode r = buscar(vendedorA, barras);

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(descripciones(r)).containsExactly("Articulo viejo");
        assertThat(r.get("items").get(0).get("activo").asBoolean()).isFalse();
    }

    @Test
    void unInactivoConElCodigoBuscadoNoTapaALosActivosQueCoincidenPorPalabra() throws Exception {
        // Caso de la auditoria: un inactivo con codigo interno TORNILLO tapaba a todos los tornillos activos.
        String token = unico("TOK");
        articulo(ferreteriaA(), token, "Articulo viejo", null, null, false);
        articulo(ferreteriaA(), unico("I"), "Tornillo " + token, null, null, true);

        JsonNode r = buscar(vendedorA, token);

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(descripciones(r)).containsExactly("Tornillo " + token, "Articulo viejo");
        assertThat(r.get("items").get(0).get("activo").asBoolean()).isTrue();
        assertThat(r.get("items").get(1).get("activo").asBoolean()).isFalse();
    }

    @Test
    void unCodigoQueNoExisteDaSinResultadosNuncaDadoDeBaja() throws Exception {
        JsonNode r = buscar(vendedorA, unico("NOEXISTE"));

        assertThat(r.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(r.get("items")).isEmpty();
    }

    // --- Busqueda parcial por palabras ---

    @Test
    void lasPalabrasSePidenTodasYEnCualquierOrden() throws Exception {
        String token = unico("TOK");
        articulo(ferreteriaA(), unico("I"), "Tornillo autoperforante 1/4 " + token, null, null, true);
        articulo(ferreteriaA(), unico("I"), "Tornillo hexagonal 3/8 " + token, null, null, true);

        JsonNode enOrden = buscar(vendedorA, "tornillo 1/4 " + token);
        JsonNode alReves = buscar(vendedorA, "1/4 " + token + " tornillo");

        assertThat(enOrden.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(descripciones(enOrden)).containsExactly("Tornillo autoperforante 1/4 " + token);
        assertThat(descripciones(alReves)).containsExactly("Tornillo autoperforante 1/4 " + token);
        // AND: "hexagonal" no esta en el primero y "1/4" no esta en el segundo.
        assertThat(buscar(vendedorA, "hexagonal 1/4 " + token).get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
    }

    @Test
    void lasPalabrasPuedenCaerEnCamposDistintos() throws Exception {
        String token = unico("TOK");
        String proveedor = unico("PROV");
        long id = articulo(ferreteriaA(), unico("I"), "Tornillo " + token, unico("7790"), proveedor, true);

        JsonNode r = buscar(vendedorA, "tornillo " + proveedor.toLowerCase() + " " + token);

        assertThat(r.get("items")).hasSize(1);
        assertThat(r.get("items").get(0).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void elPorcentajeYElGuionBajoSeBuscanComoTextoLiteral() throws Exception {
        String token = unico("TOK");
        articulo(ferreteriaA(), unico("I"), "Oferta 50% " + token, null, null, true);
        articulo(ferreteriaA(), unico("I"), "Oferta 50 unidades " + token, null, null, true);
        articulo(ferreteriaA(), unico("I"), "Perfil a_b " + token, null, null, true);
        articulo(ferreteriaA(), unico("I"), "Perfil axb " + token, null, null, true);

        assertThat(descripciones(buscar(vendedorA, "50% " + token))).containsExactly("Oferta 50% " + token);
        assertThat(descripciones(buscar(vendedorA, "a_b " + token))).containsExactly("Perfil a_b " + token);
        // Control: sin comodines las dos ofertas aparecen, asi que la busqueda corre.
        assertThat(descripciones(buscar(vendedorA, "oferta " + token))).hasSize(2);
    }

    @Test
    void sinCoincidenciasDaSinResultados() throws Exception {
        JsonNode r = buscar(vendedorA, "martillo " + unico("NADA"));

        assertThat(r.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("hayMas").asBoolean()).isFalse();
    }

    // --- Limite de 20 y hayMas ---

    private String crearCables(int cantidad) {
        String token = unico("CBL");
        for (int i = 1; i <= cantidad; i++) {
            articulo(ferreteriaA(), "%s-%02d".formatted(token, i), "Cable %02d %s".formatted(i, token), null, null, true);
        }
        return token;
    }

    @Test
    void conVeinticincoCoincidenciasVienenLasVeintePrimerasPorDescripcionYHayMas() throws Exception {
        String token = crearCables(25);

        JsonNode r = buscar(vendedorA, token);

        assertThat(r.get("tipo").asText()).isEqualTo("LISTA");
        assertThat(r.get("items")).hasSize(20);
        assertThat(descripciones(r).get(0)).isEqualTo("Cable 01 " + token);
        assertThat(descripciones(r).get(19)).isEqualTo("Cable 20 " + token);
        assertThat(r.get("hayMas").asBoolean()).isTrue();
    }

    @Test
    void conExactamenteVeinteNoHayMas() throws Exception {
        String token = crearCables(20);

        JsonNode r = buscar(vendedorA, token);

        assertThat(r.get("items")).hasSize(20);
        assertThat(r.get("hayMas").asBoolean()).isFalse();
    }

    @Test
    void conExactamenteVeintiunoVienenVeinteYHayMas() throws Exception {
        String token = crearCables(21);

        JsonNode r = buscar(vendedorA, token);

        assertThat(r.get("items")).hasSize(20);
        assertThat(r.get("hayMas").asBoolean()).isTrue();
    }

    @Test
    void conLaMismaDescripcionDesempataPorId() throws Exception {
        String token = unico("TIE");
        long segundo = articulo(ferreteriaA(), unico("I"), "Bulon " + token, null, null, true);
        long tercero = articulo(ferreteriaA(), unico("I"), "Bulon " + token, null, null, true);
        long primero = articulo(ferreteriaA(), unico("I"), "Arandela " + token, null, null, true);

        JsonNode r = buscar(vendedorA, token);

        List<Long> ids = new ArrayList<>();
        r.get("items").forEach(a -> ids.add(a.get("id").asLong()));
        assertThat(ids).containsExactly(primero, segundo, tercero);
    }

    // --- Aislamiento entre ferreterias (R-010, R-004; regla 7 de CLAUDE.md) ---

    private long ferreteriaB() {
        return ferreteriaDe("dueno@tuerca.demo");
    }

    @Test
    void elCodigoDeBarrasActivoDeUnaFerreteriaNoApareceEnOtra() throws Exception {
        String barras = unico("7790004");
        articulo(ferreteriaA(), unico("I"), "Martillo de A", barras, null, true);

        assertThat(buscar(duenoA, barras).get("tipo").asText()).isEqualTo("EXACTO");
        JsonNode deB = buscar(duenoB, barras);

        assertThat(deB.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(deB.get("items")).isEmpty();
    }

    @Test
    void unInactivoDeLaFerreteriaASoloApareceEnA() throws Exception {
        String barras = unico("7790005");
        articulo(ferreteriaA(), unico("I"), "Viejo de A", barras, null, false);

        assertThat(descripciones(buscar(duenoA, barras))).containsExactly("Viejo de A");
        JsonNode deB = buscar(duenoB, barras);

        // Sin filtracion: B no se entera de que ese codigo existe en otra ferreteria.
        assertThat(deB.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(deB.get("items")).isEmpty();
    }

    @Test
    void laDescripcionDeUnaFerreteriaNoApareceEnOtra() throws Exception {
        String token = unico("TOK");
        articulo(ferreteriaA(), unico("I"), "Martillo galponero " + token, null, null, true);

        assertThat(buscar(vendedorA, "martillo " + token).get("items")).hasSize(1);
        JsonNode deB = buscar(duenoB, "martillo " + token);

        assertThat(deB.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(deB.get("items")).isEmpty();
    }

    @Test
    void elCodigoInternoYElDeProveedorDeBNoLosVeA() throws Exception {
        String interno = unico("INT-B");
        String proveedor = unico("PROV-B");
        articulo(ferreteriaB(), interno, "Articulo de B", null, proveedor, true);

        assertThat(buscar(duenoB, interno).get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(buscar(duenoB, proveedor).get("tipo").asText()).isEqualTo("LISTA");
        assertThat(buscar(duenoA, interno).get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(buscar(vendedorA, proveedor).get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(buscar(vendedorA, "articulo de b " + interno).get("items")).isEmpty();
    }

    @Test
    void unFerreteriaIdAjenoEnElPedidoSeIgnoraYSoloSeVeLoPropio() throws Exception {
        String barrasB = unico("7790006");
        String barrasA = unico("7790007");
        articulo(ferreteriaB(), unico("I"), "Solo de B", barrasB, null, true);
        long propio = articulo(ferreteriaA(), unico("I"), "Solo de A", barrasA, null, true);

        // Parametro y headers que dicen ser B: el tenant sale del JWT de A.
        JsonNode buscandoB = objectMapper.readTree(mvc.perform(get("/api/articulos/mostrador")
                        .param("q", barrasB).param("ferreteriaId", String.valueOf(ferreteriaB()))
                        .header("X-Ferreteria-Id", String.valueOf(ferreteriaB()))
                        .header("Authorization", "Bearer " + vendedorA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode buscandoA = objectMapper.readTree(mvc.perform(get("/api/articulos/mostrador")
                        .param("q", barrasA).param("ferreteriaId", String.valueOf(ferreteriaB()))
                        .header("X-Ferreteria-Id", String.valueOf(ferreteriaB()))
                        .header("Authorization", "Bearer " + vendedorA))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(buscandoB.get("tipo").asText()).isEqualTo("SIN_RESULTADOS");
        assertThat(buscandoA.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(buscandoA.get("items").get(0).get("id").asLong()).isEqualTo(propio);
    }

    @Test
    void unActivoPropioYOtroIgualDeOtraFerreteriaDanElExactoDelPropio() throws Exception {
        String barras = unico("7790008");
        long deA = articulo(ferreteriaA(), unico("I"), "Igual de A", barras, null, true);
        long deB = articulo(ferreteriaB(), unico("I"), "Igual de B", barras, null, true);

        JsonNode a = buscar(vendedorA, barras);
        JsonNode b = buscar(duenoB, barras);

        assertThat(a.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(a.get("items").get(0).get("id").asLong()).isEqualTo(deA);
        assertThat(b.get("tipo").asText()).isEqualTo("EXACTO");
        assertThat(b.get("items").get(0).get("id").asLong()).isEqualTo(deB);
    }
}
