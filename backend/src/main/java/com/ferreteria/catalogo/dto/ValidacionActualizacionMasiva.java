package com.ferreteria.catalogo.dto;

/**
 * Grupo de validacion de la actualizacion masiva. No es el grupo por defecto: el controlador no usa
 * {@code @Valid}; las valida el servicio DESPUES de buscar el proveedor y el rubro, para que un recurso
 * ajeno responda 404 antes que un 400 por un modo o un valor invalido (R-002).
 */
public interface ValidacionActualizacionMasiva {
}
