package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * La vista previa de una actualizacion masiva: el filtro (todo opcional; vacio alcanza a todo el
 * catalogo activo), el modo ({@code COSTO_PORCENTAJE}, {@code COSTO_MONTO} o {@code MARGEN}) y su valor. Las reglas van en el
 * grupo {@link ValidacionActualizacionMasiva}, que valida el servicio despues de los 404. Con {@code CAMBIAR_RUBRO} no va
 * el valor y va {@code rubroDestinoId}, que en los otros modos no va.
 */
@PedidoMasivo.ValorSegunElModo(groups = ValidacionActualizacionMasiva.class)
public record ActualizacionMasivaRequest(
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

        Long rubroDestinoId) implements PedidoMasivo {

    /** Sin rubro destino: los modos que llevan valor. */
    public ActualizacionMasivaRequest(Long proveedorId, Long rubroId, Long importacionId, String q,
                                      boolean incluirInactivos, String modo, BigDecimal valor) {
        this(proveedorId, rubroId, importacionId, q, incluirInactivos, modo, valor, null);
    }

    /** Sin filtro por importacion. */
    public ActualizacionMasivaRequest(
            Long proveedorId, Long rubroId, String q, boolean incluirInactivos, String modo, BigDecimal valor) {
        this(proveedorId, rubroId, null, q, incluirInactivos, modo, valor);
    }
}
