package com.ferreteria.facturacion.wsfe;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Un comprobante autorizado, como lo devuelve {@code FECompConsultar} ({@code ResultGet}, manual 4.8 p. 192-193): los campos
 * de {@code FECAEDetRequest} más los del resultado. El detalle va entero, así quien verifica un envío en duda compara el
 * detalle que mandó con este, campo por campo. El esquema XML de {@code ResultGet} no trae
 * {@code CondicionIVAReceptorId}, aunque el texto dice que lleva todos los campos del pedido: se incluye (inferido).
 *
 * @param ptoVta {@code PtoVta}, Int (5)
 * @param cbteTipo {@code CbteTipo}, Int (3)
 * @param detalle los campos de {@code FECAEDetRequest}, con la {@code CbteFch} con que se autorizó
 * @param resultado {@code Resultado}
 * @param codAutorizacion {@code CodAutorizacion}: el CAE
 * @param emisionTipo {@code EmisionTipo}: «CAE» o «CAEA»
 * @param fchVto {@code FchVto}: el vencimiento del CAE
 * @param fchProceso {@code FchProceso}: cuándo se autorizó, hora de ARCA (Buenos Aires)
 * @param observaciones {@code Observaciones} con que se aprobó
 */
public record ResultGet(int ptoVta, int cbteTipo, FeCaeDetRequest detalle, Resultado resultado, String codAutorizacion,
        String emisionTipo, LocalDate fchVto, LocalDateTime fchProceso, List<Obs> observaciones) {

    public ResultGet {
        observaciones = Campos.lista(observaciones);
    }
}
