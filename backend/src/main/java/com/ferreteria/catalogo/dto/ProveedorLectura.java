package com.ferreteria.catalogo.dto;

/** Lo que devuelve la lectura de un proveedor segun el rol: con la lista para el Dueno, sin ella para el Vendedor (H07). */
public sealed interface ProveedorLectura permits ProveedorResponse, ProveedorVendedorResponse {
}
