package com.ferreteria.ventas.web;

import jakarta.validation.constraints.Size;

/**
 * Lo que manda la pantalla para anular el cobro de un recibo (CC-056): el {@code motivo}, texto libre. DELIBERADAMENTE sin
 * {@code @NotBlank}: el motivo ausente, en blanco o corto lo valida el servicio ({@code RegistroAnulacion.validarMotivo}) con un
 * único mensaje y DESPUÉS de recortar, así el 400 es el mismo venga de donde venga y un motivo de 200 caracteres con espacios
 * alrededor vale. El {@code @Size} es solo una red contra cuerpos gigantes (muy por encima del tope de 200 del servicio, que
 * mide el texto recortado); con el mismo mensaje por si alguien lo ve.
 */
public record AnulacionRequest(
        @Size(max = MAXIMO_SIN_RECORTAR, message = "El motivo admite hasta 200 caracteres.")
        String motivo) {

    /** Tope del texto sin recortar: la red del DTO, no la regla del negocio. */
    static final int MAXIMO_SIN_RECORTAR = 1000;
}
