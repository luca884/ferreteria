package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.documento.LectorQr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El cerrojo de la apariencia de la factura por HTTP (cu-07-factura-mostrador S5, S6, S7, S12, S13, S14, S15 y C1 a C3),
 * contra MySQL real y el simulador: los 3 ambientes por los 8 estados al repetir el cobro, en el detalle y en el listado;
 * la copia congelada del registro; la A, la B con DNI y la C con su QR; {@code discriminaIva} (el cuerpo de la A, también
 * en su X); lo que anticipa el cobro para cada condición de la ferretería; roles, aislamiento y los bordes de
 * {@code numeroFiscal}. Los estados que el simulador no produce se fuerzan por SQL sobre el registro de la venta.
 */
class VentaFacturadaCerrojoIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";
    private static final String MENSAJE_NUMERO_FISCAL = "Escribí el número de la factura como 12 o 00001-00000012.";
    private static final String LEYENDA_LEY_27618 = "El crédito fiscal discriminado en el presente comprobante, sólo "
            + "podrá ser computado a efectos del Régimen de Sostenimiento e Inclusión Fiscal para Pequeños Contribuyentes "
            + "de la Ley Nº 27.618";
    /** Los siete campos fiscales que el X manda en null (S6). */
    private static final List<String> NULOS_DEL_X = List.of("codigo", "puntoVenta", "numero", "cae", "caeVencimiento",
            "qr", "qrModulos");
    /** Los campos del JSON v1 del QR, en el orden de la especificación. */
    private static final List<String> CAMPOS_QR = List.of("ver", "fecha", "cuit", "ptoVta", "tipoCmp", "nroCmp",
            "importe", "moneda", "ctz", "tipoDocRec", "nroDocRec", "tipoCodAut", "codAut");

    // ---- S6 y S12: el cerrojo, por HTTP ----

    static Stream<Arguments> ambientesYEstados() {
        List<Arguments> casos = new ArrayList<>();
        for (String ambiente : List.of("SIMULADOR", "HOMOLOGACION", "PRODUCCION")) {
            for (String estado : List.of("PENDIENTE", "EN_CURSO", "EN_VERIFICACION", "AUTORIZADO", "RECHAZADO",
                    "FUERA_DE_FECHA", "CAE_NO_COINCIDE", "SERIE_DESINCRONIZADA")) {
                casos.add(Arguments.of(ambiente, estado));
            }
        }
        return casos.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("ambientesYEstados")
    @DisplayName("solo PRODUCCION AUTORIZADO es la B con su QR; lo demás es X con la marca de S12 y sin nada fiscal, al repetir el cobro, en el detalle y en el listado")
    void elCerrojoPorHttp(String ambiente, String estado) throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        String vendedor = login(t.emailVendedor());
        long venta = json(postJson("/api/ventas", vendedor, cuerpo).andExpect(status().isCreated())).get("ventaId")
                .asLong();
        String cae = (String) registroDe(venta).get("cae");
        llevarA(venta, ambiente, estado, cae);
        String dueno = login(t.emailDueno());

        String repetida = postJson("/api/ventas", vendedor, cuerpo).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String detalle = getResult("/api/ventas/" + venta, dueno).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode fila = getJson("/api/ventas", dueno).get("items").get(0);
        boolean valido = ambiente.equals("PRODUCCION") && estado.equals("AUTORIZADO");

        for (String crudo : List.of(repetida, detalle)) {
            JsonNode fiscal = objectMapper.readTree(crudo).get("fiscal");
            assertThat(fiscal.get("estado").asText()).isEqualTo(estado);
            assertThat(fiscal.get("ambiente").asText()).isEqualTo(ambiente);
            JsonNode d = fiscal.get("documento");
            assertThat(d.get("validoComoFactura").asBoolean()).isEqualTo(valido);
            assertDiscriminaIva(d, false);
            if (valido) {
                assertThat(d.get("letra").asText()).isEqualTo("B");
                assertThat(d.get("codigo").asInt()).isEqualTo(6);
                assertThat(d.get("marca").isNull()).isTrue();
                assertThat(d.get("cae").asText()).isEqualTo(cae);
                List<String> modulos = new ArrayList<>();
                d.get("qrModulos").forEach(m -> modulos.add(m.asText()));
                assertThat(LectorQr.leer(modulos).texto()).isEqualTo(d.get("qr").asText());
            } else {
                assertThat(d.get("letra").asText()).isEqualTo("X");
                assertThat(d.get("marca").asText()).isEqualTo(marcaEsperada(ambiente, estado));
                for (String nulo : NULOS_DEL_X) {
                    assertThat(d.has(nulo)).as("documento.%s presente", nulo).isTrue();
                    assertThat(d.get(nulo).isNull()).as("documento.%s en null", nulo).isTrue();
                }
                // El documento crudo no trae nada que lo haga pasar por una factura.
                String documento = d.toString();
                if (cae != null) {
                    assertThat(documento).doesNotContain(cae);
                }
                assertThat(documento).doesNotContain("arca.gob.ar").doesNotContain("Comprobante Autorizado")
                        .doesNotContain("\"letra\":\"B\"");
            }
        }
        JsonNode resumen = fila.get("fiscal");
        assertThat(resumen.get("letra").asText()).isEqualTo(valido ? "B" : "X");
        assertThat(resumen.get("letraFiscal").asText()).isEqualTo("B");
        assertThat(resumen.get("validoComoFactura").asBoolean()).isEqualTo(valido);
        assertThat(resumen.get("estado").asText()).isEqualTo(estado);
        assertThat(resumen.get("ambiente").asText()).isEqualTo(ambiente);
    }

    // ---- S5, S7, S13 y S14: la A, la B con DNI y la C, con la copia del registro ----

    @Test
    @DisplayName("Factura A con centavos: el X del simulador ya discrimina el IVA; en producción, la A con su QR; y la copia no cambia aunque cambien el cliente y CU-01")
    void laFacturaAConSuQrYLaCopiaCongelada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Tornillo", "UNIDAD", "123.45", "10");
        String cuit = cuitDeCliente();
        long c = cliente(t, "Corralón Sur SA", RI, "CUIT", cuit, "Av. Colón 2000");
        String cuerpo = cuerpoDe(t, c, null, clave(), "370.35", linea(a, "3", "123.45"));
        String vendedor = login(t.emailVendedor());
        JsonNode nueva = json(postJson("/api/ventas", vendedor, cuerpo).andExpect(status().isCreated()));
        long venta = nueva.get("ventaId").asLong();

        // En el simulador es un X, con el cuerpo de la A (renglones sin IVA y el desglose).
        JsonNode x = nueva.get("fiscal").get("documento");
        assertThat(nueva.get("fiscal").get("letra").asText()).isEqualTo("A");
        assertThat(x.get("validoComoFactura").asBoolean()).isFalse();
        assertThat(x.get("letra").asText()).isEqualTo("X");
        assertDiscriminaIva(x, true);
        assertThat(x.get("alicuotas")).hasSize(1);
        assertThat(x.get("codigo").isNull()).isTrue();
        assertThat(x.get("qr").isNull()).isTrue();

        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?", venta);
        String dueno = login(t.emailDueno());
        JsonNode antes = getJson("/api/ventas/" + venta, dueno).get("fiscal").get("documento");

        assertThat(antes.get("validoComoFactura").asBoolean()).isTrue();
        assertThat(antes.get("letra").asText()).isEqualTo("A");
        assertDiscriminaIva(antes, true);
        assertThat(antes.get("codigo").asInt()).isEqualTo(1);
        assertThat(antes.get("receptor").get("tipoDocumento").asText()).isEqualTo("CUIT");
        assertThat(antes.get("receptor").get("numeroDocumento").asText()).isEqualTo(cuit);
        assertThat(antes.get("receptor").get("condicionIva").asText()).isEqualTo("IVA RESPONSABLE INSCRIPTO");
        assertThat(antes.get("receptor").get("aConsumidorFinal").asBoolean()).isFalse();
        assertThat(antes.get("transparencia").isNull()).isTrue();
        assertThat(antes.get("leyendaMonotributo").isNull()).isTrue();
        JsonNode qr = LectorQr.datos(antes.get("qr").asText());
        List<String> campos = new ArrayList<>();
        qr.fieldNames().forEachRemaining(campos::add);
        assertThat(campos).containsExactlyElementsOf(CAMPOS_QR);
        String emisorCuit = jdbc.queryForObject("select replace(emisor_cuit, '-', '') from comprobante_fiscal where "
                + "comprobante_id = ?", String.class, venta);
        assertThat(qr.get("cuit").asLong()).isEqualTo(Long.parseLong(emisorCuit));
        assertThat(qr.get("tipoCmp").asInt()).isEqualTo(1);
        assertThat(LectorQr.json(antes.get("qr").asText())).contains("\"importe\":370.35,");
        assertThat(qr.get("tipoDocRec").asInt()).isEqualTo(80);
        assertThat(qr.get("nroDocRec").asLong()).isEqualTo(Long.parseLong(cuit));
        assertThat(qr.get("codAut").asText()).isEqualTo(antes.get("cae").asText());

        // El cliente y CU-01 cambian después de vender: la hoja no.
        jdbc.update("update cliente set razon_social = 'Otro Nombre SA', condicion_iva = 'MONOTRIBUTISTA', "
                + "numero_documento = ?, domicilio = 'Otra 99' where id = ?", cuitDeCliente(), c);
        jdbc.update("update ferreteria set razon_social = 'Cambiada SA', domicilio_comercial = 'Calle Nueva 1', "
                + "condicion_iva = 'MONOTRIBUTISTA', ingresos_brutos = '000', inicio_actividades = '2020-01-01' "
                + "where id = ?", t.id());
        JsonNode despues = getJson("/api/ventas/" + venta, dueno).get("fiscal").get("documento");
        JsonNode repetida = json(postJson("/api/ventas", vendedor, cuerpo).andExpect(status().isOk()))
                .get("fiscal").get("documento");
        assertThat(despues).isEqualTo(antes);
        assertThat(repetida).isEqualTo(antes);
    }

    @Test
    @DisplayName("Factura A a un Monotributista: la leyenda de la Ley 27.618, textual, en el X del simulador (que discrimina el IVA) y en la A de producción")
    void laFacturaAAUnMonotributistaLlevaLaLeyenda() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Tornillo", "UNIDAD", "121.00", "10");
        long c = cliente(t, "Pinturería Norte", "MONOTRIBUTISTA", "CUIT", cuitDeCliente(), "Av. Constitución 500");
        JsonNode nueva = json(venderA(t, c, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        long venta = nueva.get("ventaId").asLong();

        JsonNode x = nueva.get("fiscal").get("documento");
        assertThat(x.get("letra").asText()).isEqualTo("X");
        assertDiscriminaIva(x, true);
        assertThat(x.get("leyendaMonotributo").asText()).isEqualTo(LEYENDA_LEY_27618);

        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?", venta);
        JsonNode d = getJson("/api/ventas/" + venta, login(t.emailDueno())).get("fiscal").get("documento");
        assertThat(d.get("letra").asText()).isEqualTo("A");
        assertDiscriminaIva(d, true);
        assertThat(d.get("receptor").get("condicionIva").asText()).isEqualTo("RESPONSABLE MONOTRIBUTO");
        assertThat(d.get("leyendaMonotributo").asText()).isEqualTo(LEYENDA_LEY_27618);
    }

    @Test
    @DisplayName("Factura B a Consumidor Final con DNI (96 en el QR) y Factura C sin identificar (99 y 0): no discriminan el IVA, y el importe va con 2 decimales")
    void laFacturaBConDniYLaC() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Tornillo", "UNIDAD", "100.50", "10");
        long c = cliente(t, "Juan Pérez", "CONSUMIDOR_FINAL", "DNI", "30123456", null);
        long venta = json(venderA(t, c, null, clave(), "201.00", linea(a, "2", "100.50"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?", venta);
        JsonNode b = getJson("/api/ventas/" + venta, login(t.emailDueno())).get("fiscal").get("documento");

        assertThat(b.get("letra").asText()).isEqualTo("B");
        assertDiscriminaIva(b, false);
        assertThat(b.get("receptor").get("tipoDocumento").asText()).isEqualTo("DNI");
        assertThat(b.get("receptor").get("aConsumidorFinal").asBoolean()).isTrue();
        assertThat(b.get("transparencia").get("ivaContenido").decimalValue())
                .isEqualByComparingTo(b.get("iva").decimalValue());
        JsonNode qrB = LectorQr.datos(b.get("qr").asText());
        assertThat(qrB.get("tipoDocRec").asInt()).isEqualTo(96);
        assertThat(qrB.get("nroDocRec").asLong()).isEqualTo(30123456L);
        assertThat(LectorQr.json(b.get("qr").asText())).contains("\"importe\":201.00,");

        Tienda m = tiendaQueFactura("MONOTRIBUTISTA");
        long clavo = articulo(m, "Clavo", "UNIDAD", "50.25", "10");
        long ventaC = json(venderA(m, null, null, clave(), "50.25", linea(clavo, "1", "50.25"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        jdbc.update("update comprobante_fiscal set ambiente = 'PRODUCCION' where comprobante_id = ?", ventaC);
        JsonNode dc = getJson("/api/ventas/" + ventaC, login(m.emailDueno())).get("fiscal").get("documento");

        assertThat(dc.get("letra").asText()).isEqualTo("C");
        assertDiscriminaIva(dc, false);
        assertThat(dc.get("codigo").asInt()).isEqualTo(11);
        assertThat(dc.get("alicuotas")).isEmpty();
        assertThat(dc.get("iva").decimalValue()).isEqualByComparingTo("0");
        assertThat(dc.get("transparencia").isNull()).isTrue();
        assertThat(dc.get("emisor").get("condicionIva").asText()).isEqualTo("RESPONSABLE MONOTRIBUTO");
        JsonNode qrC = LectorQr.datos(dc.get("qr").asText());
        assertThat(qrC.get("tipoCmp").asInt()).isEqualTo(11);
        assertThat(qrC.get("tipoDocRec").asInt()).isEqualTo(99);
        assertThat(qrC.get("nroDocRec").asLong()).isZero();
        assertThat(LectorQr.json(dc.get("qr").asText())).contains("\"importe\":50.25,");
    }

    // ---- C3: lo que anticipa el cobro ----

    @ParameterizedTest(name = "ferretería {0}")
    @ValueSource(strings = {"MONOTRIBUTISTA", "EXENTO"})
    @DisplayName("GET /api/ventas/facturacion: una ferretería Monotributista o Exenta factura C a cualquier cliente")
    void laFerreteriaMonotributistaOExentaFacturaC(String condicion) throws Exception {
        Tienda t = tiendaQueFactura(condicion);

        JsonNode facturacion = getJson("/api/ventas/facturacion", login(t.emailVendedor()));

        assertThat(facturacion.get("factura").asBoolean()).isTrue();
        assertThat(facturacion.get("ambiente").asText()).isEqualTo("SIMULADOR");
        Map<String, String> letras = new LinkedHashMap<>();
        facturacion.get("letras").fields().forEachRemaining(e -> letras.put(e.getKey(), e.getValue().asText()));
        assertThat(letras).isEqualTo(Map.of("CONSUMIDOR_FINAL", "C", "RESPONSABLE_INSCRIPTO", "C",
                "MONOTRIBUTISTA", "C", "EXENTO", "C"));
    }

    @Test
    @DisplayName("GET /api/ventas/facturacion: con factura, el tope del cobro es el menor entre el del Dueño y el de ARCA (#57)")
    void conFacturaElTopeEsElMenor() throws Exception {
        Tienda arriba = tiendaQueFactura(RI);
        jdbc.update("update ferreteria set tope_consumidor_final = 20000000 where id = ?", arriba.id());
        Tienda abajo = tiendaQueFactura(RI);
        jdbc.update("update ferreteria set tope_consumidor_final = 1000 where id = ?", abajo.id());

        assertThat(getJson("/api/ventas/facturacion", login(arriba.emailVendedor())).get("topeConsumidorFinal")
                .decimalValue()).isEqualByComparingTo("10000000");
        assertThat(getJson("/api/ventas/facturacion", login(abajo.emailVendedor())).get("topeConsumidorFinal")
                .decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("con CU-01 incompleto o la facturación apagada, «factura» es false, y la venta del CU-01 incompleto sale sin «fiscal»")
    void sinFacturacionNoAnticipaNada() throws Exception {
        Tienda incompleta = tiendaQueFactura(RI);
        jdbc.update("update ferreteria set domicilio_comercial = null where id = ?", incompleta.id());
        Tienda apagada = tiendaQueFactura(RI);
        apagarFacturacion(apagada);
        long a = articulo(incompleta, "Clavo", "UNIDAD", "121.00", "10");

        JsonNode venta = json(venderA(incompleta, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));

        for (JsonNode facturacion : List.of(getJson("/api/ventas/facturacion", login(incompleta.emailVendedor())),
                getJson("/api/ventas/facturacion", login(apagada.emailDueno())))) {
            assertThat(facturacion.get("factura").asBoolean()).isFalse();
            assertThat(facturacion.get("ambiente").isNull()).isTrue();
            assertThat(facturacion.get("letras").isNull()).isTrue();
        }
        assertSinFiscal(venta);
    }

    // ---- Roles, aislamiento y S15 ----

    @Test
    @DisplayName("el super admin no tiene ferretería: 403 en /api/ventas/facturacion; el Vendedor no lista ni busca por número fiscal; otra ferretería no ve el detalle (404) ni encuentra el número")
    void rolesYAislamiento() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long venta = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        String vendedor = login(t.emailVendedor());
        Tienda otra = tiendaQueFactura(RI);
        String otroDueno = login(otra.emailDueno());

        String superAdmin = getResult("/api/ventas/facturacion", login("admin@demo.local"))
                .andExpect(status().isForbidden()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(superAdmin).get("message").asText())
                .isEqualTo("Este recurso requiere una ferretería");
        getResult("/api/ventas?numeroFiscal=1", vendedor).andExpect(status().isForbidden());
        getResult("/api/ventas/" + venta, vendedor).andExpect(status().isForbidden());
        getResult("/api/ventas/" + venta, otroDueno).andExpect(status().isNotFound());
        assertThat(getJson("/api/ventas?numeroFiscal=1", otroDueno).get("totalElements").asLong()).isZero();
        assertThat(getJson("/api/ventas?numeroFiscal=1", login(t.emailDueno())).get("totalElements").asLong())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("numeroFiscal en sus bordes: ceros a la izquierda, espacios, punto de venta 0 o el máximo y 8 dígitos sin punto de venta")
    void losBordesDelNumeroFiscal() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        String dueno = login(t.emailDueno());
        Map<String, Long> encontradas = new LinkedHashMap<>();
        encontradas.put("0", 0L);
        encontradas.put("00000-00000001", 0L);
        encontradas.put("00001-00000001", 1L);
        encontradas.put("  00001-00000001 ", 1L);
        encontradas.put("1-0001", 1L);
        encontradas.put("99999-99999999", 0L);
        encontradas.put("12345678", 0L);

        for (Map.Entry<String, Long> caso : encontradas.entrySet()) {
            assertThat(getJson("/api/ventas?numeroFiscal=" + caso.getKey(), dueno).get("totalElements").asLong())
                    .as("«%s»", caso.getKey()).isEqualTo(caso.getValue());
        }
    }

    @ParameterizedTest(name = "«{0}»")
    @ValueSource(strings = {"000000000001", "１", "1–2", "1.2", "-1", "+1"})
    @DisplayName("numeroFiscal con 9 dígitos sin punto de venta, un dígito de ancho completo, una raya, un punto o un signo da 400 con el mensaje de S15")
    void losBordesInvalidosDelNumeroFiscal(String numeroFiscal) throws Exception {
        Tienda t = tiendaQueFactura(RI);

        String cuerpo = getResult("/api/ventas?numeroFiscal=" + numeroFiscal, login(t.emailDueno()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(cuerpo).get("errors").get("numeroFiscal").asText())
                .isEqualTo(MENSAJE_NUMERO_FISCAL);
    }

    // ---- Ayudas ----

    /** El cuerpo de la hoja (C1): {@code discriminaIva} es true solo si el registro es una A, válida o X. */
    private static void assertDiscriminaIva(JsonNode documento, boolean esperado) {
        assertThat(documento.path("discriminaIva").isBoolean()).as("documento.discriminaIva es booleano").isTrue();
        assertThat(documento.get("discriminaIva").booleanValue()).as("documento.discriminaIva").isEqualTo(esperado);
    }

    /** S12: la marca del X por ambiente y, en producción, por estado. */
    private static String marcaEsperada(String ambiente, String estado) {
        return switch (ambiente) {
            case "SIMULADOR" -> "SIMULADO — sin validez fiscal";
            case "HOMOLOGACION" -> "HOMOLOGACIÓN — sin validez fiscal";
            default -> Set.of("PENDIENTE", "EN_CURSO", "EN_VERIFICACION").contains(estado)
                    ? "TICKET PROVISORIO — factura en trámite con ARCA"
                    : "SIN FACTURA — ARCA no la autorizó";
        };
    }

    /**
     * Lleva el registro de la venta a ese ambiente y estado, respetando los CHECK de V32: con número y CbteFch salvo
     * PENDIENTE, RECHAZADO y FUERA_DE_FECHA; con CAE y vencimiento solo AUTORIZADO.
     */
    private void llevarA(long venta, String ambiente, String estado, String cae) {
        boolean conNumero = !Set.of("PENDIENTE", "RECHAZADO", "FUERA_DE_FECHA").contains(estado);
        boolean autorizado = estado.equals("AUTORIZADO");
        jdbc.update("update comprobante_fiscal set ambiente = ?, estado = ?, numero = ?, cbte_fch = ?, cae = ?, "
                + "cae_vencimiento = ?, resultado = ? where comprobante_id = ?", ambiente, estado,
                conNumero ? 1L : null, conNumero ? "2026-10-28" : null, autorizado ? cae : null,
                autorizado ? "2026-11-07" : null, autorizado ? "A" : estado.equals("RECHAZADO") ? "R" : null, venta);
    }
}
