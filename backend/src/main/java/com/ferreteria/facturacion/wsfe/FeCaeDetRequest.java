package com.ferreteria.facturacion.wsfe;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * El detalle de un comprobante en {@code FECAESolicitar} ({@code FECAEDetRequest}, manual 4.8 p. 28-32), con los campos que
 * usa la facturación: sin tributos, opcionales, compradores ni actividades. Los importes quedan con escala 2 y
 * {@code MonCotiz} con escala 6, así un detalle leído de vuelta (por ejemplo en {@code FECompConsultar}) es igual al enviado.
 *
 * @param concepto {@code Concepto}, Int (2): 1 Productos, 2 Servicios, 3 Productos y Servicios
 * @param docTipo {@code DocTipo}, Int (2): 80 CUIT, 86 CUIL, 96 DNI, 99 sin identificar
 * @param docNro {@code DocNro}, Long (11): 0 con DocTipo 99
 * @param cbteDesde {@code CbteDesde}, Long (8)
 * @param cbteHasta {@code CbteHasta}, Long (8): igual a {@code CbteDesde} (de a un comprobante)
 * @param cbteFch {@code CbteFch}, String (8) yyyymmdd, opcional (null: ARCA pone la fecha de proceso)
 * @param impTotal {@code ImpTotal}, Double (13+2)
 * @param impTotConc {@code ImpTotConc}, Double (13+2): neto no gravado
 * @param impNeto {@code ImpNeto}, Double (13+2): neto gravado (en la C, el total)
 * @param impOpEx {@code ImpOpEx}, Double (13+2): exento
 * @param impTrib {@code ImpTrib}, Double (13+2): tributos
 * @param impIva {@code ImpIVA}, Double (13+2)
 * @param monId {@code MonId}, String (3): «PES»
 * @param monCotiz {@code MonCotiz}, Double (4+6): 1 para PES
 * @param condicionIvaReceptorId {@code CondicionIVAReceptorId}, Int (2) (RG 5616); null solo para probar que ARCA lo exige
 * @param iva el array {@code Iva} de {@code AlicIva} (vacío en la C)
 * @param cbtesAsoc el array {@code CbtesAsoc} (vacío si no ajusta a otro comprobante)
 * @param periodoAsoc {@code PeriodoAsoc}, opcional
 */
public record FeCaeDetRequest(int concepto, int docTipo, long docNro, long cbteDesde, long cbteHasta, LocalDate cbteFch,
        BigDecimal impTotal, BigDecimal impTotConc, BigDecimal impNeto, BigDecimal impOpEx, BigDecimal impTrib,
        BigDecimal impIva, String monId, BigDecimal monCotiz, Integer condicionIvaReceptorId, List<AlicIva> iva,
        List<CbteAsoc> cbtesAsoc, PeriodoAsoc periodoAsoc) {

    public FeCaeDetRequest {
        Campos.digitos("Concepto", concepto, 2);
        Campos.digitos("DocTipo", docTipo, 2);
        Campos.digitos("DocNro", docNro, 11);
        Campos.digitos("CbteDesde", cbteDesde, 8);
        Campos.digitos("CbteHasta", cbteHasta, 8);
        if (cbteDesde != cbteHasta) {
            throw new IllegalArgumentException("El motor pide de a un comprobante: CbteDesde (" + cbteDesde
                    + ") y CbteHasta (" + cbteHasta + ") van iguales.");
        }
        impTotal = Campos.importe("ImpTotal", impTotal, 13, 2);
        impTotConc = Campos.importe("ImpTotConc", impTotConc, 13, 2);
        impNeto = Campos.importe("ImpNeto", impNeto, 13, 2);
        impOpEx = Campos.importe("ImpOpEx", impOpEx, 13, 2);
        impTrib = Campos.importe("ImpTrib", impTrib, 13, 2);
        impIva = Campos.importe("ImpIVA", impIva, 13, 2);
        Campos.texto("MonId", monId, 3);
        monCotiz = Campos.importe("MonCotiz", monCotiz, 4, 6);
        if (condicionIvaReceptorId != null) {
            Campos.digitos("CondicionIVAReceptorId", condicionIvaReceptorId, 2);
        }
        iva = Campos.lista(iva);
        cbtesAsoc = Campos.lista(cbtesAsoc);
    }
}
