package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.service.RecalculoPreciosService.VistaPreviaPorCotizacion;
import com.ferreteria.cotizacion.api.DolarPropuesto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Vista previa de «Recalcular ahora» (mejoras-ferreteros-bloque-1, S2): el dolar que se fijaria, el vigente de hoy
 * (null si nunca se fijo uno), si supera el umbral de la ferreteria con su aviso, y cada articulo en dolares con su
 * precio actual, el nuevo y la diferencia (nuevo menos actual). Para confirmar, el POST manda {@code dolar}.
 */
public record VistaPreviaRecalculoResponse(BigDecimal dolar, BigDecimal vigente, boolean superaUmbral, String aviso,
                                           int articulosQueCambian, List<Fila> articulos, List<String> avisos) {

    public record Fila(Long id, String codigoInterno, String descripcion, boolean activo, BigDecimal precioActual,
                       BigDecimal precioNuevo, BigDecimal diferencia) {
    }

    public static VistaPreviaRecalculoResponse de(DolarPropuesto propuesto, VistaPreviaPorCotizacion vista) {
        List<Fila> filas = vista.filas().stream()
                .map(f -> new Fila(f.articuloId(), f.codigoInterno(), f.descripcion(), f.activo(), f.precioActual(),
                        f.precioNuevo(), f.precioNuevo().subtract(f.precioActual())))
                .toList();
        int queCambian = (int) filas.stream().filter(f -> f.diferencia().signum() != 0).count();
        return new VistaPreviaRecalculoResponse(propuesto.valor(), propuesto.vigente(), propuesto.superaUmbral(),
                propuesto.avisoDesvio(), queCambian, filas, vista.avisos());
    }
}
