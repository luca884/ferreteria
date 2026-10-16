package com.ferreteria.catalogo.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todo lo que necesita el planificador, ya leido: no consulta nada.
 *
 * @param filas                     todas las filas leidas del libro, con su hoja (la zona de datos la decide
 *                                  {@link ZonaDeDatos})
 * @param articulosDelProveedor     activos e inactivos del proveedor (una sola consulta)
 * @param contextoPorRubro          contexto de precio de cada rubro de la ferreteria
 * @param contextoSinRubro          contexto de los articulos sin rubro; trae el dolar vigente (null si no hay ninguno)
 * @param codigosInternosOcupados   codigos internos ya usados, normalizados con {@link NormalizadorCodigo#clave}
 * @param mapeoGuardado             mapeo guardado en el proveedor; null si no tiene
 * @param encabezado                fila anterior a la primera de datos de la hoja de referencia; null si no hay
 * @param hojas                     nombres de las hojas del libro, por posicion; vacio si no se conocen (los avisos
 *                                  no llevan hoja)
 * @param cotizacionActiva          si la cotizacion de la ferreteria esta activada: apagada, no se cargan articulos en
 *                                  dolares ni se pasa uno de pesos a dolares
 * @param rubrosPorNombre           id de cada rubro de la ferreteria por su nombre, con {@link NormalizadorCodigo#clave}
 * @param codigosBarrasOcupados     codigos de barras de los articulos activos de la ferreteria
 */
public record EntradaPlan(
        List<FilaLeida> filas,
        MapeoLista mapeo,
        boolean listaIncluyeIva,
        Bonificaciones bonificaciones,
        List<ArticuloVigente> articulosDelProveedor,
        Map<Long, ContextoPrecio> contextoPorRubro,
        ContextoPrecio contextoSinRubro,
        Set<String> codigosInternosOcupados,
        MapeoLista mapeoGuardado,
        FilaLeida encabezado,
        List<String> hojas,
        boolean cotizacionActiva,
        Map<String, Long> rubrosPorNombre,
        Set<String> codigosBarrasOcupados) {

    /** La entrada de antes del corte 2: sin nombres de hoja, cotizacion activa, sin rubros por nombre ni barras. */
    public EntradaPlan(
            List<FilaLeida> filas,
            MapeoLista mapeo,
            boolean listaIncluyeIva,
            Bonificaciones bonificaciones,
            List<ArticuloVigente> articulosDelProveedor,
            Map<Long, ContextoPrecio> contextoPorRubro,
            ContextoPrecio contextoSinRubro,
            Set<String> codigosInternosOcupados,
            MapeoLista mapeoGuardado,
            FilaLeida encabezado) {
        this(filas, mapeo, listaIncluyeIva, bonificaciones, articulosDelProveedor, contextoPorRubro, contextoSinRubro,
            codigosInternosOcupados, mapeoGuardado, encabezado, List.of(), true, Map.of(), Set.of());
    }
}
