package com.ferreteria.ventas.model;

/**
 * Estado de una caja respecto de un dia (CU-10). ABIERTA: abierta hoy, se vende y se cargan movimientos. DE_OTRO_DIA:
 * sigue abierta desde un dia anterior; solo se puede cerrar. CERRADA: inmutable.
 */
public enum EstadoCaja {
    ABIERTA,
    DE_OTRO_DIA,
    CERRADA
}
