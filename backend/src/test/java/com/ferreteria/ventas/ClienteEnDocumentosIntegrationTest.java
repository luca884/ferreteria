package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CL-004: la venta, el presupuesto y el remito con {@code clienteId}, contra MySQL real. El documento guarda el id y una
 * copia de la razón social; sin cliente vale el texto libre (presupuesto y remito) o Consumidor Final.
 */
class ClienteEnDocumentosIntegrationTest extends VentasTestBase {

    private static final String VENTAS = "/api/ventas";
    private static final String PRESUPUESTOS = "/api/presupuestos";
    private static final String REMITOS = "/api/remitos";

    private long cliente(String token, String razonSocial) throws Exception {
        return json(postJson("/api/clientes", token, "{\"razonSocial\": \"" + razonSocial + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    /** El cuerpo de un documento de un renglón de 2 u a $ 100; {@code extra} suma clienteId, clienteNombre, etc. */
    private Map<String, Object> cuerpo(String clave, long articulo, Map<String, Object> extra) {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articulo);
        renglon.put("cantidad", new BigDecimal("2"));
        renglon.put("precioUnitario", new BigDecimal("100.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave);
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal("200.00"));
        cuerpo.putAll(extra);
        return cuerpo;
    }

    private ResultActions emitir(String url, String token, long articulo, String clave, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> cuerpo = cuerpo(clave, articulo, extra);
        if (VENTAS.equals(url)) {
            cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        }
        return postJson(url, token, objectMapper.writeValueAsString(cuerpo));
    }

    private static Map<String, Object> conCliente(Long clienteId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clienteId", clienteId);
        return m;
    }

    private Map<String, Object> fila(String tabla, long id) {
        return jdbc.queryForMap("select cliente_id, cliente_nombre from " + tabla + " where id = ?", id);
    }

    private long idDe(JsonNode respuesta, String campo) {
        return respuesta.get(campo).asLong();
    }

    // ------------------------------------------------------------------ venta

    @Test
    @DisplayName("venta con cliente: la respuesta y el comprobante llevan su id y la copia de su razón social")
    void ventaConCliente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long c = cliente(token, "Ferretería Norte SRL");

        JsonNode venta = json(emitir(VENTAS, token, a, clave(), conCliente(c)).andExpect(status().isCreated()));

        assertThat(venta.get("clienteId").asLong()).isEqualTo(c);
        assertThat(venta.get("clienteNombre").asText()).isEqualTo("Ferretería Norte SRL");
        Map<String, Object> f = fila("comprobante", idDe(venta, "ventaId"));
        assertThat(((Number) f.get("cliente_id")).longValue()).isEqualTo(c);
        assertThat(f.get("cliente_nombre")).isEqualTo("Ferretería Norte SRL");
    }

    @Test
    @DisplayName("venta sin cliente es Consumidor Final: no trae clienteId ni nombre y guarda nulo")
    void ventaSinCliente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(emitir(VENTAS, login(t.emailVendedor()), a, clave(), Map.of())
                .andExpect(status().isCreated()));

        assertThat(venta.has("clienteId")).isFalse();
        assertThat(venta.has("clienteNombre")).isFalse();
        Map<String, Object> f = fila("comprobante", idDe(venta, "ventaId"));
        assertThat(f.get("cliente_id")).isNull();
        assertThat(f.get("cliente_nombre")).isNull();
    }

    @Test
    @DisplayName("renombrar al cliente después no cambia la copia del nombre de la venta ya hecha")
    void renombrarNoCambiaLaVenta() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long c = cliente(token, "Nombre Viejo");
        long venta = idDe(json(emitir(VENTAS, token, a, clave(), conCliente(c)).andExpect(status().isCreated())),
                "ventaId");

        putJson("/api/clientes/" + c, token, "{\"razonSocial\": \"Nombre Nuevo\"}").andExpect(status().isOk());

        assertThat(fila("comprobante", venta).get("cliente_nombre")).isEqualTo("Nombre Viejo");
        assertThat(getJson("/api/clientes/" + c, token).get("razonSocial").asText()).isEqualTo("Nombre Nuevo");
    }

    @Test
    @DisplayName("repetir la venta con otro cliente, o con y sin cliente, es CLAVE_CON_OTRO_CONTENIDO; igual es la misma")
    void ventaRepetida() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long uno = cliente(token, "Uno");
        long otro = cliente(token, "Otro");
        String clave = clave();
        emitir(VENTAS, token, a, clave, conCliente(uno)).andExpect(status().isCreated());

