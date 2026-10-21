package com.ferreteria.clientes.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ferreteria.clientes.model.TipoDocumento;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.service.ExtractoCuenta.Extracto;
import com.ferreteria.clientes.service.ExtractoCuenta.Linea;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CCT3B1-504 (CC-073, CC-077, CC-078): la forma JSON del extracto (contrato plano del design §6), sin Spring. */
class ExtractoResponseTest {

    /** Como Spring: fechas ISO (no arreglos) y los nulos presentes. */
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static Linea anulacion() {
        return new Linea(91L, LocalDateTime.of(2026, 10, 20, 10, 11, 12), TipoMovimientoCuenta.ANULACION, "Anulación Recibo N.º 14",
                bd("500"), bd("0"), bd("1200"), "Ana", 14L, null, "Cliente equivocado", false, null, false);
    }

    private static Linea ventaConExceso() {
        return new Linea(80L, LocalDateTime.of(2026, 10, 1, 8, 0), TipoMovimientoCuenta.VENTA, "Venta N.º 33", bd("1000.5"),
                bd("0"), bd("700"), "Luis", null, 33L, null, true, "Marta", false);
    }

    private static Extracto extracto(List<Linea> lineas, boolean truncado) {
        return new Extracto(7L, "Ferretería Don Pepe", TipoDocumento.CUIT, "30712345671", bd("1200"), LocalDate.of(2026, 10, 1), null, null, bd("300"),
                bd("1500"), bd("600"), bd("1200"), lineas, 0, 50, 37, 1, truncado);
    }

    private static List<String> claves(JsonNode nodo) {
        List<String> claves = new ArrayList<>();
        for (Iterator<String> it = nodo.fieldNames(); it.hasNext();) {
            claves.add(it.next());
        }
        return claves;
    }

    @Test
    @DisplayName("el encabezado tiene exactamente las claves del contrato, en orden, y movimientos es un PageResponse (items, page, size, totalElements, totalPages)")
    void clavesExactas() {
        JsonNode json = JSON.valueToTree(ExtractoResponse.de(extracto(List.of(anulacion()), false)));

        assertThat(claves(json)).containsExactly("clienteId", "clienteNombre", "tipoDocumento", "numeroDocumento", "saldoActual", "desde", "hasta", "tipo", "saldoInicial",
                "totalDebe", "totalHaber", "saldoFinal", "movimientos", "truncado");
        assertThat(claves(json.get("movimientos"))).containsExactly("items", "page", "size", "totalElements", "totalPages");
        assertThat(claves(json.get("movimientos").get("items").get(0))).containsExactly("id", "fecha", "tipo", "referencia", "debe",
                "haber", "saldo", "usuario", "reciboId", "comprobanteId", "motivo", "excedioLimite", "autorizadoPor", "anulado");
        assertThat(json.get("clienteId").asLong()).isEqualTo(7L);
        assertThat(json.get("clienteNombre").asText()).isEqualTo("Ferretería Don Pepe");
        assertThat(json.get("tipoDocumento").asText()).isEqualTo("CUIT");
        assertThat(json.get("numeroDocumento").asText()).isEqualTo("30712345671");
    }

    @Test
    @DisplayName("importes con escala 2 (aunque el servicio los entregue con otra), debe y haber siempre con 2 decimales y uno de los dos en 0")
    void escalaDos() throws Exception {
        String texto = JSON.writeValueAsString(ExtractoResponse.de(extracto(List.of(ventaConExceso(), anulacion()), false)));
        ExtractoResponse respuesta = ExtractoResponse.de(extracto(List.of(ventaConExceso()), false));

        assertThat(texto).contains("\"saldoActual\":1200.00", "\"saldoInicial\":300.00", "\"totalDebe\":1500.00", "\"totalHaber\":600.00",
                "\"saldoFinal\":1200.00", "\"debe\":1000.50", "\"haber\":0.00", "\"saldo\":700.00", "\"debe\":500.00");
        LineaExtractoResponse linea = respuesta.movimientos().items().get(0);
        assertThat(linea.debe().scale()).isEqualTo(2);
        assertThat(linea.haber().scale()).isEqualTo(2);
        assertThat(linea.saldo().scale()).isEqualTo(2);
        assertThat(linea.haber().signum()).as("uno de los dos es 0").isZero();
    }

