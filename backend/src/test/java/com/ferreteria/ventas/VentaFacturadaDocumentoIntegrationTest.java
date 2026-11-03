package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.facturacion.documento.LectorQr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La factura imprimible y lo que leen el cobro y Ventas (cu-07-factura-mostrador S2, S3, S8, S15 y C1 a C3), por HTTP
 * contra MySQL real y el simulador: {@code fiscal.documento} al cobrar (201 y 200) y en el detalle, {@code remitoNumero},
 * el resumen fiscal y la búsqueda por número fiscal en el listado (solo del Dueño), y lo que el cobro anticipa en
 * {@code GET /api/ventas/facturacion} (los dos roles). Lo que autoriza el simulador sale X; una factura válida de producción
 * se inserta por SQL (todavía no hay adaptador de producción).
 */
class VentaFacturadaDocumentoIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";
    private static final String CAE = "70417054367476";
    private static final String MENSAJE_NUMERO_FISCAL = "Escribí el número de la factura como 12 o 00001-00000012.";

    // ---- C1: el documento en la respuesta del cobro y en el detalle ----

    @Test
    @DisplayName("el cobro facturado responde «fiscal» con sus 8 campos y «documento»: el X «SIMULADO», con la copia del registro; la repetida (200) trae el mismo")
    void elCobroTraeElDocumento() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String clave = clave();
        String cuerpo = cuerpoDe(t, null, null, clave, "242.00", linea(a, "2", "121.00"));

        String crudo = postJson("/api/ventas", login(t.emailVendedor()), cuerpo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode repetida = json(postJson("/api/ventas", login(t.emailVendedor()), cuerpo).andExpect(status().isOk()));

        JsonNode fiscal = objectMapper.readTree(crudo).get("fiscal");
        Map<String, Object> registro = registroDe(objectMapper.readTree(crudo).get("ventaId").asLong());
        // Los 8 campos de la rama 3, igual que antes.
        assertThat(fiscal.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(fiscal.get("clase").asText()).isEqualTo("FACTURA");
        assertThat(fiscal.get("letra").asText()).isEqualTo("B");
        assertThat(fiscal.get("puntoVenta").asInt()).isEqualTo(1);
        assertThat(fiscal.get("numero").asLong()).isEqualTo(1);
        assertThat(fiscal.get("cae").asText()).isEqualTo(registro.get("cae"));
        assertThat(fiscal.get("caeVencimiento").asText()).isEqualTo(registro.get("cae_vencimiento").toString());
        assertThat(fiscal.get("ambiente").asText()).isEqualTo("SIMULADOR");
        // El documento: X, sin nada que lo haga pasar por una factura.
        JsonNode d = fiscal.get("documento");
        assertThat(d.get("validoComoFactura").asBoolean()).isFalse();
        assertThat(d.get("letra").asText()).isEqualTo("X");
        assertThat(d.get("marca").asText()).isEqualTo("SIMULADO — sin validez fiscal");
        for (String nulo : List.of("codigo", "puntoVenta", "numero", "cae", "caeVencimiento", "qr", "qrModulos",
                "leyendaMonotributo")) {
            assertThat(d.has(nulo)).as("documento.%s presente", nulo).isTrue();
            assertThat(d.get(nulo).isNull()).as("documento.%s en null", nulo).isTrue();
        }
        assertThat(d.get("fecha").asText()).isEqualTo("2026-10-28");
        String razonSocial = jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, t.id());
        String cuit = jdbc.queryForObject("select replace(cuit, '-', '') from ferreteria where id = ?", String.class, t.id());
        assertThat(d.get("emisor").get("razonSocial").asText()).isEqualTo(razonSocial);
        assertThat(d.get("emisor").get("cuit").asText()).isEqualTo(cuit);
        assertThat(d.get("emisor").get("condicionIva").asText()).isEqualTo("IVA RESPONSABLE INSCRIPTO");
        assertThat(d.get("emisor").get("domicilioComercial").asText()).isEqualTo("Av. Luro 1234, Mar del Plata");
        assertThat(d.get("emisor").get("ingresosBrutos").asText()).isEqualTo("901-123456-7");
        assertThat(d.get("emisor").get("inicioActividades").asText()).isEqualTo("2015-03-01");
        JsonNode receptor = d.get("receptor");
        assertThat(receptor.get("nombre").isNull()).isTrue();
        assertThat(receptor.get("condicionIva").asText()).isEqualTo("CONSUMIDOR FINAL");
        assertThat(receptor.get("tipoDocumento").isNull()).isTrue();
        assertThat(receptor.get("numeroDocumento").isNull()).isTrue();
        assertThat(receptor.get("domicilio").isNull()).isTrue();
        assertThat(receptor.get("aConsumidorFinal").asBoolean()).isTrue();
        assertThat(d.get("total").decimalValue()).isEqualByComparingTo("242.00");
        assertThat(d.get("neto").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(d.get("iva").decimalValue()).isEqualByComparingTo("42.00");
        assertThat(d.get("noGravado").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.get("exento").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.get("alicuotas")).hasSize(1);
        assertThat(d.get("alicuotas").get(0).get("porcentaje").decimalValue()).isEqualByComparingTo("21");
        assertThat(d.get("alicuotas").get(0).get("base").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(d.get("alicuotas").get(0).get("importe").decimalValue()).isEqualByComparingTo("42.00");
        assertThat(d.get("transparencia").get("ivaContenido").decimalValue()).isEqualByComparingTo("42.00");
        // Montos con 2 decimales en el JSON.
        assertThat(crudo).contains("\"otrosImpuestosNacionalesIndirectos\":0.00").contains("\"porcentaje\":21.00")
                .contains("\"total\":242.00");
        // La repetida responde lo mismo.
        assertThat(repetida.get("fiscal")).isEqualTo(fiscal);
    }

    @Test
    @DisplayName("el detalle (Dueño) trae el mismo «fiscal» con su documento, más lo de la bandeja (cu-07-contingencias C1); "
            + "la venta sin registro fiscal sigue sin «fiscal»")
    void elDetalleTraeElDocumento() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        JsonNode cobrada = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        JsonNode sinFactura = json(venderA(t, null, false, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        String dueno = login(t.emailDueno());

        JsonNode detalle = getJson("/api/ventas/" + cobrada.get("ventaId").asLong(), dueno);
        JsonNode detalleSinFactura = getJson("/api/ventas/" + sinFactura.get("ventaId").asLong(), dueno);

        // Lo del cobro, igual; el detalle suma lo de la bandeja (C1), vacío en una autorizada que no salió provisoria.
        ObjectNode delDetalle = ((ObjectNode) detalle.get("fiscal")).deepCopy();
        for (String lista : List.of("errores", "observaciones", "acciones")) {
            assertThat(delDetalle.remove(lista)).as(lista).isNotNull().isEmpty();
        }
        for (String nulo : List.of("motivoSinFactura", "sinFacturaPor", "sinFacturaEn", "motivoFueraDeFecha")) {
            assertThat(delDetalle.remove(nulo).isNull()).as(nulo).isTrue();
        }
        for (String marca : List.of("fechaNuevaRequerida", "reimprimir")) {
            assertThat(delDetalle.remove(marca).isBoolean()).as(marca).isTrue();
            assertThat(detalle.get("fiscal").get(marca).asBoolean()).as(marca).isFalse();
        }
        assertThat(delDetalle).isEqualTo(cobrada.get("fiscal"));
        assertThat(detalle.get("fiscal").get("documento").get("letra").asText()).isEqualTo("X");
        assertSinFiscal(sinFactura);
        assertSinFiscal(detalleSinFactura);
        assertThat(detalleSinFactura.get("renglones")).hasSize(1);
    }

    @Test
    @DisplayName("una factura válida de producción: el detalle trae la letra, el código, el número, el CAE y el QR que lee ZXing; el listado, la letra")
    void unaFacturaValidaTraeElQr() throws Exception {
        Tienda t = tiendaConDatosFiscales(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long venta = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        facturaValidaDe(t, venta, 2, 12);
        String dueno = login(t.emailDueno());

        JsonNode fiscal = getJson("/api/ventas/" + venta, dueno).get("fiscal");
        JsonNode fila = getJson("/api/ventas", dueno).get("items").get(0);

        assertThat(fiscal.get("ambiente").asText()).isEqualTo("PRODUCCION");
        JsonNode d = fiscal.get("documento");
        assertThat(d.get("validoComoFactura").asBoolean()).isTrue();
        assertThat(d.get("letra").asText()).isEqualTo("B");
        assertThat(d.get("codigo").asInt()).isEqualTo(6);
        assertThat(d.get("marca").isNull()).isTrue();
        assertThat(d.get("puntoVenta").asInt()).isEqualTo(2);
        assertThat(d.get("numero").asLong()).isEqualTo(12);
        assertThat(d.get("fecha").asText()).isEqualTo("2026-10-28");
        assertThat(d.get("cae").asText()).isEqualTo(CAE);
        assertThat(d.get("caeVencimiento").asText()).isEqualTo("2026-11-07");
        assertThat(d.get("emisor").get("razonSocial").asText()).isEqualTo("El Tornillo SRL");
        assertThat(d.get("qr").asText()).startsWith("https://www.arca.gob.ar/fe/qr/?p=");
        List<String> modulos = new ArrayList<>();
        d.get("qrModulos").forEach(m -> modulos.add(m.asText()));
        assertThat(LectorQr.leer(modulos).texto()).isEqualTo(d.get("qr").asText());
        assertThat(LectorQr.datos(d.get("qr").asText()).get("nroCmp").asLong()).isEqualTo(12);
        assertThat(fila.get("fiscal").get("letra").asText()).isEqualTo("B");
        assertThat(fila.get("fiscal").get("validoComoFactura").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("la venta que sale de un remito trae «remitoNumero» al cobrar, repetida y en el detalle; la que no, no lo trae")
    void remitoNumero() throws Exception {
        Tienda t = tiendaConDatosFiscales(RI);
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        JsonNode remito = remito(vendedor, a, "2", "100.00", "200.00");
        Map<String, Object> cuerpo = mapa(clave(), null, "200.00", linea(a, "2", "100.00"));
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));
        cuerpo.put("remitoId", remito.get("remitoId").asLong());
        String json = objectMapper.writeValueAsString(cuerpo);

        JsonNode venta = json(postJson("/api/ventas", vendedor, json).andExpect(status().isCreated()));
        JsonNode repetida = json(postJson("/api/ventas", vendedor, json).andExpect(status().isOk()));
        JsonNode detalle = getJson("/api/ventas/" + venta.get("ventaId").asLong(), login(t.emailDueno()));
        JsonNode sinRemito = json(venderA(t, null, null, clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated()));

        long numero = remito.get("numero").asLong();
        assertThat(venta.get("remitoNumero").asLong()).isEqualTo(numero);
        assertThat(repetida.get("remitoNumero").asLong()).isEqualTo(numero);
        assertThat(detalle.get("remitoNumero").asLong()).isEqualTo(numero);
        assertThat(detalle.get("remitoId").asLong()).isEqualTo(remito.get("remitoId").asLong());
        assertThat(sinRemito.has("remitoNumero")).isFalse();
        assertThat(sinRemito.has("remitoId")).isFalse();
    }

    // ---- C2 y S15: el listado del Dueño ----

    @Test
    @DisplayName("el listado suma «fiscal» por venta (la letra de la hoja: X en el simulador) y null en la venta sin registro")
    void elListadoTraeElResumenFiscal() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        venderA(t, null, false, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());

        JsonNode items = getJson("/api/ventas", login(t.emailDueno())).get("items");

        JsonNode sinRegistro = items.get(0);
        JsonNode facturada = items.get(1);
        assertThat(sinRegistro.get("numero").asLong()).isEqualTo(2);
        assertThat(sinRegistro.has("fiscal")).isTrue();
        assertThat(sinRegistro.get("fiscal").isNull()).isTrue();
        assertThat(sinRegistro.get("facturable").asBoolean()).isFalse();
        JsonNode fiscal = facturada.get("fiscal");
        assertThat(fiscal.get("letra").asText()).isEqualTo("X");
        assertThat(fiscal.get("puntoVenta").asInt()).isEqualTo(1);
        assertThat(fiscal.get("numero").asLong()).isEqualTo(1);
        assertThat(fiscal.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(fiscal.get("ambiente").asText()).isEqualTo("SIMULADOR");
        assertThat(fiscal.get("validoComoFactura").asBoolean()).isFalse();
        assertThat(facturada.get("total").decimalValue()).isEqualByComparingTo("121.00");
        assertThat(facturada.get("usuario").asText()).isEqualTo("Vendedor Ventas");
    }

    @Test
    @DisplayName("busca por número fiscal («2», «1-2» o «00001-00000002»), junto con los otros filtros; otra ferretería no aparece")
    void buscaPorNumeroFiscal() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        for (int i = 0; i < 3; i++) {
            venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        }
        Tienda otra = tiendaQueFactura(RI);
        long b = articulo(otra, "Clavo", "UNIDAD", "121.00", "10");
        for (int i = 0; i < 2; i++) {
            venderA(otra, null, null, clave(), "121.00", linea(b, "1", "121.00")).andExpect(status().isCreated());
        }
        String dueno = login(t.emailDueno());

        assertThat(numeros(getJson("/api/ventas?numeroFiscal=2", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=1-2", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=00001-00000002", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numeroFiscal= 2 ", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=2-2", dueno))).isEmpty();
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=9", dueno))).isEmpty();
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=2&numero=3", dueno))).isEmpty();
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=2&numero=2", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=", dueno))).containsExactly(3L, 2L, 1L);
        assertThat(getJson("/api/ventas?numeroFiscal=2", dueno).get("totalElements").asLong()).isEqualTo(1);
        // La otra ferretería tiene su propia factura 2: no se cuela, ni la suya se ve desde acá.
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=3", login(otra.emailDueno())))).isEmpty();
        assertThat(numeros(getJson("/api/ventas?numeroFiscal=2", login(otra.emailDueno())))).containsExactly(2L);
        assertThat(getJson("/api/ventas?numeroFiscal=2", login(otra.emailDueno())).get("items").get(0).get("ventaId")
                .asLong()).isNotEqualTo(getJson("/api/ventas?numeroFiscal=2", dueno).get("items").get(0).get("ventaId")
                        .asLong());
    }

    @ParameterizedTest(name = "«{0}»")
    @ValueSource(strings = {"abc", "1-", "-12", "123456-12", "1-123456789", "123456789", "1/12", "1-2-3", "1 - 2"})
    @DisplayName("un número fiscal con otro formato da 400 en «numeroFiscal», con el mensaje de S15")
    void unNumeroFiscalInvalidoDa400(String numeroFiscal) throws Exception {
        Tienda t = tiendaQueFactura(RI);

        String cuerpo = getResult("/api/ventas?numeroFiscal=" + numeroFiscal, login(t.emailDueno()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(cuerpo).get("errors").get("numeroFiscal").asText())
                .isEqualTo(MENSAJE_NUMERO_FISCAL);
    }

    // ---- C3: lo que anticipa el cobro, para los dos roles ----

    @Test
    @DisplayName("GET /api/ventas/facturacion: el Vendedor y el Dueño ven el ambiente y las letras; sin facturación, factura false; Ventas sigue siendo del Dueño")
    void laFacturacionDelMostrador() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        Tienda sinFacturacion = tiendaConDatosFiscales(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long venta = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        String vendedor = login(t.emailVendedor());

        for (String token : List.of(vendedor, login(t.emailDueno()))) {
            JsonNode facturacion = getJson("/api/ventas/facturacion", token);
            assertThat(facturacion.get("factura").asBoolean()).isTrue();
            assertThat(facturacion.get("ambiente").asText()).isEqualTo("SIMULADOR");
            assertThat(facturacion.get("letras").get("CONSUMIDOR_FINAL").asText()).isEqualTo("B");
            assertThat(facturacion.get("letras").get("RESPONSABLE_INSCRIPTO").asText()).isEqualTo("A");
            assertThat(facturacion.get("letras").get("MONOTRIBUTISTA").asText()).isEqualTo("A");
            assertThat(facturacion.get("letras").get("EXENTO").asText()).isEqualTo("B");
            assertThat(facturacion.size()).isEqualTo(3);
        }
        JsonNode sin = getJson("/api/ventas/facturacion", login(sinFacturacion.emailVendedor()));
        assertThat(sin.get("factura").asBoolean()).isFalse();
        assertThat(sin.has("ambiente")).isTrue();
        assertThat(sin.get("ambiente").isNull()).isTrue();
        assertThat(sin.has("letras")).isTrue();
        assertThat(sin.get("letras").isNull()).isTrue();
        // D7: no hay lectura de ventas nueva para el Vendedor.
        getResult("/api/ventas", vendedor).andExpect(status().isForbidden());
        getResult("/api/ventas/" + venta, vendedor).andExpect(status().isForbidden());
    }

    // ---- Ayudas ----

    private static List<Long> numeros(JsonNode pagina) {
        List<Long> numeros = new ArrayList<>();
        pagina.get("items").forEach(i -> numeros.add(i.get("numero").asLong()));
        return numeros;
    }

    /**
     * Una Factura B de producción AUTORIZADA a Consumidor Final por $ 121 para la venta, por SQL (respeta los CHECK de V32),
     * con una copia del emisor distinta de los datos de hoy de la ferretería.
     */
    private void facturaValidaDe(Tienda t, long venta, int puntoVenta, long numero) {
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, numero, cbte_fch, estado, cae, cae_vencimiento, resultado, receptor_doc_tipo, "
                + "receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, emisor_razon_social, emisor_condicion_iva, "
                + "emisor_domicilio_comercial, emisor_ingresos_brutos, emisor_inicio_actividades, imp_total, imp_neto, "
                + "imp_iva, imp_tot_conc, imp_op_ex, registrado_en) values (?, ?, 'PRODUCCION', 'FACTURA', 'B', 6, ?, ?, "
                + "'2026-10-28', 'AUTORIZADO', ?, '2026-11-07', 'A', 99, 0, 5, '30-71234567-8', 'El Tornillo SRL', "
                + "'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '901-765432-1', '2015-03-01', 121.00, 100.00, "
                + "21.00, 0, 0, '2026-10-28 13:15:30')", t.id(), venta, puntoVenta, numero, CAE);
        long registro = jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ?", Long.class, venta);
        jdbc.update("insert into comprobante_fiscal_alicuota (ferreteria_id, comprobante_fiscal_id, iva_id, base_imp, "
                + "importe) values (?, ?, 5, ?, ?)", t.id(), registro, new BigDecimal("100.00"), new BigDecimal("21.00"));
    }
}
