package com.ferreteria.facturacion.model;

import java.math.BigDecimal;

/**
 * Un elemento del array {@code AlicIva} de WSFEv1: la alícuota, su base imponible y el IVA, con escala 2.
 *
 * @param alicuota la alícuota (su {@code Id} va como {@code Id})
 * @param baseImp la base imponible ({@code BaseImp})
 * @param importe el IVA de esa alícuota ({@code Importe})
 */
public record AlicuotaDesglosada(Alicuota alicuota, BigDecimal baseImp, BigDecimal importe) {
}
