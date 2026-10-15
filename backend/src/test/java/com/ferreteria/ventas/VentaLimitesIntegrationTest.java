package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Topes de {@code POST /api/ventas} (S-3 del verify): lo que no entra en DECIMAL(15,3) (cantidad) o en
 * DECIMAL(15,2) (precio, importe del renglon, total) da 400 con mensaje por campo y no toca la base.
 */
class VentaLimitesIntegrationTest extends VentasTestBase {

    @Test
    void cantidadFueraDeDecimal15Con3Da400SinGuardarNada() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Cable", "METRO", "1.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "1.00", linea(a, "1000000000000", "1.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].cantidad").asText())
                .isEqualTo("La cantidad es demasiado grande: el máximo es 999.999.999.999,999.");
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void precioMostradoFueraDeDecimal15Con2Da400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "100.00",
                linea(a, "1", "10000000000000.00")).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].precioUnitario").asText())
                .isEqualTo("El precio es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void totalMostradoFueraDeDecimal15Con2Da400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "10000000000000.00",
                linea(a, "1", "100.00")).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("totalMostrado").asText())
                .isEqualTo("El total es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void cantidadPorPrecioQueDesbordaElRenglonDa400EnLaCantidad() throws Exception {
        Tienda t = tienda();
        // 500.000.000.000 x 100 = 50.000.000.000.000: cada dato entra solo, el producto no.
        long a = articulo(t, "Cable", "METRO", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "100.00",
                linea(a, "500000000000", "100.00")).andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].cantidad").asText())
                .isEqualTo("El importe de \"Cable\" es demasiado grande: el máximo por renglón es $ 9.999.999.999.999,99.");
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    void renglonesQueEntranPorSeparadoPeroSumanMasQueElTopeDan400EnElTotal() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Maquina A", "UNIDAD", "9000000000000.00", "10");
        long b = articulo(t, "Maquina B", "UNIDAD", "9000000000000.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "9000000000000.00",
                linea(a, "1", "9000000000000.00"), linea(b, "1", "9000000000000.00"))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("totalMostrado").asText())
                .isEqualTo("El total de la venta es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(b)).isEqualByComparingTo("10");
    }

    @Test
    void justoEnElTopeSeVende() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Maquina", "UNIDAD", "9999999999999.99", "10");
        long b = articulo(t, "Cable", "METRO", "1.00", "0");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "9999999999999.99",
                linea(a, "1", "9999999999999.99")).andExpect(status().isCreated()));
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("9999999999999.99");
        assertThat(stock(a)).isEqualByComparingTo("9");

        // La cantidad maxima (999.999.999.999,999) a $ 1 es un importe de 999.999.999.999,999 -> 1.000.000.000.000,00.
        vender(login(t.emailVendedor()), clave(), "1000000000000.00", linea(b, "999999999999.999", "1.00"))
                .andExpect(status().isCreated());
        assertThat(stock(b)).isEqualByComparingTo("-999999999999.999");
    }
}
