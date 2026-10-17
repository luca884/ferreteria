package com.ferreteria.catalogo.dto;

/**
 * Grupo de validacion de la edicion de ajustes. No es el grupo por defecto: el controlador no usa {@code @Valid};
 * las valida el servicio DESPUES de buscar el articulo, para que uno ajeno responda 404 antes que un 400 (R-215).
 */
public interface ValidacionAjustes {
}
