package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AltaPlaneada;
import com.ferreteria.catalogo.model.AvisoDeFila;
import com.ferreteria.catalogo.model.CambioPlaneado;
import com.ferreteria.catalogo.model.ConteosImportacion;

import java.util.List;

/**
 * Lo que pasaria al aplicar la lista, COMPLETO: los contadores son los del archivo y cada lista trae todas sus filas,
 * cada una con su hoja y su numero, para que la pantalla las pueda ver y elegir una por una. {@code cambios} son los
 * que suben y bajan; los cambios de moneda, las correcciones de IVA o ajustes y los "sin cambio" van en sus listas.
 * Las filas de titulo (salteadas) solo se cuentan. {@code detalleRecortado} quedo por compatibilidad y siempre es false.
 */
public record VistaPreviaResponse(
        ConteosImportacion conteos,
        List<CambioPlaneado> cambios,
        List<AltaPlaneada> nuevos,
        List<AvisoDeFila> errores,
        List<AvisoDeFila> advertencias,
        List<ArticuloAusente> noEstan,
        boolean detalleRecortado,
        List<CambioPlaneado> cambiosDeMoneda,
        List<CambioPlaneado> correcciones,
        List<FilaSinCambio> sinCambio) {}
