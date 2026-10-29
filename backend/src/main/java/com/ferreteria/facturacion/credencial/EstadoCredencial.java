package com.ferreteria.facturacion.credencial;

/**
 * Cómo está la credencial activa de una ferretería, con el {@code Clock} inyectado (S8, S12): no hay job diario, se calcula
 * al leer. VIGENTE y POR_VENCER (30 días o menos) dejan facturar; VENCIDA e ILEGIBLE (la clave guardada no se descifra,
 * S6) bloquean la facturación de homologación y producción de esa ferretería.
 */
public enum EstadoCredencial {
    VIGENTE,
    POR_VENCER,
    VENCIDA,
    ILEGIBLE;

    /** Si con esta credencial el servidor puede firmar. */
    public boolean dejaFacturar() {
        return this == VIGENTE || this == POR_VENCER;
    }
}
