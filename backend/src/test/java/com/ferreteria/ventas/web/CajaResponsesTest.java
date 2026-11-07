package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.EstadoCaja;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.ResumenCaja;
import com.ferreteria.ventas.model.VentasAnuladas;
import com.ferreteria.ventas.service.CajaVigente;
import com.ferreteria.ventas.service.DetalleCaja;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CCT3-304 (CC-053): el Detalle de caja expone las cobranzas por medio y el total; la caja abierta y el estado vigente (lo que
 * ve cualquier usuario) siguen sin cifras: el cierre ciego queda intacto.
 */
class CajaResponsesTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final VentasAnuladas NINGUNA_ANULADA = new VentasAnuladas(0, BigDecimal.ZERO);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Caja caja() {
        Caja caja = Caja.abrir(3L, 9L, bd("5000.00"), null, LocalDateTime.of(2026, 10, 12, 8, 0));
        ReflectionTestUtils.setField(caja, "id", 40L);
        return caja;
    }

    @Test
    @DisplayName("Detalle lleva «cobranzas» por medio (con ceros) y «totalCobranzas»; las ventas y su total no cambian")
    void detalleExponeLasCobranzas() throws Exception {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1000.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00"), MedioPago.TRANSFERENCIA, bd("300.00")), Map.of(), Map.of());
        DetalleCaja detalle = new DetalleCaja(caja(), EstadoCaja.ABIERTA, resumen, NINGUNA_ANULADA, List.of(),
                Map.of(9L, "Ana"));

        CajaResponses.Detalle respuesta = CajaResponses.Detalle.de(detalle);

        assertThat(respuesta.cobranzas().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
        assertThat(respuesta.cobranzas().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
        assertThat(respuesta.cobranzas()).containsOnlyKeys(MedioPago.values());
        assertThat(respuesta.totalCobranzas()).isEqualByComparingTo("1000");
        assertThat(respuesta.totalVentas()).isEqualByComparingTo("1000");
        assertThat(respuesta.efectivoEsperado()).isEqualByComparingTo("6700");
        JsonNode json = JSON.valueToTree(respuesta);
        assertThat(json.has("cobranzas")).isTrue();
        assertThat(json.has("totalCobranzas")).isTrue();
    }

    @Test
    @DisplayName("una caja sin recibos (como las de antes de 3a) muestra todas las cobranzas en 0")
    void cajaViejaCobranzasEnCero() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(), Map.of(), Map.of(), Map.of());
        DetalleCaja detalle = new DetalleCaja(caja(), EstadoCaja.ABIERTA, resumen, NINGUNA_ANULADA, List.of(),
                Map.of(9L, "Ana"));

        CajaResponses.Detalle respuesta = CajaResponses.Detalle.de(detalle);

        assertThat(respuesta.cobranzas().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(respuesta.totalCobranzas()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la caja abierta y el estado vigente (lo que ve el Vendedor) NO llevan cobranzas ni ninguna cifra: cierre ciego intacto")
    void vigenteYAbiertaSinCifras() throws Exception {
        CajaVigente vigente = new CajaVigente(caja(), EstadoCaja.ABIERTA, "Ana", bd("5000.00"), true);

        JsonNode abierta = JSON.valueToTree(CajaResponses.CajaAbierta.de(vigente));
        JsonNode estado = JSON.valueToTree(CajaResponses.Vigente.de(vigente));

        for (JsonNode json : List.of(abierta, estado, estado.get("caja"))) {
            assertThat(json.has("cobranzas")).isFalse();
            assertThat(json.has("totalCobranzas")).isFalse();
            assertThat(json.has("efectivoEsperado")).isFalse();
            assertThat(json.has("ventas")).isFalse();
        }
    }

    // ---- CCT3B1-303 (CC-081, CJ-R-014): anulaciones en el Detalle, nunca en la caja abierta del Vendedor ----

    @Test
    @DisplayName("Detalle lleva «anulaciones» por medio (todos, con ceros) y «totalAnulaciones» después de las cobranzas; las cobranzas siguen brutas")
    void detalleExponeLasAnulaciones() throws Exception {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1800.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00")), Map.of(MedioPago.EFECTIVO, bd("700.00"), MedioPago.TRANSFERENCIA, bd("300.00")),
                Map.of());
        DetalleCaja detalle = new DetalleCaja(caja(), EstadoCaja.ABIERTA, resumen, NINGUNA_ANULADA, List.of(),
                Map.of(9L, "Ana"));

        CajaResponses.Detalle respuesta = CajaResponses.Detalle.de(detalle);

        assertThat(respuesta.anulaciones()).containsOnlyKeys(MedioPago.values());
        assertThat(respuesta.anulaciones().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
        assertThat(respuesta.anulaciones().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
        assertThat(respuesta.anulaciones().get(MedioPago.DEBITO)).isEqualByComparingTo("0");
        assertThat(respuesta.totalAnulaciones()).isEqualByComparingTo("1000");
        assertThat(respuesta.cobranzas().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
        assertThat(respuesta.totalCobranzas()).isEqualByComparingTo("700");
        assertThat(respuesta.efectivoEsperado()).isEqualByComparingTo("6800"); // 5000 + 1800 + 700 - 700
        JsonNode json = JSON.valueToTree(respuesta);
        assertThat(json.has("anulaciones")).isTrue();
        assertThat(json.has("totalAnulaciones")).isTrue();
        assertThat(json.get("anulaciones").get("EFECTIVO").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("una caja vieja (sin anulaciones) muestra todas las anulaciones en 0")
    void cajaViejaAnulacionesEnCero() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(), Map.of(), Map.of(), Map.of());
        DetalleCaja detalle = new DetalleCaja(caja(), EstadoCaja.ABIERTA, resumen, NINGUNA_ANULADA, List.of(),
                Map.of(9L, "Ana"));

        CajaResponses.Detalle respuesta = CajaResponses.Detalle.de(detalle);

        assertThat(respuesta.anulaciones().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(respuesta.totalAnulaciones()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la caja abierta y el estado vigente (lo que ve el Vendedor) NO llevan anulaciones: cierre ciego intacto")
    void vigenteYAbiertaSinAnulaciones() throws Exception {
        CajaVigente vigente = new CajaVigente(caja(), EstadoCaja.ABIERTA, "Ana", bd("5000.00"), true);

        JsonNode abierta = JSON.valueToTree(CajaResponses.CajaAbierta.de(vigente));
        JsonNode estado = JSON.valueToTree(CajaResponses.Vigente.de(vigente));

        for (JsonNode json : List.of(abierta, estado, estado.get("caja"))) {
            assertThat(json.has("anulaciones")).isFalse();
            assertThat(json.has("totalAnulaciones")).isFalse();
        }
    }
}
