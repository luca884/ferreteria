package com.ferreteria.facturacion.wsfe;

import java.time.LocalDate;
import java.util.List;

/**
 * El resultado de un comprobante en {@code FECAESolicitar} ({@code FECAEDetResponse}, manual 4.8 p. 38-39).
 *
 * @param concepto {@code Concepto}, Int (2)
 * @param docTipo {@code DocTipo}, Int (2)
 * @param docNro {@code DocNro}, Long (11)
 * @param cbteDesde {@code CbteDesde}, Long (8)
 * @param cbteHasta {@code CbteHasta}, Long (8)
 * @param cbteFch {@code CbteFch}, String (8): la del comprobante (si no se mandó, la que puso ARCA)
 * @param resultado {@code Resultado}, String (1): A o R
 * @param cae {@code CAE}, String (14); null con R
 * @param caeFchVto {@code CAEFchVto}, String (8) yyyymmdd; null con R
 * @param observaciones {@code Observaciones}, cada una {@code Obs{Code, Msg}}: con A, las no excluyentes; con R, los
 *     motivos del rechazo
 */
public record FeCaeDetResponse(int concepto, int docTipo, long docNro, long cbteDesde, long cbteHasta, LocalDate cbteFch,
        Resultado resultado, String cae, LocalDate caeFchVto, List<Obs> observaciones) {

    public FeCaeDetResponse {
        observaciones = Campos.lista(observaciones);
    }
}
