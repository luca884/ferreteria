package com.ferreteria.catalogo.model;

/**
 * Contadores exactos de una actualizacion masiva, sobre el alcance completo.
 * {@code total = suben + bajan + sinCambioDePrecio + conError}.
 *
 * @param pasanAMargenPropio articulos sin error cuyo margen propio es hoy nulo (solo en MARGEN)
 * @param conAdvertencia     articulos sin error con variacion fuerte
 * @param modificados        articulos sin error que cambian el costo, el precio o el margen propio
 */
public record ConteosMasivos(
        int total,
        int suben,
        int bajan,
        int sinCambioDePrecio,
        int conError,
        int pasanAMargenPropio,
        int conAdvertencia,
        int modificados) {
}
