package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Articulos por HTTP contra MySQL real: alta, edicion, baja logica, lectura paginada, unicidades,
 * historial de precios, aislamiento entre ferreterias y permisos por rol. A = El Tornillo (margen
 * por defecto 30, multiplo 10), B = La Tuerca. La base se comparte y no se revierte: cada test
 * crea sus datos con codigos y nombres unicos.
 */
class ArticuloIntegrationTest extends IntegrationTestBase {

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

    /** Pedido minimo valido: codigo interno unico, costo 100, IVA y margen por omision. */
    private ObjectNode pedido(String codigoInterno) {
        return objectMapper.createObjectNode()
                .put("codigoInterno", codigoInterno)
                .put("descripcion", "Articulo " + codigoInterno)
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"));
    }

    private String texto(ObjectNode node) throws Exception {
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode crear(String token, ObjectNode pedido) throws Exception {
        String body = postJson("/api/articulos", token, texto(pedido))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private long crearRubro(String token, String nombre, String margen) throws Exception {
        String body = postJson("/api/rubros", token,
                "{\"nombre\":\"" + nombre + "\",\"margenPorcentaje\":" + margen + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long crearProveedor(String token, String nombre) throws Exception {
        String body = postJson("/api/proveedores", token, "{\"nombre\":\"" + nombre + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private BigDecimal precioFinal(JsonNode articulo) {
        return articulo.get("precioFinal").decimalValue();
    }

    /** El campo no viene o viene en null (la serializacion no deja claro cual de las dos). */
    private static boolean sinValor(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isMissingNode() || valor.isNull();
    }

    private long usuarioId(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private long ferreteriaDe(String email) {
        return jdbc.queryForObject("select ferreteria_id from usuario where email = ?", Long.class, email);
    }

    private long cantidadArticulos() {
        return jdbc.queryForObject("select count(*) from articulo", Long.class);
    }

    private long cantidadHistorial() {
        return jdbc.queryForObject("select count(*) from historial_precio", Long.class);
    }

    private List<Map<String, Object>> historial(long articuloId) {
        return jdbc.queryForList(
                "select ferreteria_id, costo_anterior, costo_nuevo, precio_anterior, precio_nuevo, origen, usuario_id"
                        + " from historial_precio where articulo_id = ? order by id", articuloId);
    }

    private Map<String, Object> filaDeArticulo(long id) {
        return jdbc.queryForMap("select * from articulo where id = ?", id);
    }

    private List<String> codigos(JsonNode pagina) {
        List<String> codigos = new ArrayList<>();
        pagina.get("items").forEach(n -> codigos.add(n.get("codigoInterno").asText()));
        return codigos;
    }

    // --- Lectura: paginacion y busqueda (R-012) ---

    @Test
    void paginaDe45ArticulosDevuelve20ConTotalesYOrdenadosPorDescripcion() throws Exception {
        String marca = unico("PAG");
        for (int i = 1; i <= 45; i++) {
            ObjectNode p = pedido(marca + "-" + String.format("%02d", i));
            p.put("descripcion", marca + " item " + String.format("%02d", i));
            crear(duenoA, p);
        }

        JsonNode pagina = getJson("/api/articulos?q=" + marca + "&page=1&size=20", vendedorA);

        assertThat(pagina.get("items")).hasSize(20);
        assertThat(pagina.get("page").asInt()).isEqualTo(1);
        assertThat(pagina.get("size").asInt()).isEqualTo(20);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(45);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(3);
        assertThat(pagina.get("items").get(0).get("descripcion").asText()).isEqualTo(marca + " item 21");
        assertThat(pagina.get("items").get(19).get("descripcion").asText()).isEqualTo(marca + " item 40");
    }

    @Test
    void conLaMismaDescripcionLasPaginasNoRepitenNiPierdenArticulosYSiguenElOrdenDeId() throws Exception {
        String marca = unico("EMP");
        List<Long> idsCreados = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            ObjectNode p = pedido(marca + "-" + i);
            p.put("descripcion", marca); // la misma para todos: solo el id los desempata
            idsCreados.add(crear(duenoA, p).get("id").asLong());
        }

        for (int size : new int[] {1, 2, 3}) {
            List<Long> ids = new ArrayList<>();
            int totalPages = Integer.MAX_VALUE;
            for (int page = 0; page < totalPages; page++) {
                JsonNode pagina = getJson("/api/articulos?q=" + marca + "&page=" + page + "&size=" + size, duenoA);
                totalPages = pagina.get("totalPages").asInt();
                pagina.get("items").forEach(n -> ids.add(n.get("id").asLong()));
            }
            // Cada id una sola vez y en orden ascendente (idsCreados ya viene ascendente: ids de identidad).
            assertThat(ids).as("size=%d", size).containsExactlyElementsOf(idsCreados);
        }
    }

    @Test
    void sinParametrosElTamanoPorDefectoEs20YElMaximoEs100() throws Exception {
        String marca = unico("TAM");
        for (int i = 1; i <= 22; i++) {
            crear(duenoA, pedido(marca + "-" + String.format("%02d", i)));
        }

        JsonNode porDefecto = getJson("/api/articulos?q=" + marca, duenoA);
        JsonNode enorme = getJson("/api/articulos?q=" + marca + "&size=500", duenoA);

        assertThat(porDefecto.get("size").asInt()).isEqualTo(20);
        assertThat(porDefecto.get("items")).hasSize(20);
        assertThat(porDefecto.get("page").asInt()).isZero();
        assertThat(enorme.get("size").asInt()).isEqualTo(100);
        assertThat(enorme.get("items")).hasSize(22);
    }

    @Test
    void qBuscaPorDescripcionOCodigoSinDistinguirMayusculas() throws Exception {
        String marca = unico("BUS");
        ObjectNode tornillo = pedido(marca + "-TORN");
        tornillo.put("descripcion", "Tornillo hexagonal " + marca);
        tornillo.put("codigoBarras", marca + "-779");
        ObjectNode martillo = pedido(marca + "-MART");
        martillo.put("descripcion", "Martillo " + marca);
        crear(duenoA, tornillo);
        crear(duenoA, martillo);

        // Mayusculas a proposito: la busqueda no distingue mayusculas y es por coincidencia parcial.
        assertThat(codigos(getJson("/api/articulos?q=TORNILLO HEXAGONAL " + marca, duenoA)))
                .containsExactly(marca + "-TORN");
        assertThat(codigos(getJson("/api/articulos?q=" + marca.toLowerCase() + "-mart", duenoA)))
                .containsExactly(marca + "-MART");
        assertThat(codigos(getJson("/api/articulos?q=" + marca + "-779", duenoA)))
                .containsExactly(marca + "-TORN");
    }

    @Test
    void qBuscaPorCodigoDelProveedor() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("ProvBus"));
        String codigoProv = unico("CP");
        ObjectNode p = pedido(unico("BCP"));
        p.put("proveedorId", proveedor).put("codigoProveedor", codigoProv);
        crear(duenoA, p);

        assertThat(codigos(getJson("/api/articulos?q=" + codigoProv, duenoA)))
                .containsExactly(p.get("codigoInterno").asText());
    }

    @Test
    void elListadoSoloMuestraArticulosDeLaFerreteriaDelToken() throws Exception {
        String marca = unico("AIS");
        crear(duenoA, pedido(marca + "-A"));
        crear(duenoB, pedido(marca + "-B"));

        assertThat(codigos(getJson("/api/articulos?q=" + marca, duenoA))).containsExactly(marca + "-A");
        assertThat(codigos(getJson("/api/articulos?q=" + marca, duenoB))).containsExactly(marca + "-B");
    }

    @Test
    void detalleDeArticuloAjenoOInexistenteDa404() throws Exception {
        long idDeB = crear(duenoB, pedido(unico("AJENO"))).get("id").asLong();

        getResult("/api/articulos/" + idDeB, duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        getResult("/api/articulos/999999999", duenoA).andExpect(status().isNotFound());
    }

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/articulos"))
                .andExpect(status().isUnauthorized());
    }

    // --- Permisos por rol (R-013) ---

    @Test
    void elVendedorLeeListadoYDetalleSinCostoNiMargen() throws Exception {
        ObjectNode p = pedido(unico("VEN"));
        p.put("margenPorcentaje", new BigDecimal("40"));
        long id = crear(duenoA, p).get("id").asLong();

        // H07: el Vendedor ve el precio, no el costo ni el margen.
        getResult("/api/articulos?q=" + p.get("codigoInterno").asText(), vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].precioFinal").exists())
                .andExpect(jsonPath("$.items[0].costo").doesNotExist());
        getResult("/api/articulos/" + id, vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costo").doesNotExist())
                .andExpect(jsonPath("$.margenPorcentaje").doesNotExist());
    }

    @Test
    void elVendedorNoPuedeCrearEditarNiDarDeBaja() throws Exception {
        ObjectNode p = pedido(unico("VEND"));
        long id = crear(duenoA, p).get("id").asLong();
        long articulosAntes = cantidadArticulos();
        long historialAntes = cantidadHistorial();

        postJson("/api/articulos", vendedorA, texto(pedido(unico("VNUEVO")))).andExpect(status().isForbidden());
        p.put("descripcion", "Cambiada por el vendedor");
        putJson("/api/articulos/" + id, vendedorA, conLaVersionVigente(id, texto(p))).andExpect(status().isForbidden());
        delete("/api/articulos/" + id, vendedorA).andExpect(status().isForbidden());

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
        assertThat(filaDeArticulo(id).get("descripcion")).isEqualTo("Articulo " + p.get("codigoInterno").asText());
        assertThat(filaDeArticulo(id).get("activo")).isIn(true, 1);
    }

    // --- Alta (R-005, R-019, R-020) ---

    @Test
    void altaCompletaDa201ConPrecioCalculadoYMargenDelRubro() throws Exception {
        long rubro = crearRubro(duenoA, unico("RubA"), "35.00");
        long proveedor = crearProveedor(duenoA, unico("ProvA"));
        ObjectNode p = pedido(unico("ALTA"));
        p.put("rubroId", rubro).put("proveedorId", proveedor).put("alicuotaIva", "IVA_21");

        JsonNode creado = crear(duenoA, p);

        assertThat(precioFinal(creado)).isEqualByComparingTo("170.00");
        assertThat(creado.get("stockActual").decimalValue()).isEqualByComparingTo("0");
        assertThat(creado.get("activo").asBoolean()).isTrue();
        assertThat(creado.get("rubroId").asLong()).isEqualTo(rubro);
        assertThat(creado.get("proveedorId").asLong()).isEqualTo(proveedor);
        assertThat(filaDeArticulo(creado.get("id").asLong()).get("ferreteria_id"))
                .isEqualTo(ferreteriaDe("dueno@tornillo.demo"));
    }

    @Test
    void altaMinimaUsaIva21YElMargenPorDefectoDeLaFerreteria() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("MIN")));

        assertThat(creado.get("alicuotaIva").asText()).isEqualTo("IVA_21");
        assertThat(creado.get("rubroId").isNull()).isTrue();
        assertThat(creado.get("proveedorId").isNull()).isTrue();
        assertThat(precioFinal(creado)).isEqualByComparingTo("160.00");
    }

    @Test
    void altaConIva27CalculaElPrecio() throws Exception {
        ObjectNode p = pedido(unico("IVA27"));
        p.put("margenPorcentaje", new BigDecimal("20")).put("alicuotaIva", "IVA_27");

        assertThat(precioFinal(crear(duenoA, p))).isEqualByComparingTo("160.00");
    }

    @Test
    void stockActualYPrecioFinalDelClienteSeIgnoran() throws Exception {
        long rubro = crearRubro(duenoA, unico("RubIgn"), "35.00");
        ObjectNode p = pedido(unico("IGN"));
        p.put("rubroId", rubro).put("stockActual", 50).put("precioFinal", 5);

        JsonNode creado = crear(duenoA, p);

        assertThat(creado.get("stockActual").decimalValue()).isEqualByComparingTo("0");
        assertThat(precioFinal(creado)).isEqualByComparingTo("170.00");
    }

    @Test
    void altaGuardaUnaEntradaManualDeHistorialConAnterioresNulos() throws Exception {
        long rubro = crearRubro(duenoA, unico("RubH"), "35.00");
        ObjectNode p = pedido(unico("HIS"));
        p.put("rubroId", rubro);

        long id = crear(duenoA, p).get("id").asLong();

        List<Map<String, Object>> filas = historial(id);
        assertThat(filas).hasSize(1);
        Map<String, Object> fila = filas.get(0);
        assertThat(fila.get("origen")).isEqualTo("MANUAL");
        assertThat(fila.get("costo_anterior")).isNull();
        assertThat(fila.get("precio_anterior")).isNull();
        assertThat((BigDecimal) fila.get("costo_nuevo")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) fila.get("precio_nuevo")).isEqualByComparingTo("170.00");
        assertThat(fila.get("usuario_id")).isEqualTo(usuarioId("dueno@tornillo.demo"));
        assertThat(fila.get("ferreteria_id")).isEqualTo(ferreteriaDe("dueno@tornillo.demo"));
    }

    @Test
    void laConfiguracionDePreciosEsPorFerreteria() throws Exception {
        Object margenOriginal = jdbc.queryForObject(
                "select margen_defecto_porcentaje from ferreteria where id = ?", Object.class, ferreteriaDe("dueno@tuerca.demo"));
        jdbc.update("update ferreteria set margen_defecto_porcentaje = 50 where id = ?", ferreteriaDe("dueno@tuerca.demo"));
        try {
            JsonNode deA = crear(duenoA, pedido(unico("CFGA")));
            JsonNode deB = crear(duenoB, pedido(unico("CFGB")));

            assertThat(precioFinal(deA)).isEqualByComparingTo("160.00");
            assertThat(precioFinal(deB)).isEqualByComparingTo("190.00");
        } finally {
            jdbc.update("update ferreteria set margen_defecto_porcentaje = ? where id = ?",
                    margenOriginal, ferreteriaDe("dueno@tuerca.demo"));
        }
    }

    // --- Validaciones 400 (R-005, R-017) ---

    @Test
    void costoNegativoDa400YNoCreaNadaNiHistorial() throws Exception {
        long articulosAntes = cantidadArticulos();
        long historialAntes = cantidadHistorial();
        ObjectNode p = pedido(unico("NEG"));
        p.put("costo", new BigDecimal("-1"));

        postJson("/api/articulos", duenoA, texto(p)).andExpect(status().isBadRequest());

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void costoCeroDa400EnCostoYNoCreaNada() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("CERO"));
        p.put("costo", new BigDecimal("0.00"));

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.costo").value("El costo tiene que ser mayor que cero."));

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void editarConCostoCeroDa400YNoCambiaNada() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("CEROE")));
        long id = creado.get("id").asLong();
        ObjectNode p = pedido(creado.get("codigoInterno").asText());
        p.put("costo", BigDecimal.ZERO).put("version", creado.get("version").asLong());

        putJson("/api/articulos/" + id, duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.costo").value("El costo tiene que ser mayor que cero."));

        assertThat(filaDeArticulo(id).get("costo").toString()).isEqualTo("100.00");
    }

    @Test
    void unPrecioQueNoEntraEnLaColumnaDa400EnCostoNo409() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("BIG"));
        p.put("costo", new BigDecimal("999999999999")).put("margenPorcentaje", new BigDecimal("1000"));

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.costo").value("Con este costo y margen el precio queda demasiado grande."));

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void editarHastaUnPrecioQueNoEntraDa400YNoCambiaNada() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("BIGE")));
        long id = creado.get("id").asLong();
        ObjectNode p = pedido(creado.get("codigoInterno").asText());
        p.put("costo", new BigDecimal("999999999999")).put("margenPorcentaje", new BigDecimal("1000"))
                .put("version", creado.get("version").asLong());

        putJson("/api/articulos/" + id, duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.costo").value("Con este costo y margen el precio queda demasiado grande."));

        assertThat(filaDeArticulo(id).get("costo").toString()).isEqualTo("100.00");
    }

    @Test
    void descripcionEnBlancoDa400() throws Exception {
        ObjectNode p = pedido(unico("BLA"));
        p.put("descripcion", "   ");

        postJson("/api/articulos", duenoA, texto(p)).andExpect(status().isBadRequest());
    }

    @Test
    void ivaInvalidoDa400ConElMensajeDelSpecYNoGuardaNada() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("IVAMAL"));
        p.put("alicuotaIva", "IVA_99");

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La alícuota de IVA no es válida. Usá 0, 2,5, 5, 10,5, 21 o 27.")))
                .andExpect(jsonPath("$.errors.alicuotaIva").exists());

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void ivaNumericoFueraDeRangoTambienDa400ConElMismoMensaje() throws Exception {
        ObjectNode p = pedido(unico("IVA19"));
        p.put("alicuotaIva", 19);

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La alícuota de IVA no es válida. Usá 0, 2,5, 5, 10,5, 21 o 27.")));
    }

    @Test
    void ivaInvalidoEnEdicionDa400() throws Exception {
        ObjectNode p = pedido(unico("IVAED"));
        long id = crear(duenoA, p).get("id").asLong();
        p.put("alicuotaIva", "IVA_99");

        putJson("/api/articulos/" + id, duenoA, conLaVersionVigente(id, texto(p)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("La alícuota de IVA no es válida")));
        assertThat(filaDeArticulo(id).get("alicuota_iva")).isEqualTo("IVA_21");
    }

    @Test
    void unidadInvalidaDa400NombrandoElCampo() throws Exception {
        ObjectNode p = pedido(unico("UNIMAL"));
        p.put("unidad", "CAJA");

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("unidad")))
                .andExpect(jsonPath("$.message", containsString("UNIDAD, METRO, KILO o LITRO")))
                .andExpect(jsonPath("$.errors.unidad").exists());
    }

    /** Jackson aceptaba el ordinal del enum: "alicuotaIva": 5 se guardaba en silencio como IVA_27. */
    @Test
    void ivaComoNumeroEnRangoDa400ConElMensajeDelSpecYNoGuardaNada() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("IVAORD"));
        p.put("alicuotaIva", 5);

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La alícuota de IVA no es válida. Usá 0, 2,5, 5, 10,5, 21 o 27.")))
                .andExpect(jsonPath("$.errors.alicuotaIva").exists());

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void ivaComoNumeroEnEdicionDa400YElArticuloNoCambia() throws Exception {
        ObjectNode p = pedido(unico("IVAORDED"));
        long id = crear(duenoA, p).get("id").asLong();
        p.put("alicuotaIva", 0);

        putJson("/api/articulos/" + id, duenoA, conLaVersionVigente(id, texto(p)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("La alícuota de IVA no es válida")));
        assertThat(filaDeArticulo(id).get("alicuota_iva")).isEqualTo("IVA_21");
    }

    /** Lo mismo con la unidad: "unidad": 0 se leia como UNIDAD por ordinal. */
    @Test
    void unidadComoNumeroDa400ConElMensajeDeLaUnidadYNoGuardaNada() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("UNIORD"));
        p.put("unidad", 0);

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La unidad no es válida. Usá UNIDAD, METRO, KILO o LITRO.")))
                .andExpect(jsonPath("$.errors.unidad").exists());

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void unidadFaltanteDa400() throws Exception {
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("SINUNI"));
        p.remove("unidad");

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.unidad", is("La unidad es obligatoria")));

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void unCuerpoQueNoEsJsonSigueDandoElMensajeGenerico() throws Exception {
        postJson("/api/articulos", duenoA, "{esto no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
    }

    // --- Rubro y proveedor por tenant (R-006) ---

    @Test
    void rubroDeOtraFerreteriaDa404YNoCreaElArticulo() throws Exception {
        long rubroDeB = crearRubro(duenoB, unico("RubB"), "30.00");
        long articulosAntes = cantidadArticulos();
        ObjectNode p = pedido(unico("RUBAJ"));
        p.put("rubroId", rubroDeB);

        postJson("/api/articulos", duenoA, texto(p))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));

        assertThat(cantidadArticulos()).isEqualTo(articulosAntes);
    }

    @Test
    void proveedorDeOtraFerreteriaDa404EnAltaYEnEdicionSinCambiarElArticulo() throws Exception {
        long proveedorDeB = crearProveedor(duenoB, unico("ProvB"));
        ObjectNode p = pedido(unico("PROVAJ"));
        long id = crear(duenoA, p).get("id").asLong();

        ObjectNode otro = pedido(unico("PROVAJ2"));
        otro.put("proveedorId", proveedorDeB);
        postJson("/api/articulos", duenoA, texto(otro))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));

        p.put("proveedorId", proveedorDeB).put("descripcion", "No debe guardarse");
        putJson("/api/articulos/" + id, duenoA, conLaVersionVigente(id, texto(p)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
        assertThat(filaDeArticulo(id).get("proveedor_id")).isNull();
        assertThat(filaDeArticulo(id).get("descripcion")).isEqualTo("Articulo " + p.get("codigoInterno").asText());
    }

    @Test
    void quitarElRubroRecalculaElPrecio() throws Exception {
        long rubro = crearRubro(duenoA, unico("RubQ"), "35.00");
        ObjectNode p = pedido(unico("QUITA"));
        p.put("rubroId", rubro);
        JsonNode creado = crear(duenoA, p);
        assertThat(precioFinal(creado)).isEqualByComparingTo("170.00");
        p.putNull("rubroId");

        editarComoLaPantalla(creado.get("id").asLong(), duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rubroId").doesNotExist())
                .andExpect(jsonPath("$.precioFinal", is(160.0)));
    }

    @Test
    void cambiarDeRubroAUnoConOtroMargenRecalculaElPrecioYGuardaUnaEntradaManual() throws Exception {
        long rubroDel35 = crearRubro(duenoA, unico("RubR35"), "35.00");
        long rubroDel50 = crearRubro(duenoA, unico("RubR50"), "50.00");
        ObjectNode p = pedido(unico("CAMRUB"));
        p.put("rubroId", rubroDel35); // sin margen propio: hereda el del rubro
        JsonNode creado = crear(duenoA, p);
        long id = creado.get("id").asLong();
        assertThat(precioFinal(creado)).isEqualByComparingTo("170.00");
        p.put("rubroId", rubroDel50);

        // 100 x 1,50 x 1,21 = 181,50 que sube a 190.
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rubroId", is((int) rubroDel50)))
                .andExpect(jsonPath("$.precioFinal", is(190.0)));

        List<Map<String, Object>> filas = historial(id);
        assertThat(filas).hasSize(2);
        Map<String, Object> cambio = filas.get(1);
        assertThat(cambio.get("origen")).isEqualTo("MANUAL");
        assertThat((BigDecimal) cambio.get("costo_anterior")).isEqualByComparingTo("100");
        assertThat((BigDecimal) cambio.get("costo_nuevo")).isEqualByComparingTo("100");
        assertThat((BigDecimal) cambio.get("precio_anterior")).isEqualByComparingTo("170");
        assertThat((BigDecimal) cambio.get("precio_nuevo")).isEqualByComparingTo("190");
        assertThat(cambio.get("usuario_id")).isEqualTo(usuarioId("dueno@tornillo.demo"));
    }

    // --- PUT sin "activo" (S4): conserva el estado actual ---

    @Test
    void unPutSinActivoConservaElEstadoYConActivoExplicitoSeAplica() throws Exception {
        ObjectNode p = pedido(unico("ACT"));
        long id = crear(duenoA, p).get("id").asLong();
        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());
        p.put("descripcion", "Editado dado de baja"); // el pedido no trae "activo"

        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(false)))
                .andExpect(jsonPath("$.descripcion", is("Editado dado de baja")));
        assertThat(filaDeArticulo(id).get("activo")).isIn(false, 0);

        p.put("activo", true);
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)));
        assertThat(filaDeArticulo(id).get("activo")).isIn(true, 1);

        // Activo y sin "activo": sigue activo. Con false explicito, se da de baja.
        p.remove("activo");
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)));
        p.put("activo", false);
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(false)));
    }

    @Test
    void unAltaSinActivoSigueQuedandoActiva() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("ALTAACT")));

        assertThat(creado.get("activo").asBoolean()).isTrue();
    }

    // --- Unicidades (R-007, R-008, R-009) ---

    @Test
    void codigoInternoDuplicadoDa409ConElMensajeDelSpec() throws Exception {
        String codigo = unico("DUP");
        crear(duenoA, pedido(codigo));
        long historialAntes = cantidadHistorial();

        postJson("/api/articulos", duenoA, texto(pedido(codigo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un artículo con el código interno " + codigo + ".")));

        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void codigoInternoDeUnArticuloDadoDeBajaTambienChoca() throws Exception {
        String codigo = unico("DUPBAJA");
        long id = crear(duenoA, pedido(codigo)).get("id").asLong();
        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/articulos", duenoA, texto(pedido(codigo))).andExpect(status().isConflict());
    }

    @Test
    void elMismoCodigoInternoEnOtraFerreteriaSePuedeCrear() throws Exception {
        String codigo = unico("COMPART");
        crear(duenoA, pedido(codigo));

        crear(duenoB, pedido(codigo));
    }

    @Test
    void editarUnArticuloConElCodigoInternoDeOtroDa409PeroConElPropioNo() throws Exception {
        String codigo1 = unico("EDC1");
        ObjectNode p2 = pedido(unico("EDC2"));
        crear(duenoA, pedido(codigo1));
        long id2 = crear(duenoA, p2).get("id").asLong();

        p2.put("descripcion", "Solo cambia la descripcion");
        editarComoLaPantalla(id2, duenoA, p2).andExpect(status().isOk());

        p2.put("codigoInterno", codigo1);
        editarComoLaPantalla(id2, duenoA, p2).andExpect(status().isConflict());
    }

    @Test
    void codigoDeBarrasDuplicadoEntreActivosDa409() throws Exception {
        String barras = unico("779");
        ObjectNode a = pedido(unico("BAR1"));
        a.put("codigoBarras", barras);
        crear(duenoA, a);
        ObjectNode b = pedido(unico("BAR2"));
        b.put("codigoBarras", barras);

        postJson("/api/articulos", duenoA, texto(b))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un artículo activo con el código de barras " + barras + ".")));
    }

    @Test
    void elBarrasDeUnArticuloDadoDeBajaNoBloqueaYReactivarConBarrasTomadoDa409() throws Exception {
        String barras = unico("780");
        ObjectNode x = pedido(unico("BARX"));
        x.put("codigoBarras", barras);
        long idX = crear(duenoA, x).get("id").asLong();
        delete("/api/articulos/" + idX, duenoA).andExpect(status().isNoContent());

        ObjectNode y = pedido(unico("BARY"));
        y.put("codigoBarras", barras);
        crear(duenoA, y);

        x.put("activo", true);
        editarComoLaPantalla(idX, duenoA, x).andExpect(status().isConflict());
        assertThat(filaDeArticulo(idX).get("activo")).isIn(false, 0);
    }

    @Test
    void variosArticulosActivosSinBarrasSePuedenCrear() throws Exception {
        crear(duenoA, pedido(unico("SB1")));
        crear(duenoA, pedido(unico("SB2")));
    }

    @Test
    void codigoDeProveedorDuplicadoConElMismoProveedorDa409YConOtroProveedorNo() throws Exception {
        long p1 = crearProveedor(duenoA, unico("ProvU1"));
        long p2 = crearProveedor(duenoA, unico("ProvU2"));
        String codigoProv = unico("AB");
        ObjectNode a = pedido(unico("CPA"));
        a.put("proveedorId", p1).put("codigoProveedor", codigoProv);
        crear(duenoA, a);

        ObjectNode mismo = pedido(unico("CPB"));
        mismo.put("proveedorId", p1).put("codigoProveedor", codigoProv);
        postJson("/api/articulos", duenoA, texto(mismo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un artículo de este proveedor con el código " + codigoProv + ".")));

        ObjectNode otro = pedido(unico("CPC"));
        otro.put("proveedorId", p2).put("codigoProveedor", codigoProv);
        crear(duenoA, otro);
    }

    @Test
    void elMismoCodigoDeProveedorSinProveedorNoChoca() throws Exception {
        String codigoProv = unico("SP");
        ObjectNode a = pedido(unico("SPA"));
        a.put("codigoProveedor", codigoProv);
        ObjectNode b = pedido(unico("SPB"));
        b.put("codigoProveedor", codigoProv);

        crear(duenoA, a);
        crear(duenoA, b);
    }

    // --- Edicion (R-010, R-021) ---

    @Test
    void cambiarElCostoRecalculaElPrecioYGuardaOtraEntradaManual() throws Exception {
        long rubro = crearRubro(duenoA, unico("RubE"), "35.00");
        ObjectNode p = pedido(unico("EDI"));
        p.put("rubroId", rubro);
        long id = crear(duenoA, p).get("id").asLong();
        p.put("costo", new BigDecimal("200"));

        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioFinal", is(330.0)));

        List<Map<String, Object>> filas = historial(id);
        assertThat(filas).hasSize(2);
        Map<String, Object> cambio = filas.get(1);
        assertThat(cambio.get("origen")).isEqualTo("MANUAL");
        assertThat((BigDecimal) cambio.get("costo_anterior")).isEqualByComparingTo("100");
        assertThat((BigDecimal) cambio.get("costo_nuevo")).isEqualByComparingTo("200");
        assertThat((BigDecimal) cambio.get("precio_anterior")).isEqualByComparingTo("170");
        assertThat((BigDecimal) cambio.get("precio_nuevo")).isEqualByComparingTo("330");
        assertThat(cambio.get("ferreteria_id")).isEqualTo(ferreteriaDe("dueno@tornillo.demo"));
        assertThat(cambio.get("usuario_id")).isEqualTo(usuarioId("dueno@tornillo.demo"));
    }

    @Test
    void cambiarSoloLaDescripcionNoAgregaHistorial() throws Exception {
        ObjectNode p = pedido(unico("SOLODESC"));
        long id = crear(duenoA, p).get("id").asLong();
        p.put("descripcion", "Nueva descripcion");

        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion", is("Nueva descripcion")));

        assertThat(historial(id)).hasSize(1);
    }

    @Test
    void unCambioDeMargenQueNoMuevaElPrecioRedondeadoNoAgregaHistorial() throws Exception {
        ObjectNode p = pedido(unico("MARG"));
        p.put("margenPorcentaje", new BigDecimal("35"));
        long id = crear(duenoA, p).get("id").asLong();
        p.put("margenPorcentaje", new BigDecimal("36"));

        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioFinal", is(170.0)));

        assertThat(historial(id)).hasSize(1);
    }

    @Test
    void elStockActualNoSeEditaPorLaApi() throws Exception {
        ObjectNode p = pedido(unico("STK"));
        long id = crear(duenoA, p).get("id").asLong();
        jdbc.update("update articulo set stock_actual = 12 where id = ?", id);
        p.put("stockActual", 99).put("descripcion", "Con stock");

        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockActual", is(12.0)));

        assertThat((BigDecimal) filaDeArticulo(id).get("stock_actual")).isEqualByComparingTo("12");
    }

    // --- Precio de lista del proveedor (CU-04, R-036) ---

    @Test
    void unArticuloCargadoAManoNoTienePrecioDeListaDelProveedor() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("PLN")));
        long id = creado.get("id").asLong();

        assertThat(sinValor(creado, "precioListaProveedor")).isTrue();
        getResult("/api/articulos/" + id, duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioListaProveedor").doesNotExist());
    }

    @Test
    void laRespuestaMuestraElPrecioDeListaFijadoPorLaImportacion() throws Exception {
        long id = crear(duenoA, pedido(unico("PLV"))).get("id").asLong();
        jdbc.update("update articulo set precio_lista_proveedor = 1210.00 where id = ?", id);

        getResult("/api/articulos/" + id, duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioListaProveedor", is(1210.0)));
        getResult("/api/articulos/" + id, vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioListaProveedor").doesNotExist());
    }

    @Test
    void unPutConservaElPrecioDeListaConYSinCambioDeCostoAunqueElCuerpoLoTraiga() throws Exception {
        ObjectNode p = pedido(unico("PLP"));
        long id = crear(duenoA, p).get("id").asLong();
        jdbc.update("update articulo set precio_lista_proveedor = 1210.00 where id = ?", id);

        p.put("descripcion", "Solo descripcion").put("precioListaProveedor", new BigDecimal("1"));
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioListaProveedor", is(1210.0)));
        assertThat((BigDecimal) filaDeArticulo(id).get("precio_lista_proveedor")).isEqualByComparingTo("1210.00");

        p.put("costo", new BigDecimal("200")).put("precioListaProveedor", new BigDecimal("2"));
        editarComoLaPantalla(id, duenoA, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioListaProveedor", is(1210.0)));
        assertThat((BigDecimal) filaDeArticulo(id).get("precio_lista_proveedor")).isEqualByComparingTo("1210.00");
    }

    @Test
    void laApiNoAceptaPrecioDeListaEnElAlta() throws Exception {
        ObjectNode p = pedido(unico("PLA")).put("precioListaProveedor", new BigDecimal("500"));

        JsonNode creado = crear(duenoA, p);

        assertThat(sinValor(creado, "precioListaProveedor")).isTrue();
        assertThat(filaDeArticulo(creado.get("id").asLong()).get("precio_lista_proveedor")).isNull();
    }

    // --- Aislamiento de escrituras (R-010, R-011) ---

    @Test
    void putYDeleteDeArticuloAjenoDan404SinCambiarNada() throws Exception {
        ObjectNode deB = pedido(unico("INTB"));
        long idB = crear(duenoB, deB).get("id").asLong();
        long historialAntes = historial(idB).size();
        deB.put("descripcion", "Hackeado desde A").put("costo", new BigDecimal("999"));

        putJson("/api/articulos/" + idB, duenoA, conLaVersionVigente(idB, texto(deB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Artículo no encontrado")));
        delete("/api/articulos/" + idB, duenoA).andExpect(status().isNotFound());

        Map<String, Object> fila = filaDeArticulo(idB);
        assertThat(fila.get("descripcion")).isEqualTo("Articulo " + deB.get("codigoInterno").asText());
        assertThat((BigDecimal) fila.get("costo")).isEqualByComparingTo("100");
        assertThat(fila.get("activo")).isIn(true, 1);
        assertThat(historial(idB)).hasSize((int) historialAntes);
    }

    // --- Baja logica (R-011, R-023) ---

    @Test
    void laBajaDa204ConservaElHistorialYSaleDelListadoPorDefecto() throws Exception {
        String codigo = unico("BAJA");
        JsonNode creado = crear(duenoA, pedido(codigo));
        long id = creado.get("id").asLong();
        long historialAntes = historial(id).size();

        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());

        assertThat(filaDeArticulo(id).get("activo")).isIn(false, 0);
        assertThat(historial(id)).hasSize((int) historialAntes);
        assertThat(codigos(getJson("/api/articulos?q=" + codigo, duenoA))).isEmpty();
        assertThat(codigos(getJson("/api/articulos?q=" + codigo + "&incluirInactivos=true", duenoA)))
                .containsExactly(codigo);
        getResult("/api/articulos/" + id, vendedorA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(false)));
    }

    // --- Codigos repetidos: todos juntos y por campo ---

    @Test
    void conVariosCodigosRepetidosEl409TraeUnErrorPorCampo() throws Exception {
        String codigo = unico("REP");
        String barras = unico("779");
        ObjectNode original = pedido(codigo);
        original.put("codigoBarras", barras);
        crear(duenoA, original);

        ObjectNode repetido = pedido(codigo);
        repetido.put("codigoBarras", barras);
        postJson("/api/articulos", duenoA, texto(repetido))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.codigoInterno", is("Ya existe un artículo con el código interno " + codigo + ".")))
                .andExpect(jsonPath("$.errors.codigoBarras",
                        is("Ya existe un artículo activo con el código de barras " + barras + ".")));
    }

    // --- Reactivacion ---

    @Test
    void reactivarUnArticuloDadoDeBajaLoVuelveAlListado() throws Exception {
        String codigo = unico("REA");
        long id = crear(duenoA, pedido(codigo)).get("id").asLong();
        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/articulos/" + id + "/reactivacion", duenoA, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo", is(true)))
                .andExpect(jsonPath("$.codigoInterno", is(codigo)));

        assertThat(filaDeArticulo(id).get("activo")).isEqualTo(true);
        assertThat(codigos(getJson("/api/articulos?q=" + codigo, duenoA))).contains(codigo);
    }

    @Test
    void reactivarConElCodigoDeBarrasTomadoPorOtroActivoDa409YSigueDeBaja() throws Exception {
        String barras = unico("779");
        ObjectNode viejo = pedido(unico("VIE"));
        viejo.put("codigoBarras", barras);
        long idViejo = crear(duenoA, viejo).get("id").asLong();
        delete("/api/articulos/" + idViejo, duenoA).andExpect(status().isNoContent());
        ObjectNode nuevo = pedido(unico("NUE"));
        nuevo.put("codigoBarras", barras);
        crear(duenoA, nuevo);

        postJson("/api/articulos/" + idViejo + "/reactivacion", duenoA, "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un artículo activo con el código de barras " + barras + ".")));

        assertThat(filaDeArticulo(idViejo).get("activo")).isEqualTo(false);
    }

    @Test
    void reactivarUnArticuloDeOtraFerreteriaDa404YNoLoCambia() throws Exception {
        long idDeB = crear(duenoB, pedido(unico("REB"))).get("id").asLong();
        delete("/api/articulos/" + idDeB, duenoB).andExpect(status().isNoContent());

        postJson("/api/articulos/" + idDeB + "/reactivacion", duenoA, "").andExpect(status().isNotFound());

        assertThat(filaDeArticulo(idDeB).get("activo")).isEqualTo(false);
    }

    @Test
    void elVendedorNoPuedeReactivar() throws Exception {
        long id = crear(duenoA, pedido(unico("REV"))).get("id").asLong();
        delete("/api/articulos/" + id, duenoA).andExpect(status().isNoContent());

        postJson("/api/articulos/" + id + "/reactivacion", vendedorA, "").andExpect(status().isForbidden());

        assertThat(filaDeArticulo(id).get("activo")).isEqualTo(false);
    }

    /**
     * Un PUT como el de la pantalla: con la version que devuelve el GET del articulo, no con una leida de la base. Los
     * tests que escriben usan este; conLaVersionVigente queda para los que no dependen de la version.
     */
    private org.springframework.test.web.servlet.ResultActions editarComoLaPantalla(long id, String token, ObjectNode pedido)
            throws Exception {
        ObjectNode cuerpo = pedido.deepCopy();
        cuerpo.set("version", getJson("/api/articulos/" + id, token).get("version"));
        return putJson("/api/articulos/" + id, token, texto(cuerpo));
    }
}
