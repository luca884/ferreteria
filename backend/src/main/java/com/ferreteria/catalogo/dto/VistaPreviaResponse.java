package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AltaPlaneada;
import com.ferreteria.catalogo.model.AvisoDeFila;
import com.ferreteria.catalogo.model.CambioPlaneado;
import com.ferreteria.catalogo.model.ConteosImportacion;

import java.util.List;

/**
 * Lo que pasaria al aplicar la lista. Los contadores son siempre los reales; cada lista de detalle se
 * recorta a 1.000 filas y {@code detalleRecortado} avisa si alguna quedo incompleta.
 */
public record VistaPreviaResponse(
        ConteosImportacion conteos,
        List<CambioPlaneado> cambios,
        List<AltaPlaneada> nuevos,
        List<AvisoDeFila> errores,
        List<AvisoDeFila> advertencias,
        List<ArticuloAusente> noEstan,
        boolean detalleRecortado) {}
