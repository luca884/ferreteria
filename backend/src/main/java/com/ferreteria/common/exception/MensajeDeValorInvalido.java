package com.ferreteria.common.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mensaje para un valor que no es de esta enumeracion en el cuerpo de un pedido. Gana sobre el mensaje por nombre de
 * campo de {@link GlobalExceptionHandler}: dos enums con un campo del mismo nombre (el {@code tipo} de una compra y
 * el de un movimiento de caja) no comparten mensaje, y {@code common} no depende del modulo del enum.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MensajeDeValorInvalido {

    String value();
}
