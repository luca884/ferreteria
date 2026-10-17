package com.ferreteria.catalogo.dto;

/** Lo que devuelve la lectura de un rubro segun el rol: con margen para el Dueno, sin margen para el Vendedor (H07). */
public sealed interface RubroLectura permits RubroResponse, RubroVendedorResponse {
}
