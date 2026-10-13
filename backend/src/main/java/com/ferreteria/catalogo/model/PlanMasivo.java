package com.ferreteria.catalogo.model;

import java.util.List;

/**
 * Lo que haria una actualizacion masiva, sin tocar nada. {@code cambios} lista solo los articulos
 * modificados y {@code errores} los que no se pueden actualizar, ambas por id ascendente y completas
 * (el recorte para la vista previa lo hace el servicio). La huella resume la operacion, el filtro y el
 * estado de cada articulo del alcance.
 */
public record PlanMasivo(
        ConteosMasivos conteos,
        List<CambioMasivo> cambios,
        List<ErrorMasivo> errores,
        String huella) {
}
