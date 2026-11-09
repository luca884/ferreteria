package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La compra con recargos del costo (CU-13, S4 y S6): la vista previa y el registro usan el costo real, la compra pisa los
 * recargos de sus articulos (vacios, los borra) y el detalle los devuelve. Margen 30 %, IVA 21 %.
 */
class CompraRecargosIntegrationTest extends ComprasTestBase {

    Tienda tienda;
    String dueno;
    long proveedor;
    long tornillo;

    @BeforeEach
    void datos() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        proveedor = proveedor(tienda.id(), "Bulonera Sur", null);
        tornillo = articulo(tienda.id(), "TOR-1", "Tornillo 1/4", "100.00", "157.30", null, null);
    }

    @Test
    @DisplayName("la vista previa calcula el precio con el costo real y lo muestra, sin guardar nada")
    void vistaPreviaConCostoReal() throws Exception {
        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 1, RECARGO_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recargos[0].nombre").value("Embalaje y carga"))
                .andExpect(jsonPath("$.recargos[0].porcentaje").value(10.00))
                .andExpect(jsonPath("$.renglones[0].costoNuevo").value(110.00))
                .andExpect(jsonPath("$.renglones[0].costoRealAnterior").value(100.00))
                .andExpect(jsonPath("$.renglones[0].costoRealNuevo").value(121.00))
                .andExpect(jsonPath("$.renglones[0].precioAnterior").value(157.30))
                .andExpect(jsonPath("$.renglones[0].precioNuevo").value(190.34));
        assertThat(jdbc.queryForObject("select recargos_costo from articulo where id = ?", String.class, tornillo))
                .isEqualTo("[]");
    }

    @Test
    @DisplayName("registrar guarda los recargos en la compra y en el articulo, y el detalle los devuelve")
    void registrarGuardaLosRecargos() throws Exception {
        JsonNode creada = objectMapper.readTree(postJson("/api/compras", dueno,
                        cuerpo(UUID.randomUUID().toString(), 1, RECARGO_10))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recargos[0].nombre").value("Embalaje y carga"))
                .andExpect(jsonPath("$.renglones[0].precioNuevo").value(190.34))
                .andReturn().getResponse().getContentAsString());

        assertThat(jdbc.queryForObject("select precio_final from articulo where id = ?", String.class, tornillo))
                .isEqualTo("190.34");
        JsonNode recargosDelArticulo = objectMapper.readTree(
                jdbc.queryForObject("select recargos_costo from articulo where id = ?", String.class, tornillo));
        assertThat(recargosDelArticulo).hasSize(1);
        assertThat(recargosDelArticulo.get(0).get("nombre").asText()).isEqualTo("Embalaje y carga");
        assertThat(recargosDelArticulo.get(0).get("porcentaje").decimalValue()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ?", Integer.class,
                tornillo)).isEqualTo(1);

        getResult("/api/compras/" + creada.get("id").asLong(), dueno)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recargos[0].nombre").value("Embalaje y carga"))
                .andExpect(jsonPath("$.recargos[0].porcentaje").value(10.00));
    }

    @Test
    @DisplayName("una compra sin recargos borra los que tenia el articulo y el precio vuelve al del costo")
    void unaCompraSinRecargosLosBorra() throws Exception {
        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 1, RECARGO_10))
                .andExpect(status().isCreated());

        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 2, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recargos").isEmpty())
                .andExpect(jsonPath("$.renglones[0].precioAnterior").value(190.34))
                .andExpect(jsonPath("$.renglones[0].precioNuevo").value(173.03));

        assertThat(jdbc.queryForObject("select recargos_costo from articulo where id = ?", String.class, tornillo))
                .isEqualTo("[]");
    }

    @Test
    @DisplayName("la misma clave con otros recargos es otra compra: 409")
    void laMismaClaveConOtrosRecargosEs409() throws Exception {
        String clave = UUID.randomUUID().toString();
        postJson("/api/compras", dueno, cuerpo(clave, 1, RECARGO_10)).andExpect(status().isCreated());

        postJson("/api/compras", dueno, cuerpo(clave, 1, RECARGO_10)).andExpect(status().isOk());
        postJson("/api/compras", dueno, cuerpo(clave, 1, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Esta compra ya se registró con otros datos."));
    }

    @Test
    @DisplayName("los recargos se validan con las reglas de los del articulo: 400 en recargosCosto")
    void recargosInvalidos() throws Exception {
        postJson("/api/compras/vista-previa", dueno,
                cuerpo(null, 1, "[{\"nombre\": \"Flete\", \"porcentaje\": 0}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto")
                        .value("El porcentaje del recargo debe estar entre -99,99 y 99,99, y no puede ser 0."));
        postJson("/api/compras/vista-previa", dueno,
                cuerpo(null, 1, "[{\"nombre\": \"\", \"porcentaje\": 5}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto").value("El nombre del recargo es obligatorio."));
        postJson("/api/compras", dueno,
                cuerpo(UUID.randomUUID().toString(), 1, "[{\"nombre\": \"Flete\", \"porcentaje\": 1.234}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto").value("El porcentaje del recargo tiene hasta 2 decimales."));
    }

    @Test
    @DisplayName("el contador de la vista previa compara el costo real: con el mismo costo, el recargo lo hace subir")
    void elContadorCuentaLosRecargos() throws Exception {
        contador(cuerpo(null, 1, RECARGO_10, "100.00"), 1, 0, 0);
        contador(cuerpo(null, 1, RECARGO_10, "95.00"), 1, 0, 0);
        contador(cuerpo(null, 1, "[{\"nombre\": \"Pronto pago\", \"porcentaje\": -10}]", "100.00"), 0, 1, 0);
        contador(cuerpo(null, 1, null, "100.00"), 0, 0, 1);
        contador(cuerpo(null, 1, null, "110.00"), 1, 0, 0);
    }

    @Test
    @DisplayName("una compra sin recargos sobre un articulo que los tenia cuenta como baja: el costo real baja")
    void sinRecargosSobreUnoQueLosTeniaBaja() throws Exception {
        postJson("/api/compras", dueno, cuerpo(UUID.randomUUID().toString(), 1, RECARGO_10, "100.00"))
                .andExpect(status().isCreated());

        postJson("/api/compras/vista-previa", dueno, cuerpo(null, 2, null, "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renglones[0].costoRealAnterior").value(110.00))
                .andExpect(jsonPath("$.renglones[0].costoRealNuevo").value(100.00))
                .andExpect(jsonPath("$.suben").value(0))
                .andExpect(jsonPath("$.bajan").value(1))
                .andExpect(jsonPath("$.iguales").value(0));
    }

    // ---- helpers ----

    private static final String RECARGO_10 = "[{\"nombre\": \"Embalaje y carga\", \"porcentaje\": 10}]";

    private void contador(String cuerpo, int suben, int bajan, int iguales) throws Exception {
        postJson("/api/compras/vista-previa", dueno, cuerpo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suben").value(suben))
                .andExpect(jsonPath("$.bajan").value(bajan))
                .andExpect(jsonPath("$.iguales").value(iguales));
    }

    private String cuerpo(String clave, long numero, String recargos) {
        return cuerpo(clave, numero, recargos, "110.00");
    }

    private String cuerpo(String clave, long numero, String recargos, String costo) {
        return """
                {%s"proveedorId": %d, "tipo": "FA", "puntoVenta": 3, "numero": %d, "fecha": "%s",
                 "renglones": [{"articuloId": %d, "cantidad": 20, "costoUnitario": %s}]%s}
                """.formatted(clave == null ? "" : "\"clave\": \"" + clave + "\", ", proveedor, numero, AYER, tornillo,
                costo, recargos == null ? "" : ", \"recargos\": " + recargos);
    }
}
