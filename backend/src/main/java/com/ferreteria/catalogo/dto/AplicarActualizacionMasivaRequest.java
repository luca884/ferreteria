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
@PedidoMasivo.ValorSegunElModo(groups = ValidacionActualizacionMasiva.class)
public record AplicarActualizacionMasivaRequest(
        Long proveedorId,

        Long rubroId,

        Long importacionId,

        String q,

        boolean incluirInactivos,

        @NotNull(message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        @Pattern(regexp = MODOS, message = MENSAJE_MODO, groups = ValidacionActualizacionMasiva.class)
        String modo,

        @Digits(integer = 15, fraction = 2, message = MENSAJE_DECIMALES, groups = ValidacionActualizacionMasiva.class)
        BigDecimal valor,

        @NotBlank(message = "Falta la vista previa. Generala de nuevo antes de confirmar.",
                groups = ValidacionActualizacionMasiva.class)
        String huella,

        Long rubroDestinoId) implements PedidoMasivo {

    /** Sin rubro destino: los modos que llevan valor. */
    public AplicarActualizacionMasivaRequest(Long proveedorId, Long rubroId, Long importacionId, String q,
                                             boolean incluirInactivos, String modo, BigDecimal valor, String huella) {
        this(proveedorId, rubroId, importacionId, q, incluirInactivos, modo, valor, huella, null);
    }

    /** Sin filtro por importacion. */
    public AplicarActualizacionMasivaRequest(
            Long proveedorId, Long rubroId, String q, boolean incluirInactivos, String modo, BigDecimal valor,
            String huella) {
        this(proveedorId, rubroId, null, q, incluirInactivos, modo, valor, huella);
    }
}
