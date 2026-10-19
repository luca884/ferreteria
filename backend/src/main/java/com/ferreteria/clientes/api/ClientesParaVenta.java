package com.ferreteria.clientes.api;

import java.util.Optional;

/**
 * Puerto de clientes hacia ventas (CL-004). Es lo único de clientes que ese módulo puede usar (lo vigila
 * ModulosArchTest). Todo se resuelve con la ferretería del request.
 */
public interface ClientesParaVenta {

    /** El cliente de la ferretería del request con ese id, activo o no. Uno ajeno o inexistente no vuelve. */
    Optional<ClienteReferencia> buscar(Long id);

    /**
     * Como {@link #buscar}, pero TOMA la fila del cliente (lock de escritura hasta el fin de la transacción) y lee su estado
     * con una lectura actual: quien lo toma serializa contra cualquier otra venta o desactivación del mismo cliente. Exige
     * una transacción abierta ({@code Propagation.MANDATORY}). Es el primer paso de toda operación que mueve la cuenta del
     * cliente (venta y cobranza); ver el orden de locks en RegistroVenta.
     */
    Optional<ClienteReferencia> tomarParaVenta(Long id);
}