    @Test
    @DisplayName("fechas ISO (yyyy-MM-dd y yyyy-MM-ddTHH:mm:ss), tipo por nombre, anulado y excedioLimite booleanos, nulos donde no corresponde")
    void formasYNulos() {
        JsonNode json = JSON.valueToTree(ExtractoResponse.de(extracto(List.of(anulacion(), ventaConExceso()), false)));
        JsonNode anulacion = json.get("movimientos").get("items").get(0);
        JsonNode venta = json.get("movimientos").get("items").get(1);

        assertThat(json.get("desde").asText()).isEqualTo("2026-10-01");
        assertThat(json.get("hasta").isNull()).as("sin hasta: la clave está y vale null").isTrue();
        assertThat(json.get("tipo").isNull()).isTrue();
        assertThat(anulacion.get("fecha").asText()).isEqualTo("2026-10-20T10:11:12");
        assertThat(anulacion.get("tipo").asText()).isEqualTo("ANULACION");
        assertThat(anulacion.get("reciboId").asLong()).isEqualTo(14L);
        assertThat(anulacion.get("comprobanteId").isNull()).isTrue();
        assertThat(anulacion.get("motivo").asText()).isEqualTo("Cliente equivocado");
        assertThat(anulacion.get("excedioLimite").isBoolean()).isTrue();
        assertThat(anulacion.get("excedioLimite").asBoolean()).isFalse();
        assertThat(anulacion.get("autorizadoPor").isNull()).isTrue();
        assertThat(anulacion.get("anulado").isBoolean()).isTrue();
        assertThat(venta.get("excedioLimite").asBoolean()).isTrue();
        assertThat(venta.get("autorizadoPor").asText()).isEqualTo("Marta");
        assertThat(venta.get("comprobanteId").asLong()).isEqualTo(33L);
        assertThat(venta.get("reciboId").isNull()).isTrue();
        assertThat(venta.get("motivo").isNull()).isTrue();
    }

    @Test
    @DisplayName("el tipo filtrado, el rango y el truncado se devuelven tal cual; el COBRO anulado sale con anulado = true")
    void tipoTruncadoYAnulado() {
        Linea cobro = new Linea(5L, LocalDateTime.of(2026, 10, 2, 9, 0), TipoMovimientoCuenta.COBRO, "Recibo N.º 14", bd("0"),
                bd("400"), bd("600"), "Ana", 14L, null, null, false, null, true);
        Extracto x = new Extracto(7L, "Cliente", null, null, bd("600"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                TipoMovimientoCuenta.COBRO, bd("0"), bd("0"), bd("400"), bd("600"), List.of(cobro), 0, 2000, 1, 1, true);

        JsonNode json = JSON.valueToTree(ExtractoResponse.de(x));

        assertThat(json.get("tipoDocumento").isNull()).as("sin documento: las claves están y valen null").isTrue();
        assertThat(json.get("numeroDocumento").isNull()).isTrue();
        assertThat(json.get("tipo").asText()).isEqualTo("COBRO");
        assertThat(json.get("hasta").asText()).isEqualTo("2026-10-31");
        assertThat(json.get("truncado").asBoolean()).isTrue();
        assertThat(json.get("movimientos").get("items").get(0).get("anulado").asBoolean()).isTrue();
        assertThat(json.get("movimientos").get("items").get(0).get("haber").decimalValue()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("PageResponse: page, size, totalElements y totalPages de la paginación del servicio; sin líneas, items vacío")
    void paginacion() {
        Extracto x = new Extracto(7L, "Cliente", null, null, bd("0"), null, null, null, bd("0"), bd("0"), bd("0"), bd("0"), List.of(), 2, 10, 45, 5,
                false);

        JsonNode pagina = JSON.valueToTree(ExtractoResponse.de(x)).get("movimientos");

        assertThat(pagina.get("items").isArray()).isTrue();
        assertThat(pagina.get("items")).isEmpty();
        assertThat(pagina.get("page").asInt()).isEqualTo(2);
        assertThat(pagina.get("size").asInt()).isEqualTo(10);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(45);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(5);
    }
}
