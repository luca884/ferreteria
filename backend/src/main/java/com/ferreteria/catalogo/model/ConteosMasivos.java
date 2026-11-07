package com.ferreteria.catalogo.model;

/**
 * Contadores exactos de una actualizacion masiva, sobre el alcance completo.
 * {@code total = suben + bajan + sinCambioDePrecio + conError}.
 *
 * @param pasanAMargenPropio articulos sin error cuyo margen propio es hoy nulo (solo en MARGEN)
 * @param conAdvertencia     articulos sin error con variacion fuerte
 * @param modificados        articulos sin error que cambian el costo, el precio o el margen propio
 * @param yaEnElDestino      articulos que ya estan en el rubro destino (solo en CAMBIAR_RUBRO; tambien cuentan en
 *                           sinCambioDePrecio, y la pantalla los muestra aparte de los que solo cambian de rubro)
 */
public record ConteosMasivos(
        int total,
        int suben,
        int bajan,
        int sinCambioDePrecio,
        int conError,
        int pasanAMargenPropio,
        int conAdvertencia,
        int modificados,
        int yaEnElDestino) {
}
