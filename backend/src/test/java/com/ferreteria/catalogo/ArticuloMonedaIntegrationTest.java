package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Articulos con moneda por HTTP contra MySQL (T-034, T-035 y T-037): alta y edicion en USD con el dolar vigente de la
 * ferreteria, los dos 409, el permiso del Dueno y el aislamiento entre ferreterias. Ferreterias propias (CUIT
 * 30-77100001-1 y 30-77100002-2) y casa propia (t35-): la tabla cotizacion es global.
 *
 * <p>Con costo 10, margen 30 %, IVA 21 % y multiplo 10: dolar 1000 = 15.730; 1100 = 17.310; 950 = 14.950.
 */
class ArticuloMonedaIntegrationTest extends CotizacionTestBase {

    private static final String URL = "/api/articulos";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    private static final String MENSAJE_APAGADA = "Activá la cotización para cargar artículos en dólares";
    private static final String MENSAJE_SIN_COTIZACION = "Todavía no hay cotización del dólar. Probá en unos minutos";

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77100001-1", "Moneda A");
        b = ferreteria("30-77100002-2", "Moneda B");
        borrarCotizaciones("t35-");
        // La API va por 1100, pero el dolar VIGENTE de A es 1000 (lo fijo su recalculo de las 08:00) y el de B, 950.
        cotizacion("USD", "t35-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> {
            c.setCasaPrecios("t35-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t35-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t35-oficial");
            c.fijarVigente(new BigDecimal("950.0000"), "t35-oficial", LadoCotizacion.COMPRA, HACE_UN_RATO);
        });
        duenoA = login(usuario(a, "dueno-t35a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t35a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t35b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t35-");
        borrarDatosDe(a, b);
    }

    // ---- helpers ----

    /** Pedido valido: costo 10 en la moneda indicada (null = sin el campo). */
    private ObjectNode pedido(String codigo, String moneda) {
        ObjectNode p = objectMapper.createObjectNode()
                .put("codigoInterno", codigo)
                .put("descripcion", "Articulo " + codigo)
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("10"));
        if (moneda != null) {
            p.put("moneda", moneda);
        }
        return p;
    }

    private JsonNode cuerpo(org.springframework.test.web.servlet.ResultActions resultado) throws Exception {
        return objectMapper.readTree(
                resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode crear(String token, ObjectNode pedido) throws Exception {
        return cuerpo(postJson(URL, token, objectMapper.writeValueAsString(pedido)).andExpect(status().isCreated()));
    }

    private org.springframework.test.web.servlet.ResultActions editar(String token, long id, ObjectNode pedido)
            throws Exception {
        return putJson(URL + "/" + id, token, conLaVersionVigente(id, objectMapper.writeValueAsString(pedido)));
    }

    private long cantidadDeArticulos(Long ferreteriaId) {
        return jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    private String monedaGuardada(long id) {
        return jdbc.queryForObject("select moneda from articulo where id = ?", String.class, id);
    }

    private void apagarLaCotizacionDeA() {
        configuracion(a, c -> {
            c.setActiva(false);
            c.setCasaPrecios("t35-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t35-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
    }

    private void sinNingunaCotizacionParaA() {
        configuracion(a, c -> c.setCasaPrecios("t35-no-existe"));
    }

    // ---- T-034: la moneda en el pedido y en la respuesta ----

    @Test
    @DisplayName("sin moneda el alta es en pesos y la respuesta trae moneda ARS")
    void altaSinMonedaEsEnPesos() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("ARS"), null));

        assertThat(creado.get("moneda").asText()).isEqualTo("ARS");
        // 10 x 1,30 x 1,21 = 15,73 -> 20
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("una moneda que no es ARS ni USD (EUR, BRL, minuscula, numero) da 400 con mensaje en espanol y no crea nada")
    void monedaInvalidaDa400() throws Exception {
        long antes = cantidadDeArticulos(a);

        for (String invalida : new String[] {"EUR", "BRL", "usd", ""}) {
            postJson(URL, duenoA, objectMapper.writeValueAsString(pedido(unico("MON"), invalida)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("La moneda no es válida. Usá ARS o USD.")))
                    .andExpect(jsonPath("$.errors.moneda").exists());
        }
        ObjectNode comoNumero = pedido(unico("MON"), null);
        comoNumero.put("moneda", 1);
        postJson(URL, duenoA, objectMapper.writeValueAsString(comoNumero))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La moneda no es válida. Usá ARS o USD.")));

        assertThat(cantidadDeArticulos(a)).isEqualTo(antes);
    }

    @Test
    @DisplayName("una moneda invalida en la edicion da 400 y el articulo no cambia")
    void monedaInvalidaEnLaEdicion() throws Exception {
        ObjectNode p = pedido(unico("MONED"), null);
        long id = crear(duenoA, p).get("id").asLong();
        p.put("moneda", "EUR");

        editar(duenoA, id, p)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.moneda").exists());

        assertThat(monedaGuardada(id)).isEqualTo("ARS");
    }

    @Test
    @DisplayName("el detalle y el listado devuelven la moneda de cada articulo")
    void lecturaConMoneda() throws Exception {
        String codigo = unico("LEC");
        long id = crear(duenoA, pedido(codigo, "USD")).get("id").asLong();

        assertThat(getJson(URL + "/" + id, vendedorA).get("moneda").asText()).isEqualTo("USD");
        JsonNode pagina = getJson(URL + "?q=" + codigo, vendedorA);
        assertThat(pagina.get("items")).hasSize(1);
        assertThat(pagina.get("items").get(0).get("moneda").asText()).isEqualTo("USD");
    }

    // ---- T-035: alta y edicion con el dolar vigente ----

    @Test
    @DisplayName("alta en USD: costo 10,00 USD y precio 15.730 en pesos con el vigente 1000 (no el 1100 de la API), historial MANUAL en USD")
    void altaEnDolares() throws Exception {
        JsonNode creado = crear(duenoA, pedido(unico("USD"), "USD"));

        assertThat(creado.get("moneda").asText()).isEqualTo("USD");
        assertThat(creado.get("costo").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");
        assertThat(historialDe(creado.get("id").asLong())).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MANUAL");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t35a@test.local"));
        });
    }

    @Test
    @DisplayName("pasar un articulo de ARS a USD con costo 10: el Dueno ve 15.730 y queda historial MANUAL con costo 100 -> 10")
    void pasarDePesosADolares() throws Exception {
        ObjectNode p = pedido(unico("PASE"), null);
        p.put("costo", new BigDecimal("100"));
        long id = crear(duenoA, p).get("id").asLong();
        p.put("costo", new BigDecimal("10")).put("moneda", "USD");

        JsonNode editado = cuerpo(editar(duenoA, id, p).andExpect(status().isOk()));

        assertThat(editado.get("moneda").asText()).isEqualTo("USD");
        assertThat(editado.get("costo").decimalValue()).isEqualByComparingTo("10");
        assertThat(editado.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");
        assertThat(historialDe(id)).hasSize(2);
        assertThat(historialDe(id).get(1)).satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MANUAL");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat((BigDecimal) h.get("costo_anterior")).isEqualByComparingTo("100");
            assertThat((BigDecimal) h.get("costo_nuevo")).isEqualByComparingTo("10");
            assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo("15730");
        });
    }

    @Test
    @DisplayName("el Vendedor recibe 403 al querer cambiar la moneda, y al crear en USD; nada cambia")
    void vendedorNoPuede() throws Exception {
        ObjectNode p = pedido(unico("VEN"), null);
        long id = crear(duenoA, p).get("id").asLong();
        long antes = cantidadDeArticulos(a);
        p.put("moneda", "USD");

        editar(vendedorA, id, p).andExpect(status().isForbidden());
        postJson(URL, vendedorA, objectMapper.writeValueAsString(pedido(unico("VEN2"), "USD")))
                .andExpect(status().isForbidden());

        assertThat(monedaGuardada(id)).isEqualTo("ARS");
        assertThat(cantidadDeArticulos(a)).isEqualTo(antes);
    }

    @Test
    @DisplayName("alta en USD sin ninguna cotizacion: 409 «Todavía no hay cotización…» y no se crea; en pesos se crea normal")
    void altaEnDolaresSinCotizacion() throws Exception {
        sinNingunaCotizacionParaA();
        long antes = cantidadDeArticulos(a);

        postJson(URL, duenoA, objectMapper.writeValueAsString(pedido(unico("SINCOT"), "USD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_SIN_COTIZACION)));

        assertThat(cantidadDeArticulos(a)).isEqualTo(antes);
        assertThat(crear(duenoA, pedido(unico("ENPESOS"), "ARS")).get("moneda").asText()).isEqualTo("ARS");
    }

    @Test
    @DisplayName("alta en USD con la cotizacion apagada: 409 «Activá la cotización…» y no se crea; en pesos se crea normal")
    void altaEnDolaresConCotizacionApagada() throws Exception {
        apagarLaCotizacionDeA();
        long antes = cantidadDeArticulos(a);

        postJson(URL, duenoA, objectMapper.writeValueAsString(pedido(unico("APAG"), "USD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_APAGADA)));

        assertThat(cantidadDeArticulos(a)).isEqualTo(antes);
        crear(duenoA, pedido(unico("APAGARS"), "ARS"));
    }

    @Test
    @DisplayName("pasar de ARS a USD con la cotizacion apagada: 409 con el mismo mensaje y el articulo sigue en ARS")
    void pasarAUsdConCotizacionApagada() throws Exception {
        ObjectNode p = pedido(unico("PASEAP"), null);
        long id = crear(duenoA, p).get("id").asLong();
        apagarLaCotizacionDeA();
        p.put("moneda", "USD");

        editar(duenoA, id, p)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(MENSAJE_APAGADA)));

        assertThat(monedaGuardada(id)).isEqualTo("ARS");
        assertThat(historialDe(id)).hasSize(1);
    }

    @Test
    @DisplayName("editar uno que ya es USD con la cotizacion apagada se permite: margen 40 % = 16.940 con el vigente 1000 aunque la API vaya a 1100")
    void editarUnoEnDolaresConCotizacionApagada() throws Exception {
        Long id = articuloUsd(a, unico("CONG"), "10.00", "15730.00");
        apagarLaCotizacionDeA();
        ObjectNode p = pedido("CONG-EDIT", "USD").put("margenPorcentaje", new BigDecimal("40"));
        p.put("codigoInterno", jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, id));

        JsonNode editado = cuerpo(editar(duenoA, id, p).andExpect(status().isOk()));

        assertThat(editado.get("precioFinal").decimalValue()).isEqualByComparingTo("16940");
        assertThat(precioDe(id)).isEqualByComparingTo("16940");
        assertThat(historialDe(id)).singleElement()
                .satisfies(h -> assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo("15730"));
    }

    @Test
    @DisplayName("R-033: a las 14:00 con la API en 1100 y vigente 1000, editar un USD sale en 15.730; despues de «Recalcular ahora», en 17.310")
    void edicionesUsanElVigente() throws Exception {
        ObjectNode p = pedido(unico("R033"), "USD");
        JsonNode creado = crear(duenoA, p);
        long id = creado.get("id").asLong();
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");

        p.put("descripcion", "Editado a las 14:00");
        assertThat(cuerpo(editar(duenoA, id, p).andExpect(status().isOk())).get("precioFinal").decimalValue())
                .isEqualByComparingTo("15730");

        postJson("/api/cotizacion/recalculo", duenoA, "{}").andExpect(status().isOk());
        assertThat(precioDe(id)).isEqualByComparingTo("17310");

        p.put("descripcion", "Editado despues del recalculo");
        assertThat(cuerpo(editar(duenoA, id, p).andExpect(status().isOk())).get("precioFinal").decimalValue())
                .isEqualByComparingTo("17310");
    }

    @Test
    @DisplayName("un PUT sin moneda conserva la del articulo: uno en USD sigue en USD con costo 10 y precio 15.730; uno en pesos sigue en pesos")
    void editarSinMonedaConservaLaActual() throws Exception {
        ObjectNode usd = pedido(unico("SINMON"), "USD");
        long idUsd = crear(duenoA, usd).get("id").asLong();
        usd.remove("moneda");
        usd.put("descripcion", "Editado sin moneda");

        JsonNode editadoUsd = cuerpo(editar(duenoA, idUsd, usd).andExpect(status().isOk()));

        assertThat(editadoUsd.get("moneda").asText()).isEqualTo("USD");
        assertThat(editadoUsd.get("costo").decimalValue()).isEqualByComparingTo("10");
        assertThat(editadoUsd.get("precioFinal").decimalValue()).isEqualByComparingTo("15730");
        assertThat(monedaGuardada(idUsd)).isEqualTo("USD");
        assertThat(precioDe(idUsd)).isEqualByComparingTo("15730");

        ObjectNode pesos = pedido(unico("SINMONARS"), null);
        long idPesos = crear(duenoA, pesos).get("id").asLong();
        pesos.put("descripcion", "Editado sin moneda");

        JsonNode editadoPesos = cuerpo(editar(duenoA, idPesos, pesos).andExpect(status().isOk()));

        assertThat(editadoPesos.get("moneda").asText()).isEqualTo("ARS");
        assertThat(editadoPesos.get("precioFinal").decimalValue()).isEqualByComparingTo("20");
        assertThat(monedaGuardada(idPesos)).isEqualTo("ARS");
    }

    // ---- T-037: aislamiento ----

    @Test
    @DisplayName("aislamiento: el Dueno de A pide pasar a USD un articulo de B y recibe 404; B no cambia")
    void unDuenoNoCambiaLaMonedaDeOtraFerreteria() throws Exception {
        Long deB = articulo(b, unico("DEB"), "ARS", "100.00", "170.00", true);
        ObjectNode p = pedido(jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, deB),
                "USD");

        editar(duenoA, deB, p).andExpect(status().isNotFound());

        assertThat(monedaGuardada(deB)).isEqualTo("ARS");
        assertThat(precioDe(deB)).isEqualByComparingTo("170");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, deB))
                .isEqualByComparingTo("100");
        assertThat(historialDe(deB)).isEmpty();
    }

    @Test
    @DisplayName("aislamiento: A con vigente 1000 y B con 950 editan un articulo de 10 USD y obtienen 15.730 y 14.950")
    void cadaFerreteriaCalculaConSuVigente() throws Exception {
        long deA = crear(duenoA, pedido(unico("VIGA"), "USD")).get("id").asLong();
        long deB = crear(duenoB, pedido(unico("VIGB"), "USD")).get("id").asLong();

        assertThat(precioDe(deA)).isEqualByComparingTo("15730");
        assertThat(precioDe(deB)).isEqualByComparingTo("14950");

        ObjectNode editA = pedido(jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, deA),
                "USD").put("descripcion", "Editado A");
        ObjectNode editB = pedido(jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, deB),
                "USD").put("descripcion", "Editado B");
        assertThat(cuerpo(editar(duenoA, deA, editA).andExpect(status().isOk())).get("precioFinal").decimalValue())
                .isEqualByComparingTo("15730");
        assertThat(cuerpo(editar(duenoB, deB, editB).andExpect(status().isOk())).get("precioFinal").decimalValue())
                .isEqualByComparingTo("14950");
    }

    @Test
    @DisplayName("aislamiento: un ferreteriaId en el body del alta se ignora; el articulo queda en la ferreteria del token")
    void laFerreteriaDelBodySeIgnora() throws Exception {
        ObjectNode p = pedido(unico("FERR"), "USD");
        p.put("ferreteriaId", b);

        long id = crear(duenoA, p).get("id").asLong();

        assertThat(jdbc.queryForObject("select ferreteria_id from articulo where id = ?", Long.class, id)).isEqualTo(a);
        assertThat(precioDe(id)).isEqualByComparingTo("15730");
    }
}
