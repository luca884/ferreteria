package com.ferreteria.facturacion.motor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Lo que el motor guarda tal cual (pedidos, respuestas, observaciones, errores y verificaciones), como JSON. Mapper propio:
 * el formato guardado no depende de la configuración web. Las fechas van como texto ISO y los importes con su escala.
 */
final class JsonFiscal {

    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private JsonFiscal() {
    }

    static String de(Object valor) {
        try {
            return JSON.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar como JSON lo que va o vuelve de ARCA.", e);
        }
    }
}
