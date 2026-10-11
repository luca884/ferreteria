package com.ferreteria.common;

import java.time.Instant;
import java.util.Map;

/** Cuerpo de error uniforme para toda la API. */
public record ApiError(int status, String message, Map<String, String> errors, Instant timestamp) {

    public static ApiError of(int status, String message) {
        return new ApiError(status, message, null, Instant.now());
    }

    public static ApiError of(int status, String message, Map<String, String> errors) {
        return new ApiError(status, message, errors, Instant.now());
    }
}
