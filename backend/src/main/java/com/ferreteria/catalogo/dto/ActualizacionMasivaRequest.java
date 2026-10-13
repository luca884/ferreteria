package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * La vista previa de una actualizacion masiva: el filtro (todo opcional; vacio alcanza a todo el
 * catalogo activo), el modo ({@code COSTO_PORCENTAJE} o {@code MARGEN}) y su valor. Las reglas van en el
 * grupo {@link ValidacionActualizacionMasiva}, que valida el servicio despues de los 404.
 */
public record ActualizacionMasivaRequest(
        Long proveedorId,

        Long rubroId,

        String q,

        boolean incluirInactivos,

        @NotNull(message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        @Pattern(regexp = MODOS, message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        String modo,

        @NotNull(message = MENSAJE_VALOR_AUSENTE, groups = ValidacionActualizacionMasiva.class)
        @Digits(integer = 15, fraction = 2, message = MENSAJE_DECIMALES, groups = ValidacionActualizacionMasiva.class)
        BigDecimal valor) implements PedidoMasivo {
}
