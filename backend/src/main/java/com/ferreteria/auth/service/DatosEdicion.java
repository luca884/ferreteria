package com.ferreteria.auth.service;

import com.ferreteria.auth.model.Rol;
import java.math.BigDecimal;

/** Edición de un usuario de la ferretería: la contraseña tiene su propia operación. */
public record DatosEdicion(String nombre, String email, Rol rol, BigDecimal topeDescuento) {}
