package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.TipoMovimientoCaja;

import java.math.BigDecimal;

/**
 * Los cuerpos de la caja (CU-10). Sin ferreteria ni usuario: salen del JWT. Las validaciones las hace el servicio,
 * despues del 404 de una caja ajena, con los mensajes del dominio.
 */
public final class CajaRequests {

    private CajaRequests() {
    }

    public record Apertura(BigDecimal efectivoInicial) {}

    /** {@code confirmaSinEfectivo}: el Dueño registra un gasto o retiro mayor al efectivo de la caja (sin el campo, false). */
    public record Movimiento(TipoMovimientoCaja tipo, BigDecimal importe, String motivo, Boolean confirmaSinEfectivo) {}

    /** Cierre ciego: solo el efectivo contado. */
    public record Cierre(BigDecimal efectivoContado) {}
}
