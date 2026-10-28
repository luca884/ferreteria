package com.ferreteria.facturacion.wsfe;

import java.math.BigDecimal;

/**
 * Un elemento del array {@code Iva} ({@code AlicIva}, manual 4.8 p. 32). Los importes quedan con escala 2.
 *
 * @param id {@code Id} de la alícuota, Int (2): 3 = 0 %, 4 = 10,5 %, 5 = 21 %, 6 = 27 %, 8 = 5 %, 9 = 2,5 %
 * @param baseImp {@code BaseImp}, Double (13+2)
 * @param importe {@code Importe}, Double (13+2): el IVA de esa alícuota
 */
public record AlicIva(int id, BigDecimal baseImp, BigDecimal importe) {

    public AlicIva {
        Campos.digitos("AlicIva.Id", id, 2);
        baseImp = Campos.importe("AlicIva.BaseImp", baseImp, 13, 2);
        importe = Campos.importe("AlicIva.Importe", importe, 13, 2);
    }
}
