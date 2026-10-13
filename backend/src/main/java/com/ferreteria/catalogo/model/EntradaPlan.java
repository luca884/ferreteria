package com.ferreteria.catalogo.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todo lo que necesita el planificador, ya leido: no consulta nada.
 *
 * @param articulosDelProveedor     activos e inactivos del proveedor (una sola consulta)
 * @param contextoPorRubro          contexto de precio de cada rubro de la ferreteria
 * @param contextoSinRubro          contexto de los articulos sin rubro
 * @param codigosInternosOcupados   codigos internos ya usados, normalizados con {@link NormalizadorCodigo#clave}
 * @param mapeoGuardado             mapeo guardado en el proveedor; null si no tiene
 * @param encabezado                fila anterior a la primera de datos; null si no hay
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
        FilaLeida encabezado) {}
