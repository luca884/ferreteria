package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.service.RecalculoPreciosService;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edicion de rubro por HTTP contra MySQL real: la cascada de precios cuando cambia el margen
 * (solo herederos activos del rubro y de la ferreteria), el historial MASIVO, la atomicidad y los
 * permisos. A = El Tornillo (margen por defecto 30, multiplo 10), B = La Tuerca. Cada test crea
 * su propio rubro y sus articulos con nombres y codigos unicos (la base se comparte).
 *
 * Con costo 100 e IVA 21 (la base de estos tests): margen 35 da 170, margen 50 da 190, margen 40
 * da 170 (169.40 sube a 170) y margen 36 da 170 (164.56 sube a 170).
 */
class RubroCascadaIntegrationTest extends IntegrationTestBase {

    /** Spy sobre el servicio real: solo se le cambia el comportamiento en el test de atomicidad. */
    @SpyBean
    private RecalculoPreciosService recalculoPreciosService;

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

    private long crearRubro(String token, String nombre, String margen) throws Exception {
        String body = postJson("/api/rubros", token,
                "{\"nombre\":\"" + nombre + "\",\"margenPorcentaje\":" + margen + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Crea un articulo de costo 100 e IVA 21 en el rubro (null = sin rubro) con el margen propio (null = hereda). */
    private long crearArticulo(String token, String prefijo, Long rubroId, String margenPropio) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico(prefijo))
                .put("descripcion", "Articulo " + prefijo)
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"));
        if (rubroId != null) {
            pedido.put("rubroId", rubroId);
        }
        if (margenPropio != null) {
            pedido.put("margenPorcentaje", new BigDecimal(margenPropio));
        }
        String body = postJson("/api/articulos", token, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String json(String nombre, String margen) {
        return "{\"nombre\":\"" + nombre + "\",\"margenPorcentaje\":" + margen + "}";
    }

    private BigDecimal precioDe(long articuloId) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private List<Map<String, Object>> historial(long articuloId) {
        return jdbc.queryForList(
                "select ferreteria_id, costo_anterior, costo_nuevo, precio_anterior, precio_nuevo, origen, usuario_id"
                        + " from historial_precio where articulo_id = ? order by id", articuloId);
    }

    private List<Map<String, Object>> masivos(long articuloId) {
        return historial(articuloId).stream().filter(h -> "MASIVO".equals(h.get("origen"))).toList();
    }

    /**
     * @SpyBean envuelve al bean real y Spring le pone encima el proxy transaccional: stubbear
     * sobre el proxy ejecutaria la llamada con la transaccion MANDATORY sin transaccion. Se
     * stubbea el spy de abajo; las llamadas reales siguen entrando por el proxy.
     */
    private RecalculoPreciosService objetivoDelSpy() {
        return AopTestUtils.getUltimateTargetObject(recalculoPreciosService);
    }

    private long cantidadHistorial() {
        return jdbc.queryForObject("select count(*) from historial_precio", Long.class);
    }

    private long usuarioId(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    private Map<String, Object> filaDeRubro(long id) {
        return jdbc.queryForMap("select nombre, margen_porcentaje from rubro where id = ?", id);
    }

    private void assertPrecio(long articuloId, String esperado) {
        assertThat(precioDe(articuloId)).isEqualByComparingTo(esperado);
    }

    // --- Cascada (R-024, R-022) ---

    @Test
    void subirElMargenRecalculaSoloALosHerederosActivosDelRubroYGuardaHistorialMasivo() throws Exception {
        long rubro = crearRubro(duenoA, unico("Cascada"), "35");
        long x = crearArticulo(duenoA, "CX", rubro, null);
        long y = crearArticulo(duenoA, "CY", rubro, "40");
        long z = crearArticulo(duenoA, "CZ", rubro, null);
        assertPrecio(x, "170.00");
        assertPrecio(y, "170.00");
        long historialY = historial(y).size();

        putJson("/api/rubros/" + rubro, duenoA, json(unico("Cascada"), "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.margenPorcentaje", is(50)));

        assertPrecio(x, "190.00");
        assertPrecio(z, "190.00");
        assertPrecio(y, "170.00");
        long dueno = usuarioId("dueno@tornillo.demo");
        long ferreteriaA = jdbc.queryForObject("select ferreteria_id from usuario where id = ?", Long.class, dueno);
        for (long heredero : new long[] {x, z}) {
            List<Map<String, Object>> masivo = masivos(heredero);
            assertThat(masivo).hasSize(1);
            Map<String, Object> fila = masivo.get(0);
            assertThat((BigDecimal) fila.get("costo_anterior")).isEqualByComparingTo("100");
            assertThat((BigDecimal) fila.get("costo_nuevo")).isEqualByComparingTo("100");
            assertThat((BigDecimal) fila.get("precio_anterior")).isEqualByComparingTo("170");
            assertThat((BigDecimal) fila.get("precio_nuevo")).isEqualByComparingTo("190");
            assertThat(((Number) fila.get("usuario_id")).longValue()).isEqualTo(dueno);
            assertThat(((Number) fila.get("ferreteria_id")).longValue()).isEqualTo(ferreteriaA);
        }
        // Y tiene margen propio: ni precio nuevo ni entradas nuevas.
        assertThat(historial(y)).hasSize((int) historialY);
        assertThat(masivos(y)).isEmpty();
    }

    @Test
    void inactivosOtroRubroSinRubroYOtraFerreteriaNoCambian() throws Exception {
        long rubro = crearRubro(duenoA, unico("Objetivo"), "35");
        long otroRubro = crearRubro(duenoA, unico("Otro"), "35");
        long rubroDeB = crearRubro(duenoB, unico("DeB"), "35");

        long heredero = crearArticulo(duenoA, "HER", rubro, null);
        long inactivo = crearArticulo(duenoA, "INA", rubro, null);
        delete("/api/articulos/" + inactivo, duenoA).andExpect(status().isNoContent());
        long deOtroRubro = crearArticulo(duenoA, "OTR", otroRubro, null);
        long sinRubro = crearArticulo(duenoA, "SIN", null, null);
        long deOtraFerreteria = crearArticulo(duenoB, "AJE", rubroDeB, null);

        long historialAntes = cantidadHistorial();
        long historialInactivo = historial(inactivo).size();
        long historialOtroRubro = historial(deOtroRubro).size();
        long historialSinRubro = historial(sinRubro).size();
        long historialAjeno = historial(deOtraFerreteria).size();

        putJson("/api/rubros/" + rubro, duenoA, json(unico("Objetivo"), "50")).andExpect(status().isOk());

        assertPrecio(heredero, "190.00");
        assertPrecio(inactivo, "170.00");
        assertPrecio(deOtroRubro, "170.00");
        assertPrecio(sinRubro, "160.00"); // margen por defecto de la ferreteria: 30
        assertPrecio(deOtraFerreteria, "170.00");
        assertThat(historial(inactivo)).hasSize((int) historialInactivo);
        assertThat(historial(deOtroRubro)).hasSize((int) historialOtroRubro);
        assertThat(historial(sinRubro)).hasSize((int) historialSinRubro);
        assertThat(historial(deOtraFerreteria)).hasSize((int) historialAjeno);
        // La unica entrada nueva de todo el sistema es la del heredero activo.
        assertThat(cantidadHistorial()).isEqualTo(historialAntes + 1);
        assertThat(masivos(heredero)).hasSize(1);
    }

    @Test
    void soloRenombrarNoRecalculaNiGuardaHistorial() throws Exception {
        long rubro = crearRubro(duenoA, unico("Viejo"), "35");
        long heredero = crearArticulo(duenoA, "REN", rubro, null);
        long historialAntes = cantidadHistorial();
        String nuevoNombre = unico("Nuevo");

        // 35.00 y 35 son el mismo margen: no cuenta como cambio.
        putJson("/api/rubros/" + rubro, duenoA, json(nuevoNombre, "35.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre", is(nuevoNombre)));

        assertThat(filaDeRubro(rubro).get("nombre")).isEqualTo(nuevoNombre);
        assertPrecio(heredero, "170.00");
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void cambioDeMargenQueNoMuevePrecioRedondeadoNoGuardaEntrada() throws Exception {
        long rubro = crearRubro(duenoA, unico("Mismo"), "35");
        long heredero = crearArticulo(duenoA, "MIS", rubro, null);
        long historialAntes = cantidadHistorial();

        // 100 x 1,36 x 1,21 = 164.56 sube a 170: el precio queda igual.
        putJson("/api/rubros/" + rubro, duenoA, json(unico("Mismo"), "36")).andExpect(status().isOk());

        assertThat((BigDecimal) filaDeRubro(rubro).get("margen_porcentaje")).isEqualByComparingTo("36");
        assertPrecio(heredero, "170.00");
        assertThat(masivos(heredero)).isEmpty();
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    // --- Atomicidad ---

    @Test
    void siLaCascadaFallaSeRevierteElRubroLosPreciosYElHistorial() throws Exception {
        String nombre = unico("Atomico");
        long rubro = crearRubro(duenoA, nombre, "35");
        long heredero = crearArticulo(duenoA, "ATO", rubro, null);
        long historialAntes = cantidadHistorial();
        // Corre la cascada de verdad (cambia precios y escribe historial) y recien despues falla.
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            throw new IllegalStateException("falla simulada despues de recalcular");
        }).when(objetivoDelSpy()).porCambioDeMargenDeRubro(any(), any());

        putJson("/api/rubros/" + rubro, duenoA, json(unico("Renombrado"), "50"))
                .andExpect(status().isInternalServerError());

        Map<String, Object> fila = filaDeRubro(rubro);
        assertThat(fila.get("nombre")).isEqualTo(nombre);
        assertThat((BigDecimal) fila.get("margen_porcentaje")).isEqualByComparingTo("35");
        assertPrecio(heredero, "170.00");
        assertThat(masivos(heredero)).isEmpty();
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    // --- Errores y permisos ---

    @Test
    void rubroDeOtraFerreteriaDa404YNoCambiaNada() throws Exception {
        String nombreDeB = unico("RubroB");
        long rubroDeB = crearRubro(duenoB, nombreDeB, "35");
        long articuloDeB = crearArticulo(duenoB, "RB", rubroDeB, null);
        long historialAntes = cantidadHistorial();

        putJson("/api/rubros/" + rubroDeB, duenoA, json(unico("Intruso"), "50"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));

        assertThat(filaDeRubro(rubroDeB).get("nombre")).isEqualTo(nombreDeB);
        assertThat((BigDecimal) filaDeRubro(rubroDeB).get("margen_porcentaje")).isEqualByComparingTo("35");
        assertPrecio(articuloDeB, "170.00");
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void vendedorNoPuedeEditarUnRubroYNoCambiaNada() throws Exception {
        String nombre = unico("SoloDueno");
        long rubro = crearRubro(duenoA, nombre, "35");
        long heredero = crearArticulo(duenoA, "VEN", rubro, null);
        long historialAntes = cantidadHistorial();

        putJson("/api/rubros/" + rubro, vendedorA, json(unico("Vendedor"), "50"))
                .andExpect(status().isForbidden());

        assertThat(filaDeRubro(rubro).get("nombre")).isEqualTo(nombre);
        assertPrecio(heredero, "170.00");
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void nombreDuplicadoDa409YNoRecalculaNada() throws Exception {
        String ocupado = unico("Ocupado");
        crearRubro(duenoA, ocupado, "35");
        String nombre = unico("Editado");
        long rubro = crearRubro(duenoA, nombre, "35");
        long heredero = crearArticulo(duenoA, "DUP", rubro, null);
        long historialAntes = cantidadHistorial();

        putJson("/api/rubros/" + rubro, duenoA, json(ocupado, "50"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ya existe un rubro llamado " + ocupado + ".")));

        Map<String, Object> fila = filaDeRubro(rubro);
        assertThat(fila.get("nombre")).isEqualTo(nombre);
        assertThat((BigDecimal) fila.get("margen_porcentaje")).isEqualByComparingTo("35");
        assertPrecio(heredero, "170.00");
        assertThat(cantidadHistorial()).isEqualTo(historialAntes);
    }

    @Test
    void elListadoDeArticulosMuestraElPrecioNuevoTrasLaCascada() throws Exception {
        long rubro = crearRubro(duenoA, unico("Visible"), "35");
        String codigo = unico("VIS");
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", codigo)
                .put("descripcion", "Articulo visible")
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"))
                .put("rubroId", rubro);
        postJson("/api/articulos", duenoA, objectMapper.writeValueAsString(pedido)).andExpect(status().isCreated());

        putJson("/api/rubros/" + rubro, duenoA, json(unico("Visible"), "50")).andExpect(status().isOk());

        JsonNode pagina = getJson("/api/articulos?q=" + codigo, duenoA);
        assertThat(pagina.get("items")).hasSize(1);
        assertThat(pagina.get("items").get(0).get("precioFinal").decimalValue()).isEqualByComparingTo("190");
    }
}
