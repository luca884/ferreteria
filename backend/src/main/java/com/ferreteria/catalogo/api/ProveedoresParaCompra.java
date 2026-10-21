package com.ferreteria.catalogo.api;

import java.util.Optional;

/** Puerto de catalogo hacia compras (CU-13) para el proveedor de la compra. Todo con la ferreteria del request. */
public interface ProveedoresParaCompra {

    /** El proveedor de la ferreteria del request, activo o no. Vacio si es de otra ferreteria o no existe. */
    Optional<ProveedorDeCompra> buscar(Long id);

    /** Lo que compras necesita del proveedor: el nombre se copia en la compra. */
    record ProveedorDeCompra(Long id, String nombre, boolean activo) {
    }
}
