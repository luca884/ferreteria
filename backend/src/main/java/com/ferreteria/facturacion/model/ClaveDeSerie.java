package com.ferreteria.facturacion.model;

import java.util.Objects;

/**
 * Una serie de numeración de la ferretería del request por su clave (ambiente, punto de venta y CbteTipo), sin su fila: la
 * que atiende {@code MotorFiscal.atender}, que crea la fila si falta.
 */
public record ClaveDeSerie(Ambiente ambiente, int puntoVenta, int cbteTipo) {

    public ClaveDeSerie {
        Objects.requireNonNull(ambiente, "Falta el ambiente de la serie.");
    }
}
