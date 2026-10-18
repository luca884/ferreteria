package com.ferreteria.clientes.api;

import java.util.Optional;

/**
 * Puerto de clientes hacia ventas (CL-004). Es lo único de clientes que ese módulo puede usar (lo vigila
 * ModulosArchTest). Todo se resuelve con la ferretería del request.
 */
public interface ClientesParaVenta {

    /** El cliente de la ferretería del request con ese id, activo o no. Uno ajeno o inexistente no vuelve. */
    Optional<ClienteReferencia> buscar(Long id);
}
