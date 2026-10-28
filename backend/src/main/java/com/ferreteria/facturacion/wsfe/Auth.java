package com.ferreteria.facturacion.wsfe;

import com.ferreteria.facturacion.model.Ambiente;

/**
 * Lo que lleva cada llamada a WSFEv1 para saber a quién y a dónde va: el ambiente y la CUIT emisora ({@code Auth.Cuit}).
 * El token y la firma del WSAA ({@code Auth.Token}, {@code Auth.Sign}) los pone el cliente real (ramas 5 y 6): el motor no
 * los conoce.
 *
 * @param ambiente el ambiente del registro fiscal (no el de la configuración actual)
 * @param cuit la CUIT emisora, 11 dígitos sin guiones
 */
public record Auth(Ambiente ambiente, long cuit) {

    public Auth {
        Campos.requerido("el ambiente", ambiente);
        Campos.digitos("Cuit", cuit, 11);
    }
}
