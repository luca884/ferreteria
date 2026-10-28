package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S1 (D2): el simulador acepta todo lo que dice la tabla de verdad de la letra, {@code facturacion/letra-tabla-de-verdad.json}
 * (la misma que recorre {@code LetraTablaDeVerdadTest}), y rechaza con 10243 lo que el anexo del manual 4.8 no admite para la
 * clase y con 10246 la condición ausente. El simulador no conoce la función de la letra: valida cada fila con el anexo.
 */
class SimuladorWsfeTablaDeVerdadTest extends SimuladorWsfeTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> filas() throws IOException {
        try (InputStream in = SimuladorWsfeTablaDeVerdadTest.class.getResourceAsStream("/facturacion/letra-tabla-de-verdad.json")) {
            assertThat(in).as("facturacion/letra-tabla-de-verdad.json en el classpath de test").isNotNull();
            List<JsonNode> filas = new ArrayList<>();
            JSON.readTree(in).get("filas").forEach(filas::add);
            return filas;
        }
    }

    /** El documento con que va cada receptor de la tabla: la venta sin cliente, sin identificar; el resto, con el suyo. */
    private static PedidoDePrueba conReceptor(PedidoDePrueba pedido, String receptor) {
        return switch (receptor) {
            case "SIN_CLIENTE" -> pedido.documento(99, 0);
            case "CONSUMIDOR_FINAL" -> pedido.documento(96, DNI);
            default -> pedido.documento(80, CUIT_RECEPTOR);
        };
    }

    @Test
    @DisplayName("cada una de las 15 filas se autoriza: la factura (CbteTipo 1, 6 u 11) y su nota de crédito (3, 8 o 13)")
    void cadaFilaDeLaTablaSeAutoriza() throws IOException {
        List<JsonNode> filas = filas();
        assertThat(filas).hasSize(15);

        for (JsonNode fila : filas) {
            String caso = fila.get("emisor").asText() + " a " + fila.get("receptor").asText();
            Letra letra = Letra.valueOf(fila.get("letra").asText());
            int factura = fila.get("cbteTipoFactura").asInt();
            int notaCredito = fila.get("cbteTipoNotaCredito").asInt();
            int condicion = fila.get("condicionIvaReceptorId").asInt();
            DesgloseFiscal desglose = DesgloseFiscal.de(letra,
                    List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))));

            long numeroFactura = ultimo(factura) + 1;
            FeCaeResponse respuestaFactura = solicitar(conReceptor(facturaB(), fila.get("receptor").asText())
                    .tipo(factura).numero(numeroFactura).condicion(condicion).desglose(desglose));
            FeCaeResponse respuestaNota = solicitar(conReceptor(facturaB(), fila.get("receptor").asText())
                    .tipo(notaCredito).numero(ultimo(notaCredito) + 1).condicion(condicion).desglose(desglose)
                    .asociados(new CbteAsoc(factura, 1, numeroFactura, null, null)));

            assertThat(respuestaFactura.errors()).as("Errors de la factura de %s", caso).isEmpty();
            assertThat(respuestaNota.errors()).as("Errors de la NC de %s", caso).isEmpty();
            aprobado(respuestaFactura);
            aprobado(respuestaNota);
        }
    }

    @ParameterizedTest(name = "CbteTipo {0} con CondicionIVAReceptorId {1}")
    @CsvSource({"1, 4", "1, 5", "6, 1", "6, 6", "3, 5", "8, 6"})
    @DisplayName("10243: la condición del receptor que el anexo no admite para la clase (A: 1, 6, 13, 16; B: 4, 5, 7, 8, 9, 10, 15)")
    void condicionQueLaClaseNoAdmite(int cbteTipo, int condicion) {
        int docTipo = condicion == 5 ? 96 : 80;
        long docNro = condicion == 5 ? DNI : CUIT_RECEPTOR;

        FeCaeResponse respuesta = solicitar(facturaB().tipo(cbteTipo).documento(docTipo, docNro).condicion(condicion)
                .periodo(new PeriodoAsoc(HOY.withDayOfMonth(1), HOY)));

        rechazadoCon(respuesta, 10243);
        assertThat(ultimo(cbteTipo)).isZero();
    }

    @ParameterizedTest(name = "CbteTipo {0}")
    @ValueSource(ints = {1, 6, 11})
    @DisplayName("10246: sin CondicionIVAReceptorId se rechaza, en la A, en la B y en la C")
    void condicionAusente(int cbteTipo) {
        PedidoDePrueba pedido = facturaB().tipo(cbteTipo).condicion(null);
        if (cbteTipo == 1) {
            pedido.documento(80, CUIT_RECEPTOR);
        }
        if (cbteTipo == 11) {
            pedido.importes("121.00", "121.00", "0.00").alicuotas();
        }

        rechazadoCon(solicitar(pedido), 10246);
        assertThat(ultimo(cbteTipo)).isZero();
    }
}
