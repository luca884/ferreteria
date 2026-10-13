package com.ferreteria.catalogo.model;

import java.util.List;

/**
 * Resultado del planificador. Cada fila de datos cae en exactamente una de {@code cambios} (suben o
 * bajan), {@code sinCambio}, {@code altas} o {@code errores}; las advertencias no son una categoria.
 */
public record PlanImportacion(
        ConteosImportacion conteos,
        List<CambioPlaneado> cambios,
        List<AltaPlaneada> altas,
        List<PrecioListaPlaneado> sinCambio,
        List<AvisoDeFila> errores,
        List<AvisoDeFila> advertencias,
        List<ArticuloVigente> noEstan) {}
