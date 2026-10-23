package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.TipoMovimientoCaja;

import java.math.BigDecimal;

/**
 * Un gasto o retiro mayor al efectivo esperado de la caja abierta (GDS-36). Al Dueño le llegan las cifras y puede
 * confirmarlo igual; al Vendedor no (cierre ciego: no ve el esperado) y tiene que pedirselo al Dueño. Responde 422.
 */
public class EgresoSinEfectivoException extends RuntimeException {

    private final boolean puedeConfirmar;
    private final BigDecimal disponible;
    private final BigDecimal importe;
    private final BigDecimal faltante;

    private EgresoSinEfectivoException(String mensaje, boolean puedeConfirmar, BigDecimal disponible,
            BigDecimal importe, BigDecimal faltante) {
        super(mensaje);
        this.puedeConfirmar = puedeConfirmar;
        this.disponible = disponible;
        this.importe = importe;
        this.faltante = faltante;
    }

    /** Para el Dueño: con el efectivo esperado, el importe pedido y lo que falta. */
    static EgresoSinEfectivoException paraElDueno(TipoMovimientoCaja tipo, BigDecimal disponible, BigDecimal importe) {
        return new EgresoSinEfectivoException("No alcanza el efectivo de la caja para este " + nombre(tipo) + ".", true,
                disponible, importe, importe.subtract(disponible));
    }

    /** Para el Vendedor: sin cifras. */
    static EgresoSinEfectivoException paraElVendedor(TipoMovimientoCaja tipo) {
        return new EgresoSinEfectivoException("No hay efectivo suficiente en la caja para este " + nombre(tipo)
                + ". Pedile al Dueño que lo cargue.", false, null, null, null);
    }

    private static String nombre(TipoMovimientoCaja tipo) {
        return tipo == TipoMovimientoCaja.RETIRO ? "retiro" : "gasto";
    }

    public boolean puedeConfirmar() {
        return puedeConfirmar;
    }

    public BigDecimal disponible() {
        return disponible;
    }

    public BigDecimal importe() {
        return importe;
    }

    public BigDecimal faltante() {
        return faltante;
    }
}
