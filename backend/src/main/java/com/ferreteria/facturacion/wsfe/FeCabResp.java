package com.ferreteria.facturacion.wsfe;

import java.time.LocalDateTime;

/**
 * La cabecera de la respuesta de {@code FECAESolicitar} ({@code FeCabResp}, manual 4.8 p. 37).
 *
 * @param cuit {@code Cuit}, Long (11): la CUIT emisora
 * @param ptoVta {@code PtoVta}, Int (5)
 * @param cbteTipo {@code CbteTipo}, Int (3)
 * @param fchProceso {@code FchProceso}, String (14) yyyymmddhhmiss, hora de ARCA (Buenos Aires)
 * @param cantReg {@code CantReg}, Int (4)
 * @param resultado {@code Resultado}, String (1)
 */
public record FeCabResp(long cuit, int ptoVta, int cbteTipo, LocalDateTime fchProceso, int cantReg, Resultado resultado) {
}
