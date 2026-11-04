package com.ferreteria.cotizacion.api;

import java.math.BigDecimal;

/**
 * El dolar con el que «Recalcular ahora» recalcularia, para la vista previa: el ultimo guardado del tipo y lado de la
 * ferreteria, el vigente con el que estan hoy sus precios (null si nunca fijo uno) y, si se aleja mas de su umbral,
 * el aviso tal cual se le muestra al Dueno (null si no).
 */
public record DolarPropuesto(BigDecimal valor, BigDecimal vigente, String avisoDesvio) {

    public boolean superaUmbral() {
        return avisoDesvio != null;
    }
}
