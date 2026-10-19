package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.ventas.service.ConfirmarVenta;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-005, CC-009: la autorización del exceso de límite viaja en el pedido de venta, opcional y fuera del contenido. */
class ConfirmarVentaRequestTest {

    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    final ObjectMapper json = new ObjectMapper();

    ConfirmarVenta pedidoDe(String cuerpo) throws Exception {
        return json.readValue(cuerpo, ConfirmarVentaRequest.class).aPedido();
    }

    static String cuerpo(String extra) {
        return """
                {"clave":"%s","renglones":[{"articuloId":1,"cantidad":1,"precioUnitario":10.00}],
                 "totalMostrado":10.00,"formaPagoId":4%s}""".formatted(CLAVE, extra);
    }

    @Test
    @DisplayName("sin el campo, autorizaExceso es false")
    void ausente() throws Exception {
        assertThat(pedidoDe(cuerpo("")).autorizaExceso()).isFalse();
    }

    @Test
    @DisplayName("autorizaExceso: true llega al pedido; false y null son false")
    void presente() throws Exception {
        assertThat(pedidoDe(cuerpo(",\"autorizaExceso\":true")).autorizaExceso()).isTrue();
        assertThat(pedidoDe(cuerpo(",\"autorizaExceso\":false")).autorizaExceso()).isFalse();
        assertThat(pedidoDe(cuerpo(",\"autorizaExceso\":null")).autorizaExceso()).isFalse();
    }

    @Test
    @DisplayName("el constructor de 10 argumentos de siempre arma un pedido sin autorización")
    void constructorViejo() {
        ConfirmarVenta pedido = new ConfirmarVenta(CLAVE, List.of(), BigDecimal.TEN, 1L, BigDecimal.ZERO, List.of(),
                null, null, true, 5L);

        assertThat(pedido.autorizaExceso()).isFalse();
        assertThat(pedido.clienteId()).isEqualTo(5L);
    }
}
