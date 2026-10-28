package com.ferreteria.facturacion.wsfe;

import java.time.LocalDate;

/**
 * El período que ajusta una nota de crédito o de débito sin comprobante asociado ({@code PeriodoAsoc}, manual 4.8 p. 32).
 *
 * @param fchDesde {@code FchDesde}, String (8) yyyymmdd
 * @param fchHasta {@code FchHasta}, String (8) yyyymmdd
 */
public record PeriodoAsoc(LocalDate fchDesde, LocalDate fchHasta) {

    public PeriodoAsoc {
        Campos.requerido("PeriodoAsoc.FchDesde", fchDesde);
        Campos.requerido("PeriodoAsoc.FchHasta", fchHasta);
    }
}
