package com.ferreteria.facturacion.arca;

import java.util.Objects;

/**
 * Lo que da el WSAA para llamar a un servicio (el TA, sin su vencimiento): el token, la firma y la CUIT de la credencial con
 * que se sacó. El token y el sign son secretos (S10): {@link #toString()} los enmascara, así un log o un mensaje de error
 * que lo imprima no los muestra.
 *
 * @param token {@code credentials/token} del TA, que va en {@code Auth.Token}
 * @param sign {@code credentials/sign} del TA, que va en {@code Auth.Sign}
 * @param cuit la CUIT del certificado de la credencial (su {@code serialNumber}), 11 dígitos
 */
public record TicketDeAcceso(String token, String sign, long cuit) {

    public TicketDeAcceso {
        Objects.requireNonNull(token, "el token del TA");
        Objects.requireNonNull(sign, "el sign del TA");
    }

    @Override
    public String toString() {
        return "TicketDeAcceso[token=***, sign=***, cuit=" + cuit + "]";
    }
}
