package com.ferreteria.facturacion.documento;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * El QR de ARCA (cu-07-factura-mostrador S7 y S14), contra la especificación del 03/02/2025 (versión 1): el texto es
 * {@code https://www.arca.gob.ar/fe/qr/?p=} con el JSON en Base64 estándar y con relleno, y la matriz (corrección M, sin zona
 * de silencio) la lee el core de ZXing y da ese mismo texto.
 */
class QrArcaTest {

    /** Los campos del JSON v1, en el orden de la especificación. */
    private static final List<String> CAMPOS = List.of("ver", "fecha", "cuit", "ptoVta", "tipoCmp", "nroCmp", "importe",
            "moneda", "ctz", "tipoDocRec", "nroDocRec", "tipoCodAut", "codAut");

    static Stream<Arguments> comprobantes() {
        return Stream.of(
                Arguments.of("Factura B a Consumidor Final sin identificar (99 y 0, S14)",
                        new QrArca.Datos(LocalDate.of(2026, 10, 28), 30712345678L, 1, 6, 12, new BigDecimal("1210.00"), 99,
                                0, 70417054367476L)),
                Arguments.of("Factura A a un Responsable Inscripto con CUIT, punto de venta y número grandes",
                        new QrArca.Datos(LocalDate.of(2026, 1, 5), 30712345678L, 99999, 1, 99999999,
                                new BigDecimal("229.19"), 80, 30786013301L, 70417054367499L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("comprobantes")
    @DisplayName("el QR lleva la URL de ARCA con el JSON v1 en Base64, y ZXing lee la matriz con corrección M")
    void elQrEsElDeLaEspecificacion(String caso, QrArca.Datos datos) {
        String texto = QrArca.texto(datos);
        List<String> modulos = QrArca.modulos(texto);

        // La URL sale del texto de la especificación (www.arca.gob.ar), no de su ejemplo (afip.gob.ar).
        assertThat(texto).startsWith("https://www.arca.gob.ar/fe/qr/?p=").isEqualTo(QrArca.URL + LectorQr.parametro(texto));
        // Base64 estándar con relleno: alfabeto con + y /, y el largo múltiplo de 4.
        assertThat(LectorQr.parametro(texto)).matches("[A-Za-z0-9+/]+={0,2}");
        assertThat(LectorQr.parametro(texto).length() % 4).isZero();

        JsonNode json = LectorQr.datos(texto);
        List<String> campos = new ArrayList<>();
        json.fieldNames().forEachRemaining(campos::add);
        assertThat(campos).containsExactlyElementsOf(CAMPOS);
        assertThat(json.get("ver").isIntegralNumber()).isTrue();
        assertThat(json.get("ver").asInt()).isEqualTo(1);
        assertThat(json.get("fecha").isTextual()).isTrue();
        assertThat(json.get("fecha").asText()).isEqualTo(datos.fecha().toString());
        assertThat(json.get("cuit").isIntegralNumber()).isTrue();
        assertThat(json.get("cuit").asLong()).isEqualTo(datos.cuit());
        assertThat(json.get("ptoVta").isIntegralNumber()).isTrue();
        assertThat(json.get("ptoVta").asInt()).isEqualTo(datos.ptoVta());
        assertThat(json.get("tipoCmp").isIntegralNumber()).isTrue();
        assertThat(json.get("tipoCmp").asInt()).isEqualTo(datos.tipoCmp());
        assertThat(json.get("nroCmp").isIntegralNumber()).isTrue();
        assertThat(json.get("nroCmp").asLong()).isEqualTo(datos.nroCmp());
        assertThat(json.get("importe").isNumber()).isTrue();
        assertThat(json.get("importe").decimalValue()).isEqualByComparingTo(datos.importe());
        assertThat(json.get("importe").decimalValue().scale()).isLessThanOrEqualTo(2);
        assertThat(json.get("moneda").asText()).isEqualTo("PES");
        assertThat(json.get("ctz").isNumber()).isTrue();
        assertThat(json.get("ctz").decimalValue()).isEqualByComparingTo("1");
        assertThat(json.get("tipoDocRec").isIntegralNumber()).isTrue();
        assertThat(json.get("tipoDocRec").asInt()).isEqualTo(datos.tipoDocRec());
        assertThat(json.get("nroDocRec").isIntegralNumber()).isTrue();
        assertThat(json.get("nroDocRec").asLong()).isEqualTo(datos.nroDocRec());
        assertThat(json.get("tipoCodAut").asText()).isEqualTo("E");
        assertThat(json.get("codAut").isIntegralNumber()).isTrue();
        assertThat(json.get("codAut").asText()).hasSize(14);
        assertThat(json.get("codAut").asLong()).isEqualTo(datos.codAut());

        // La matriz: cuadrada, de '1' y '0', sin zona de silencio (arranca con el patrón de posición, oscuro).
        assertThat(modulos).isNotEmpty();
        assertThat(modulos).allSatisfy(fila -> assertThat(fila).hasSize(modulos.size()).matches("[01]+"));
        assertThat(modulos.get(0)).startsWith("1111111").endsWith("1111111");
        assertThat(modulos.get(modulos.size() - 1)).startsWith("1111111");
        LectorQr.Lectura lectura = LectorQr.leer(modulos);
        assertThat(lectura.texto()).isEqualTo(texto);
        assertThat(lectura.nivelDeCorreccion()).isEqualTo("M");
    }
}
