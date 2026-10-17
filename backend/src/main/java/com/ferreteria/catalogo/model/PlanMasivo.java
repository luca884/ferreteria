package com.ferreteria.catalogo.model;

import java.util.List;

/**
 * Lo que haria una actualizacion masiva, sin tocar nada. {@code cambios} lista solo los articulos
 * modificados y {@code errores} los que no se pueden actualizar, ambas por id ascendente y completas
 * (el recorte para la vista previa lo hace el servicio). La huella resume la operacion, el filtro y el
 * estado de cada articulo del alcance.
 *
 * <p>{@code enPesos} y {@code enDolares} cuentan todo el alcance, con o sin error. {@code advertenciaMezcla} es
 * null salvo que el modo sea {@code COSTO_MONTO} y el alcance tenga las dos monedas. Viven aca y no en
 * {@link ConteosMasivos} para no cambiar sus ocho campos.
 */
public record PlanMasivo(
        ConteosMasivos conteos,
        List<CambioMasivo> cambios,
        List<ErrorMasivo> errores,
        String huella,
        int enPesos,
        int enDolares,
        String advertenciaMezcla) {

    /** Un plan sin datos de moneda: todo el alcance cuenta como pesos y sin advertencia. */
    public PlanMasivo(ConteosMasivos conteos, List<CambioMasivo> cambios, List<ErrorMasivo> errores, String huella) {
        this(conteos, cambios, errores, huella, conteos.total(), 0, null);
    }
}
