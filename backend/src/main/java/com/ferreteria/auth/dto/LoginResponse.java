package com.ferreteria.auth.dto;

public record LoginResponse(String token, SesionResponse sesion) {
}
