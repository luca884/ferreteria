package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.catalogo.model.Oferta;
import com.ferreteria.catalogo.model.TipoOferta;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regresiones del verificador (ofertas): el filtro {@code enOferta} da lo mismo que {@link Oferta#precioSobre}, un PUT
 * rechazado no cambia la fila, otra ferreteria no ve nada y sin oferta las respuestas solo suman campos nulos.
 */
class OfertaRegresionIntegrationTest extends IntegrationTestBase {

    private record Caso(long id, BigDecimal precio, TipoOferta tipo, BigDecimal valor) {}

    private long ferreteriaDe(String token) throws Exception {
        String codigo = unico("PRB");
        String pedido = objectMapper.createObjectNode().put("codigoInterno", codigo).put("descripcion", "probe base")
                .put("unidad", "UNIDAD").put("costo", new BigDecimal("100")).put("margenPorcentaje", new BigDecimal("30"))
                .put("precioManual", true).put("precioFinal", new BigDecimal("1000")).toString();
        JsonNode r = objectMapper.readTree(postJson("/api/articulos", token, pedido).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return jdbc.queryForObject("select ferreteria_id from articulo where id = ?", Long.class, r.get("id").asLong());
    }

    private long insertar(long fid, String tag, BigDecimal precio, TipoOferta tipo, BigDecimal valor) {
        String codigo = "PR-" + UUID.randomUUID().toString().substring(0, 10);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, "
                + "unidad, stock_actual, stock_minimo, activo, oferta_tipo, oferta_valor) values (?, ?, ?, 1.00, ?, "
                + "'IVA_21', 'UNIDAD', 10, 0, 1, ?, ?)", fid, codigo, tag + " " + codigo, precio, tipo.name(), valor);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                fid, codigo);
    }

    private Set<Long> filtrados(String token, String tag) throws Exception {
        Set<Long> ids = new HashSet<>();
        int page = 0;
        while (true) {
            JsonNode p = getJson("/api/articulos?enOferta=true&size=100&q=" + tag + "&page=" + page, token);
            p.get("items").forEach(a -> ids.add(a.get("id").asLong()));
            if (page + 1 >= p.get("totalPages").asInt()) {
                return ids;
            }
            page++;
        }
    }

    @Test
    void filtroJpqlCoincideConOfertaPrecioSobre() throws Exception {
        String dueno = login("dueno@tornillo.demo");
        long fid = ferreteriaDe(dueno);
        String tag = "PRBF" + UUID.randomUUID().toString().substring(0, 6);
        List<Caso> casos = new ArrayList<>();
        String[] precios = {"0.00", "0.01", "0.02", "0.03", "0.99", "1.00", "1.01", "3.33", "9.99", "10.00",
            "13552.00", "99999.99", "1234567.89"};
        String[] porcentajes = {"0.01", "0.50", "1.00", "33.33", "50.00", "66.67", "99.99", "0.99", "49.99"};
        String[] montos = {"0.01", "0.99", "1.00", "9.99", "10.00", "13551.99", "13552.00"};
        for (String p : precios) {
            for (String v : porcentajes) {
                casos.add(caso(fid, tag, p, TipoOferta.DESCUENTO_PORCENTAJE, v));
            }
            for (String v : montos) {
                casos.add(caso(fid, tag, p, TipoOferta.DESCUENTO_MONTO, v));
                casos.add(caso(fid, tag, p, TipoOferta.PRECIO_FIJO, v));
            }
        }
        Random rnd = new Random(44);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = BigDecimal.valueOf(rnd.nextInt(5_000_00) + (rnd.nextBoolean() ? 0 : 1), 2);
            BigDecimal v = BigDecimal.valueOf(1 + rnd.nextInt(9999), 2);
            TipoOferta t = TipoOferta.values()[rnd.nextInt(3)];
            if (t != TipoOferta.DESCUENTO_PORCENTAJE) {
                v = BigDecimal.valueOf(1 + rnd.nextInt(5_000_00), 2);
            }
            casos.add(new Caso(insertar(fid, tag, p, t, v), p, t, v));
        }

        Set<Long> enFiltro = filtrados(dueno, tag);

        List<String> diferencias = new ArrayList<>();
        for (Caso c : casos) {
            boolean dominio = new Oferta(c.tipo(), c.valor(), null).precioSobre(c.precio()).isPresent();
            if (dominio != enFiltro.contains(c.id())) {
                diferencias.add(c + " dominio=" + dominio + " filtro=" + enFiltro.contains(c.id()));
            }
        }
        assertThat(diferencias).isEmpty();
    }

    private Caso caso(long fid, String tag, String p, TipoOferta t, String v) {
        BigDecimal precio = new BigDecimal(p);
        BigDecimal valor = new BigDecimal(v);
        return new Caso(insertar(fid, tag, precio, t, valor), precio, t, valor);
    }

    @Test
    void putRechazadoNoTocaLaOfertaNiLaVersionGuardadas() throws Exception {
        String dueno = login("dueno@tornillo.demo");
        long fid = ferreteriaDe(dueno);
        long id = insertar(fid, "PRBR", new BigDecimal("1000.00"), TipoOferta.PRECIO_FIJO, new BigDecimal("800.00"));
        Map<String, Object> antes = jdbc.queryForMap("select * from articulo where id = ?", id);
        String[] invalidos = {
            "{\"tipo\":\"PRECIO_FIJO\",\"valor\":1000}",
            "{\"tipo\":\"PRECIO_FIJO\",\"valor\":800.001}",
            "{\"tipo\":\"DESCUENTO_PORCENTAJE\",\"valor\":100}",
            "{\"tipo\":\"DESCUENTO_MONTO\",\"valor\":1000}",
            "{\"tipo\":\"PRECIO_FIJO\",\"valor\":-1}",
            "{\"tipo\":\"PRECIO_FIJO\",\"valor\":0}",
            "{\"tipo\":\"PRECIO_FIJO\",\"valor\":700,\"hasta\":\"" + HoraArgentina.hoy().minusDays(1) + "\"}",
            "{\"tipo\":\"REGALO\",\"valor\":700}",
            "{\"valor\":700}",
        };
        List<String> estados = new ArrayList<>();
        for (String json : invalidos) {
            int s = putJson("/api/articulos/" + id + "/oferta", dueno, conLaVersionVigente(id, json))
                    .andReturn().getResponse().getStatus();
            estados.add(s + " " + json);
        }
        assertThat(estados).allMatch(e -> e.startsWith("400"));
        assertThat(jdbc.queryForMap("select * from articulo where id = ?", id)).isEqualTo(antes);
    }

    @Test
    void otraFerreteriaNoVeLaOferta() throws Exception {
        String duenoA = login("dueno@tornillo.demo");
        String duenoB = login("dueno@tuerca.demo");
        long fidA = ferreteriaDe(duenoA);
        long id = insertar(fidA, "PRBX", new BigDecimal("1000.00"), TipoOferta.PRECIO_FIJO, new BigDecimal("800.00"));
        // mostrador y ficha de B no lo ven
        int fichaB = getResult("/api/articulos/" + id, duenoB).andReturn().getResponse().getStatus();
        JsonNode mostradorB = getJson("/api/articulos/mostrador?q=PRBX", duenoB);
        JsonNode filtroB = getJson("/api/articulos?enOferta=true&size=100&q=PRBX", duenoB);
        JsonNode filtroBIncl = getJson("/api/articulos?enOferta=true&incluirInactivos=true&size=100", duenoB);
        boolean fugaB = false;
        for (JsonNode a : filtroBIncl.get("items")) {
            Long f = jdbc.queryForObject("select ferreteria_id from articulo where id = ?", Long.class, a.get("id").asLong());
            if (f == fidA) {
                fugaB = true;
            }
        }
        assertThat(fichaB).isEqualTo(404);
        assertThat(mostradorB.get("items").size()).isZero();
        assertThat(filtroB.get("items").size()).isZero();
        assertThat(fugaB).isFalse();
    }

    @Test
    void sinOfertaLasRespuestasSoloSumanCamposNulos() throws Exception {
        String dueno = login("dueno@tornillo.demo");
        String vendedor = login("vendedor@tornillo.demo");
        long fid = ferreteriaDe(dueno);
        long id = insertar(fid, "PRBN", new BigDecimal("1000.00"), TipoOferta.PRECIO_FIJO, new BigDecimal("800.00"));
        jdbc.update("update articulo set oferta_tipo = null, oferta_valor = null where id = ?", id);
        JsonNode d = getJson("/api/articulos/" + id, dueno);
        JsonNode v = getJson("/api/articulos/" + id, vendedor);
        String codigo = jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, id);
        JsonNode m = getJson("/api/articulos/mostrador?q=" + codigo, vendedor).get("items").get(0);
        assertThat(d.get("oferta").isNull()).isTrue();
        assertThat(v.get("oferta").isNull()).isTrue();
        assertThat(m.get("precioFinal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(m.get("enOferta").asBoolean()).isFalse();
        assertThat(m.get("precioNormal").isNull()).isTrue();
    }
}
