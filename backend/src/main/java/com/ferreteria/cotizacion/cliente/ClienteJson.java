package com.ferreteria.cotizacion.cliente;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Lo que comparten los adaptadores de las fuentes: pedir un JSON con timeouts y leerlo con los importes como
 * {@link BigDecimal} exacto (nunca pasan por double). Cualquier problema de la respuesta (red, timeout, 4xx o 5xx,
 * cuerpo vacio, JSON invalido) sale como {@link FuenteNoDisponibleException}.
 */
final class ClienteJson {

    static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(3);
    static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final RestClient cliente;

    ClienteJson(RestClient cliente) {
        this.cliente = cliente;
    }

    static ClienteJson de(String urlBase, Duration conexion, Duration lectura) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(conexion);
        fabrica.setReadTimeout(lectura);
        return new ClienteJson(RestClient.builder().baseUrl(urlBase).requestFactory(fabrica).build());
    }

    JsonNode leer(String ruta) {
        String cuerpo;
        try {
            cuerpo = cliente.get().uri(ruta).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new FuenteNoDisponibleException("No respondio " + ruta + ": " + e.getMessage(), e);
        }
        if (cuerpo == null || cuerpo.isBlank()) {
            throw new FuenteNoDisponibleException("Respuesta vacia en " + ruta);
        }
        try {
            return MAPPER.readTree(cuerpo);
        } catch (JsonProcessingException e) {
            throw new FuenteNoDisponibleException("JSON invalido en " + ruta, e);
        }
    }

    /** Un importe positivo; null si falta, no es un numero o no es mayor que cero. */
    static BigDecimal importe(JsonNode nodo) {
        if (nodo == null || !nodo.isNumber()) {
            return null;
        }
        BigDecimal valor = nodo.decimalValue();
        return valor.signum() > 0 ? valor : null;
    }

    /** El texto de un campo; null si falta o no es texto. */
    static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor != null && valor.isTextual() ? valor.asText() : null;
    }

    /** Una fecha con zona (ISO 8601) como instante; null si falta o no se entiende. */
    static Instant instante(JsonNode nodo, String campo) {
        String texto = texto(nodo, campo);
        if (texto == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
