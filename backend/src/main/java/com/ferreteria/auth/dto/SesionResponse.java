package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import java.math.BigDecimal;

/**
 * Lo que el frontend necesita para mostrar la sesion: quien sos y en que ferreteria estas. {@code topeDescuento} (CU-07)
 * es null si el usuario no tiene tope; el mostrador lo usa para avisar antes de confirmar. {@code debeCambiarContrasena}
 * es true mientras su contraseña la haya elegido otra persona: el frontend lo lleva a «Mi contraseña» antes que a cualquier
 * otra pantalla. {@code cuentaDemo} indica que no se pueden cambiar sus credenciales.
 */
public record SesionResponse(Long userId, String nombre, String email, Rol rol,
                             Long ferreteriaId, String ferreteriaNombre, BigDecimal topeDescuento,
                             boolean debeCambiarContrasena, boolean cuentaDemo) {
}
