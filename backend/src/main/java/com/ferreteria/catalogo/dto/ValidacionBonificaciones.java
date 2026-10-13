package com.ferreteria.catalogo.dto;

/**
 * Grupo de validacion de las bonificaciones de la importacion. No es el grupo por defecto: el
 * {@code @Valid} del controlador no las valida, las valida el servicio DESPUES de buscar el proveedor
 * (y el rubro), para que un recurso ajeno responda 404 antes que un 400 por una bonificacion invalida.
 */
public interface ValidacionBonificaciones {
}
