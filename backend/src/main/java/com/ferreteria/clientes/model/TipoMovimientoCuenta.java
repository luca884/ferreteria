package com.ferreteria.clientes.model;

/**
 * Tipos de movimiento del libro de cuenta corriente (CC-001). VENTA (debe, un renglón por pago a cuenta) y COBRO (haber, uno
 * por recibo) se escriben desde el tramo 3a; ANULACION es el contra-asiento de un COBRO (debe) previsto para el tramo 3b: ya
 * está admitida en los CHECK de la base y en el enum, pero 3a no tiene fábrica que la escriba. AJUSTE y NC quedan previstos
 * para más adelante. Los nombres caben en movimiento_cuenta_cliente.tipo (VARCHAR(10)).
 */
public enum TipoMovimientoCuenta {
    VENTA,
    COBRO,
    AJUSTE,
    NC,
    ANULACION
}
