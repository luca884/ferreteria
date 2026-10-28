package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los receptores de la venta facturada de un emisor Responsable Inscripto (cu-07-venta-facturada S1 y S3; sonda p7 del
 * verificador) contra MySQL real y el simulador: cada condición frente al IVA sale con su letra, su
 * {@code CondicionIVAReceptorId} y su {@code DocTipo}, y el Consumidor Final identificado con DNI no tiene el tope de ARCA
 * de la venta sin identificar.
 */
class VentaFacturadaReceptoresIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    @ParameterizedTest(name = "{0} con {1} → Factura {3}")
    @CsvSource({
            "EXENTO, CUIT, Rivadavia 1, B, 4, 80",
            "CONSUMIDOR_FINAL, CUIT, , B, 5, 80",
            "MONOTRIBUTISTA, CUIT, Colón 3, A, 6, 80"})
    @DisplayName("Exento con CUIT y domicilio, Consumidor Final con CUIT y Monotributista con CUIT y domicilio salen AUTORIZADOS con su letra, su condición y su documento")
    void cadaReceptorConSuLetraCondicionYDocumento(String condicion, String tipoDocumento, String domicilio, String letra,
            int condicionIvaReceptorId, int docTipo) throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String cuit = cuitDeCliente();
        long cliente = cliente(t, "Cliente " + condicion, condicion, tipoDocumento, cuit, domicilio);

        JsonNode venta = json(venderA(t, cliente, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(venta.get("fiscal").get("letra").asText()).isEqualTo(letra);
        assertThat(venta.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(registroDe(venta.get("ventaId").asLong())).containsEntry("letra", letra)
                .containsEntry("receptor_condicion_iva_id", condicionIvaReceptorId).containsEntry("receptor_doc_tipo", docTipo)
                .containsEntry("receptor_doc_nro", Long.parseLong(cuit));
    }

    @Test
    @DisplayName("a un Consumidor Final con DNI se le factura desde el tope de ARCA y por encima: 201 AUTORIZADO, Factura B con DocTipo 96")
    void consumidorFinalConDniEnElTopeDeArcaYPorEncima() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long enElTope = articulo(t, "Autoelevador", "UNIDAD", "10000000.00", "5");
        long porEncima = articulo(t, "Grúa", "UNIDAD", "12000000.00", "5");
        long conDni = cliente(t, "Ana Gómez", "CONSUMIDOR_FINAL", "DNI", "30123456", null);

        for (Map.Entry<Long, String> venta : Map.of(enElTope, "10000000.00", porEncima, "12000000.00").entrySet()) {
            JsonNode respuesta = json(venderA(t, conDni, null, clave(), venta.getValue(),
                    linea(venta.getKey(), "1", venta.getValue())).andExpect(status().isCreated()));

            assertThat(respuesta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
            Map<String, Object> registro = registroDe(respuesta.get("ventaId").asLong());
            assertThat(registro).containsEntry("letra", "B").containsEntry("receptor_condicion_iva_id", 5)
                    .containsEntry("receptor_doc_tipo", 96).containsEntry("receptor_doc_nro", 30123456L);
            assertThat((BigDecimal) registro.get("imp_total")).isEqualByComparingTo(venta.getValue());
        }
        assertThat(registrosFiscales(t)).isEqualTo(2);
    }
}
