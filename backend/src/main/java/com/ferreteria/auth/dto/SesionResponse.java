package com.ferreteria.auth.dto;

import com.ferreteria.auth.model.Rol;
import java.math.BigDecimal;

/**
 * Lo que el frontend necesita para mostrar la sesion: quien sos y en que ferreteria estas. {@code topeDescuento} (CU-07)
 * es null si el usuario no tiene tope; el mostrador lo usa para avisar antes de confirmar.
 */
public record SesionResponse(Long userId, String nombre, String email, Rol rol,
                             Long ferreteriaId, String ferreteriaNombre, BigDecimal topeDescuento) {
}
