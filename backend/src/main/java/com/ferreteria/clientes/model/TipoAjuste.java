package com.ferreteria.clientes.model;

/**
 * Los dos ajustes manuales de la cuenta corriente del cliente (GDS-20, G59): el Recargo SUBE la deuda (es una partida
 * pendiente como un comprobante) y el Descuento la BAJA (es crédito, como un recibo). Los nombres caben en VARCHAR(10).
 */
public enum TipoAjuste {
    RECARGO,
    DESCUENTO
}
