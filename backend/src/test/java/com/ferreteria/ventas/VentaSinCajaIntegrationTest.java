package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-10 en la venta: sin caja abierta del dia no se vende, y cada venta queda en la caja abierta. */
class VentaSinCajaIntegrationTest extends VentasTestBase {

    @Test
    @DisplayName("sin caja abierta la venta da 422 CAJA_CERRADA y no guarda nada ni toca el stock")
    void sinCajaNoSeVende() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(cuerpo.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(cuerpo.get("message").asText()).isEqualTo("Abrí la caja para vender.");
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("con la caja abierta de un dia anterior la venta da 422 CAJA_DE_OTRO_DIA con la fecha de esa caja")
    void cajaDeOtroDiaNoSeVende() throws Exception {
        Tienda t = tiendaSinCaja();
        LocalDateTime ayer = LocalDateTime.now(HoraArgentina.ZONA).minusDays(1);
        cajaAbiertaEl(t.id(), ayer);
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(cuerpo.get("motivo").asText()).isEqualTo("CAJA_DE_OTRO_DIA");
        assertThat(cuerpo.get("message").asText()).isEqualTo(
                "La caja del " + ayer.format(DateTimeFormatter.ofPattern("dd/MM")) + " sigue abierta. "
                        + "Cerrala antes de vender.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("la venta queda asociada a la caja abierta")
    void laVentaQuedaEnLaCaja() throws Exception {
        Tienda t = tiendaSinCaja();
        long caja = cajaAbiertaHoy(t.id());
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select caja_id from comprobante where ferreteria_id = ?", Long.class, t.id()))
                .isEqualTo(caja);
    }

    @Test
    @DisplayName("la caja abierta de otra ferreteria no habilita a vender")
    void laCajaDeOtraFerreteriaNoCuenta() throws Exception {
        Tienda propia = tiendaSinCaja();
        Tienda ajena = tienda();
        long a = articulo(propia, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(vender(login(propia.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(cuerpo.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(comprobantes(propia)).isZero();
        assertThat(comprobantes(ajena)).isZero();
    }

    @Test
    @DisplayName("la clave repetida de una venta ya hecha sigue devolviendo la venta original aunque la caja se haya cerrado")
    void laRepetidaNoMiraLaCaja() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        vender(token, clave, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());
        jdbc.update("update caja set abierta = null, fecha_cierre = now(6) where ferreteria_id = ?", t.id());

        vender(token, clave, "100.00", linea(a, "1", "100.00")).andExpect(status().isOk());

        assertThat(comprobantes(t)).isEqualTo(1);
    }
}
