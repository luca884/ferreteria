package com.ferreteria.catalogo.api;

import java.util.List;
import java.util.Optional;

/** Puerto de catalogo hacia compras (CU-13) para el proveedor de la compra. Todo con la ferreteria del request. */
public interface ProveedoresParaCompra {

    /** El proveedor de la ferreteria del request, activo o no. Vacio si es de otra ferreteria o no existe. */
    Optional<ProveedorDeCompra> buscar(Long id);

    /**
     * El primero de {@code cuits} (11 digitos, sin guiones, en ese orden) que es el CUIT de un proveedor activo de la
     * ferreteria del request. El CUIT de la propia ferreteria no cuenta: es el comprador de la factura, no el proveedor.
     */
    Optional<ProveedorDeCompra> buscarPorCuit(List<String> cuits);

    /** Lo que compras necesita del proveedor: el nombre se copia en la compra. */
    record ProveedorDeCompra(Long id, String nombre, boolean activo) {
    }
}
