package com.ferreteria.catalogo.model;

import java.util.List;

/**
 * Resultado del planificador. Cada fila de datos cae en exactamente una de {@code cambios} (suben o bajan),
 * {@code cambiosDeMoneda}, {@code correcciones}, {@code sinCambio}, {@code altas} o {@code errores}; las
 * advertencias no son una categoria. Las filas de titulo se cuentan en {@code salteadas} y no tienen detalle.
 */
public record PlanImportacion(
        ConteosImportacion conteos,
        List<CambioPlaneado> cambios,
        List<AltaPlaneada> altas,
        List<PrecioListaPlaneado> sinCambio,
        List<AvisoDeFila> errores,
        List<AvisoDeFila> advertencias,
        List<ArticuloVigente> noEstan,
        List<CambioPlaneado> cambiosDeMoneda,
        List<CambioPlaneado> correcciones,
        int salteadas) {

    /** El plan de antes del corte 2: sin cambios de moneda, correcciones ni salteadas. */
    public PlanImportacion(
            ConteosImportacion conteos,
            List<CambioPlaneado> cambios,
            List<AltaPlaneada> altas,
            List<PrecioListaPlaneado> sinCambio,
            List<AvisoDeFila> errores,
            List<AvisoDeFila> advertencias,
            List<ArticuloVigente> noEstan) {
        this(conteos, cambios, altas, sinCambio, errores, advertencias, noEstan, List.of(), List.of(), 0);
    }

    /** Los que suben: el costo nuevo es mayor, en la misma moneda. */
    public List<CambioPlaneado> suben() {
        return cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) > 0).toList();
    }

    /** Los que bajan: el costo nuevo es menor, en la misma moneda. */
    public List<CambioPlaneado> bajan() {
        return cambios.stream().filter(c -> c.costoNuevo().compareTo(c.costoAnterior()) < 0).toList();
    }
}
