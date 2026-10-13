package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Los contextos de precio de una ferreteria, ya armados: uno por rubro y otro para los articulos
 * sin rubro. Los usan la importacion de listas (CU-04) y la actualizacion masiva (CU-05). Recibe los
 * valores de la configuracion en vez de la configuracion misma para que este paquete no dependa del
 * modulo ferreteria.
 *
 * @param porRubro  contexto de cada rubro, por id; inmutable
 * @param sinRubro  contexto de los articulos sin rubro
 */
public record ContextosDePrecio(Map<Long, ContextoPrecio> porRubro, ContextoPrecio sinRubro) {

    public ContextosDePrecio {
        porRubro = Map.copyOf(porRubro);
    }

    public static ContextosDePrecio armar(List<Rubro> rubros, BigDecimal margenDefecto, BigDecimal redondeoMultiplo) {
        Map<Long, ContextoPrecio> porRubro = new HashMap<>();
        for (Rubro rubro : rubros) {
            porRubro.put(rubro.getId(), new ContextoPrecio(rubro.getMargenPorcentaje(), margenDefecto, redondeoMultiplo));
        }
        return new ContextosDePrecio(porRubro, new ContextoPrecio(null, margenDefecto, redondeoMultiplo));
    }

    /** El contexto del rubro, o el de los articulos sin rubro si {@code rubroId} es null. */
    public ContextoPrecio de(Long rubroId) {
        if (rubroId == null) {
            return sinRubro;
        }
        ContextoPrecio contexto = porRubro.get(rubroId);
        if (contexto == null) {
            throw new IllegalStateException("Falta el contexto de precio del rubro " + rubroId);
        }
        return contexto;
    }
}
