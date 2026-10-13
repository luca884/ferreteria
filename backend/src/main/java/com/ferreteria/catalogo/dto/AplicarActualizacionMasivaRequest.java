package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Aplicar una actualizacion masiva: lo mismo que la vista previa (los campos se repiten, como en
 * {@link AplicarRequest}) mas la huella que devolvio. Si al recalcular el plan la huella difiere, no se
 * escribe nada (409).
 */
public record AplicarActualizacionMasivaRequest(
        Long proveedorId,

        Long rubroId,

        String q,

        boolean incluirInactivos,

        @NotNull(message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        @Pattern(regexp = MODOS, message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        String modo,

        @NotNull(message = MENSAJE_VALOR_AUSENTE, groups = ValidacionActualizacionMasiva.class)
        @Digits(integer = 15, fraction = 2, message = MENSAJE_DECIMALES, groups = ValidacionActualizacionMasiva.class)
        BigDecimal valor,

        @NotBlank(message = "Falta la vista previa. Generala de nuevo antes de confirmar.",
                groups = ValidacionActualizacionMasiva.class)
        String huella) implements PedidoMasivo {
}
