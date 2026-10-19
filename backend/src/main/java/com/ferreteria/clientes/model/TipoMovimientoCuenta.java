package com.ferreteria.clientes.model;

/**
 * Tipos de movimiento del libro de cuenta corriente (CC-001). En el tramo 2 solo se escribe VENTA (debe); COBRO, AJUSTE y
 * NC quedan previstos para cobranzas e imputación. Los nombres caben en movimiento_cuenta_cliente.tipo (VARCHAR(10)).
 */
public enum TipoMovimientoCuenta {
    VENTA,
    COBRO,
    AJUSTE,
    NC
}
