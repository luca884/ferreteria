package com.ferreteria.catalogo.model;

import java.util.Optional;

/**
 * Resultado de un recalculo automatico del precio de un articulo (cascada del margen del rubro, cotizacion, compras):
 * el cambio de costo/precio si hubo alguno y, si los ajustes sobre el precio final lo habrian dejado en 0 o menos, el
 * aviso de que el articulo conserva su precio actual (S7). Con aviso, el precio no cambio; el costo si pudo cambiar.
 */
public record RecalculoAutomatico(Optional<CambioPrecio> cambio, Optional<String> aviso) {

    static RecalculoAutomatico de(Optional<CambioPrecio> cambio) {
        return new RecalculoAutomatico(cambio, Optional.empty());
    }
}
