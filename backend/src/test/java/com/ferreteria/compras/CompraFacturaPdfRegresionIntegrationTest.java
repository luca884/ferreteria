package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.ListasDePrueba;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regresion de CU-13 (factura en PDF). Margen 30 %, IVA 21 %, sin redondeo. */
class CompraFacturaPdfRegresionIntegrationTest extends ComprasTestBase {

    Tienda tienda;
    String dueno;
    String cuitProveedor;
    long proveedor;
    long tornillo;

    @BeforeEach
    void datos() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        cuitProveedor = cuitValido();
        proveedor = proveedor(tienda.id(), "Bulonera Sur", cuitProveedor);
        tornillo = articulo(tienda.id(), "TOR-1", "Tornillo 1/4", "100.00", "157.30", proveedor, "TOR-9");
    }

    private Map<String, Object> estado(long articulo) {
        return jdbc.queryForMap("select costo, precio_final, precio_manual, recargos_costo, ajustes_precio, "
            + "stock_actual, version from articulo where id = ?", articulo);
    }

    private int compras() {
        return jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class, tienda.id());
    }

    private String cuerpo(String clave, long numero, String costo, String recargos) {
        return """
            {%s"proveedorId": %d, "tipo": "FA", "puntoVenta": 3, "numero": %d, "fecha": "%s",
             "renglones": [{"articuloId": %d, "cantidad": 2, "costoUnitario": %s}]%s}
            """.formatted(clave == null ? "" : "\"clave\": \"" + clave + "\", ", proveedor, numero, AYER, tornillo,
            costo, recargos == null ? "" : ", \"recargos\": " + recargos);
    }

    // ---- lectura de la factura ----

    @Test
    void facturaAConIvaAl21NoDeberiaDarUnRecargoDeIva() throws Exception {
        byte[] pdf = ListasDePrueba.pdf(List.of(
            List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
            List.of("FECHA:", "03/11/2026", "C.U.I.T.: " + cuitProveedor),
            List.of("TOR-9", "10.0", "TORNILLO", "100.00", "1,000.00"),
            List.of("SUBTOTAL", "1,000.00"),
            List.of("IVA 21%", "210.00"),
            List.of("TOTAL", "1,210.00")));
        String json = postMultipart("/api/compras/factura-pdf", dueno, archivo("f.pdf", pdf), null)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode borrador = objectMapper.readTree(json);
        assertThat(borrador.get("recargos")).as("el IVA de la factura no es un recargo del costo").isEmpty();
    }

    // ---- idempotencia y rechazos ----

    @Test
    void reintentoConRecargosEquivalentesEsLaMismaCompraYConOtrosEs409SinTocarNada() throws Exception {
        String clave = UUID.randomUUID().toString();
        postJson("/api/compras", dueno, cuerpo(clave, 1, "110.00",
            "[{\"nombre\": \"Embalaje y carga\", \"porcentaje\": 4.4}]")).andExpect(status().isCreated());
        Map<String, Object> despues = estado(tornillo);

        postJson("/api/compras", dueno, cuerpo(clave, 1, "110.00",
            "[{\"nombre\": \"  Embalaje y carga \", \"porcentaje\": 4.40}]")).andExpect(status().isOk());
        postJson("/api/compras", dueno, cuerpo(clave, 1, "110.00",
            "[{\"nombre\": \"Embalaje y carga\", \"porcentaje\": 4.45}]")).andExpect(status().isConflict());
        postJson("/api/compras", dueno, cuerpo(clave, 1, "110.00", "[]")).andExpect(status().isConflict());

        assertThat(estado(tornillo)).isEqualTo(despues);
        assertThat(compras()).isEqualTo(1);
    }

    @Test
    void unaCompraRechazadaPorSusRecargosNoCambiaNada() throws Exception {
        Map<String, Object> antes = estado(tornillo);
        String seis = "[" + String.join(",", java.util.Collections.nCopies(6,
            "{\"nombre\": \"R\", \"porcentaje\": 1}")) + "]";

        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 9, "110.00", seis))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.recargosCosto").value("Un artículo tiene hasta 5 recargos del costo."));
        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 9, "110.00",
            "[{\"nombre\": \"R\", \"porcentaje\": -60}, {\"nombre\": \"S\", \"porcentaje\": -40}]"))
            .andExpect(status().isBadRequest());

        assertThat(estado(tornillo)).isEqualTo(antes);
        assertThat(compras()).isZero();
    }

    // ---- precio manual y margen negativo con el costo real ----

    @Test
    void conPrecioManualElPrecioQuedaYElMargenNegativoUsaElCostoReal() throws Exception {
        jdbc.update("update articulo set precio_manual = true, precio_final = 140.00 where id = ?", tornillo);

        // Sin recargo: 110 x 1,21 = 133,10 < 140, sin aviso.
        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 1, "110.00", null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.renglones[0].precioNuevo").value(140.00))
            .andExpect(jsonPath("$.renglones[0].advertencia").doesNotExist());
        // Con 10 %: costo real 121 x 1,21 = 146,41 > 140, avisa.
        String previa = postJson("/api/compras/vista-previa", dueno, cuerpo(null, 1, "110.00",
                "[{\"nombre\": \"Flete\", \"porcentaje\": 10}]"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.renglones[0].precioNuevo").value(140.00))
            .andExpect(jsonPath("$.renglones[0].costoRealNuevo").value(121.00))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode renglonPrevia = objectMapper.readTree(previa).get("renglones").get(0);
        assertThat(renglonPrevia.get("advertencia").asText()).startsWith("Margen negativo: el costo ($\u00a0121,00)");

        String registrada = postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 1, "110.00",
                "[{\"nombre\": \"Flete\", \"porcentaje\": 10}]"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        Map<String, Object> fila = estado(tornillo);
        assertThat(fila.get("precio_final").toString()).isEqualTo("140.00");
        assertThat(fila.get("recargos_costo").toString()).contains("Flete");
    }

    // ---- vista previa = registro con ajustes de CU-14 que no entran con el costo real nuevo ----

    @Test
    void ajustesQueNoEntranConElCostoRealNuevoVistaPreviaYRegistroCoinciden() throws Exception {
        // 157,30 - 150 = 7,30. Con -10 %: costo real 90 -> 141,57 - 150 < 0: se conserva el precio con el aviso.
        jdbc.update("update articulo set ajustes_precio = ?, precio_final = 7.30 where id = ?",
            "[{\"nombre\":null,\"tipo\":\"MONTO\",\"valor\":-150}]", tornillo);
        String recargos = "[{\"nombre\": \"Bonif\", \"porcentaje\": -10}]";

        JsonNode previa = objectMapper.readTree(postJson("/api/compras/vista-previa", dueno,
                cuerpo(null, 1, "100.00", recargos))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("renglones").get(0);
        JsonNode registrada = objectMapper.readTree(postJson("/api/compras", dueno,
                cuerpo(UUID.randomUUID().toString(), 1, "100.00", recargos))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("renglones").get(0);

        assertThat(previa.get("precioNuevo").decimalValue()).isEqualByComparingTo("7.30");
        assertThat(registrada.get("precioNuevo").decimalValue()).isEqualByComparingTo(previa.get("precioNuevo").decimalValue());
        assertThat(previa.get("advertencia").asText()).contains("con sus ajustes queda").contains("en 0 o menos");
        Map<String, Object> fila = estado(tornillo);
        assertThat(fila.get("precio_final").toString()).isEqualTo("7.30");
        assertThat(fila.get("recargos_costo").toString()).contains("Bonif");
    }

    // ---- ultima compra en la ficha ----

    @Test
    void laFichaDelDuenoTraeLaUltimaCompraYLaDelVendedorNo() throws Exception {
        JsonNode sinCompra = getJson("/api/articulos/" + tornillo, dueno);
        assertThat(sinCompra.get("recargosCosto")).isEmpty();
        assertThat(sinCompra.path("ultimaCompra").isNull() || sinCompra.path("ultimaCompra").isMissingNode()).isTrue();

        long compra = objectMapper.readTree(postJson("/api/compras", dueno,
                cuerpo(UUID.randomUUID().toString(), 77, "110.00", "[{\"nombre\": \"Flete\", \"porcentaje\": 10}]"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        JsonNode delDueno = getJson("/api/articulos/" + tornillo, dueno);
        assertThat(delDueno.get("ultimaCompra").get("compraId").asLong()).isEqualTo(compra);
        String vendedor = login(tienda.emailVendedor());
        JsonNode delVendedor = getJson("/api/articulos/" + tornillo, vendedor);
        assertThat(delVendedor.has("ultimaCompra")).isFalse();
        assertThat(delVendedor.has("recargosCosto")).isFalse();
        assertThat(delVendedor.has("costo")).isFalse();
    }
}