        emitir(VENTAS, token, a, clave, conCliente(uno)).andExpect(status().isOk());
        for (Map<String, Object> distinto : List.of(conCliente(otro), Map.<String, Object>of())) {
            JsonNode rechazo = json(emitir(VENTAS, token, a, clave, distinto).andExpect(status().is(422)));
            assertThat(rechazo.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        }
        assertThat(comprobantes(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("venta con un cliente inexistente o de otra ferretería es 404, y no cobra ni mueve el stock")
    void ventaConClienteAjeno() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long ajeno = cliente(login(otra.emailDueno()), "De otra ferretería");
        String token = login(t.emailVendedor());

        emitir(VENTAS, token, a, clave(), conCliente(ajeno)).andExpect(status().isNotFound());
        emitir(VENTAS, token, a, clave(), conCliente(999_999_999L)).andExpect(status().isNotFound());

        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("venta con un cliente desactivado es 422")
    void ventaConClienteDesactivado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long c = cliente(login(t.emailVendedor()), "Viejo");
        jdbc.update("update cliente set activo = false where id = ?", c);

        JsonNode rechazo = json(emitir(VENTAS, login(t.emailVendedor()), a, clave(), conCliente(c))
                .andExpect(status().is(422)));

        assertThat(rechazo.get("message").asText()).isEqualTo("El cliente está desactivado.");
        assertThat(comprobantes(t)).isZero();
    }

    // ---------------------------------------------------- presupuesto y remito

    private List<String> documentos() {
        return new ArrayList<>(List.of("presupuesto", "remito"));
    }

    private String url(String documento) {
        return "presupuesto".equals(documento) ? PRESUPUESTOS : REMITOS;
    }

    private String campoId(String documento) {
        return "presupuesto".equals(documento) ? "presupuestoId" : "remitoId";
    }

    @Test
    @DisplayName("presupuesto y remito con cliente: llevan su id y la copia de su razón social, y ganan al texto libre")
    void documentosConCliente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        long c = cliente(token, "Ferretería Norte SRL");

        for (String documento : documentos()) {
            Map<String, Object> extra = conCliente(c);
            extra.put("clienteNombre", "escrito a mano");
            JsonNode emitido = json(emitir(url(documento), token, a, clave(), extra).andExpect(status().isCreated()));

            assertThat(emitido.get("clienteId").asLong()).as(documento).isEqualTo(c);
            assertThat(emitido.get("clienteNombre").asText()).as(documento).isEqualTo("Ferretería Norte SRL");
            Map<String, Object> f = fila(documento, idDe(emitido, campoId(documento)));
            assertThat(((Number) f.get("cliente_id")).longValue()).as(documento).isEqualTo(c);
            assertThat(f.get("cliente_nombre")).as(documento).isEqualTo("Ferretería Norte SRL");
        }
    }

    @Test
    @DisplayName("presupuesto y remito sin clienteId siguen guardando el texto libre, sin cliente")
    void documentosConTextoLibre() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());

        for (String documento : documentos()) {
            JsonNode emitido = json(emitir(url(documento), token, a, clave(),
                    Map.of("clienteNombre", "Juan, el del taller")).andExpect(status().isCreated()));

            assertThat(emitido.has("clienteId")).as(documento).isFalse();
            assertThat(emitido.get("clienteNombre").asText()).as(documento).isEqualTo("Juan, el del taller");
            Map<String, Object> f = fila(documento, idDe(emitido, campoId(documento)));
            assertThat(f.get("cliente_id")).as(documento).isNull();
            assertThat(f.get("cliente_nombre")).as(documento).isEqualTo("Juan, el del taller");
        }
    }

    @Test
    @DisplayName("una razón social de 120 caracteres entra entera en la copia del presupuesto y del remito")
    void razonSocialLargaEntra() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        String larga = "R".repeat(120);
        long c = cliente(token, larga);

        for (String documento : documentos()) {
            JsonNode emitido = json(emitir(url(documento), token, a, clave(), conCliente(c))
                    .andExpect(status().isCreated()));

            assertThat(emitido.get("clienteNombre").asText()).as(documento).isEqualTo(larga);
            assertThat(fila(documento, idDe(emitido, campoId(documento))).get("cliente_nombre")).as(documento)
                    .isEqualTo(larga);
        }
    }

    @Test
    @DisplayName("presupuesto y remito con un cliente ajeno (404) o desactivado (422) no escriben nada")
    void documentosConClienteInvalido() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        long ajeno = cliente(login(otra.emailDueno()), "De otra");
        long viejo = cliente(token, "Viejo");
        jdbc.update("update cliente set activo = false where id = ?", viejo);

        for (String documento : documentos()) {
            emitir(url(documento), token, a, clave(), conCliente(ajeno)).andExpect(status().isNotFound());
            emitir(url(documento), token, a, clave(), conCliente(viejo)).andExpect(status().is(422));
            assertThat(jdbc.queryForObject("select count(*) from " + documento + " where ferreteria_id = ?", Long.class,
                    t.id())).as(documento).isZero();
        }
        assertThat(stock(a)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("repetir un presupuesto o un remito con otro cliente es CLAVE_CON_OTRO_CONTENIDO; igual es el mismo (200)")
    void documentosRepetidos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        long uno = cliente(token, "Uno");
        long otro = cliente(token, "Otro");

        for (String documento : documentos()) {
            String clave = clave();
            emitir(url(documento), token, a, clave, conCliente(uno)).andExpect(status().isCreated());

            emitir(url(documento), token, a, clave, conCliente(uno)).andExpect(status().isOk());
            JsonNode rechazo = json(emitir(url(documento), token, a, clave, conCliente(otro)).andExpect(status().is(422)));
            assertThat(rechazo.get("motivo").asText()).as(documento).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        }
    }

    @Test
    @DisplayName("el detalle y la lista del presupuesto y del remito traen clienteId y clienteNombre")
    void consultaTraeElCliente() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        long c = cliente(token, "Ferretería Norte SRL");

        for (String documento : documentos()) {
            long id = idDe(json(emitir(url(documento), token, a, clave(), conCliente(c)).andExpect(status().isCreated())),
                    campoId(documento));

            JsonNode detalle = getJson(url(documento) + "/" + id, token);
            JsonNode lista = getJson(url(documento), token).get("items").get(0);

            assertThat(detalle.get("clienteId").asLong()).as(documento).isEqualTo(c);
            assertThat(detalle.get("clienteNombre").asText()).as(documento).isEqualTo("Ferretería Norte SRL");
            assertThat(lista.get("clienteNombre").asText()).as(documento).isEqualTo("Ferretería Norte SRL");
        }
    }
}
