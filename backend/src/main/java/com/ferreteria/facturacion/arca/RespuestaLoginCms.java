package com.ferreteria.facturacion.arca;

import java.time.Instant;
import java.util.Objects;

/**
 * El TA que devuelve {@code loginCms} (el {@code loginTicketResponse}, C3): las credenciales y su vigencia. El token y el sign
 * son secretos (S10): {@link #toString()} los enmascara.
 *
 * @param token {@code credentials/token}
 * @param sign {@code credentials/sign}
 * @param generado {@code header/generationTime}: cuándo lo sacó WSAA (de él corre el lapso preventivo)
 * @param vence {@code header/expirationTime}
 */
public record RespuestaLoginCms(String token, String sign, Instant generado, Instant vence) {

    public RespuestaLoginCms {
        Objects.requireNonNull(token, "el token del TA");
        Objects.requireNonNull(sign, "el sign del TA");
        Objects.requireNonNull(generado, "el generationTime del TA");
        Objects.requireNonNull(vence, "el expirationTime del TA");
    }

    @Override
    public String toString() {
        return "RespuestaLoginCms[token=***, sign=***, generado=" + generado + ", vence=" + vence + "]";
    }
}
