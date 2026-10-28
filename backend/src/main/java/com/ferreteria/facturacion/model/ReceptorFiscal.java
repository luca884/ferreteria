package com.ferreteria.facturacion.model;

import java.util.Objects;

/**
 * Quien recibe el comprobante, como se copia en el registro fiscal al vender (lo que va a ARCA se congela). Es propio de
 * {@code facturacion}: quien pide la factura traduce su cliente a este. La venta sin cliente es {@link #sinCliente()}:
 * Consumidor Final sin identificar (DocTipo 99, DocNro 0).
 *
 * @param condicionIva la condición frente al IVA (decide la letra y el {@code CondicionIVAReceptorId})
 * @param tipoDocumento el documento ({@code DocTipo})
 * @param numeroDocumento el número ({@code DocNro}): 0 sin identificar, mayor que 0 con documento
 * @param nombre la razón social o el nombre, opcional (hasta 120)
 * @param domicilio el domicilio, opcional (hasta 200)
 */
public record ReceptorFiscal(CondicionIvaReceptor condicionIva, TipoDocumentoReceptor tipoDocumento, long numeroDocumento,
        String nombre, String domicilio) {

    public ReceptorFiscal {
        Objects.requireNonNull(condicionIva, "Falta la condición frente al IVA del receptor.");
        Objects.requireNonNull(tipoDocumento, "Falta el tipo de documento del receptor.");
        boolean sinIdentificar = tipoDocumento == TipoDocumentoReceptor.SIN_IDENTIFICAR;
        if (sinIdentificar != (numeroDocumento == 0)) {
            throw new IllegalArgumentException(sinIdentificar
                    ? "Un receptor sin identificar va con el número de documento en 0."
                    : "Falta el número de documento del receptor.");
        }
        if (numeroDocumento < 0 || numeroDocumento > 99_999_999_999L) {
            throw new IllegalArgumentException("El número de documento va sin signo y con 11 dígitos como mucho.");
        }
    }

    /** La venta sin cliente: Consumidor Final sin identificar. */
    public static ReceptorFiscal sinCliente() {
        return new ReceptorFiscal(CondicionIvaReceptor.CONSUMIDOR_FINAL, TipoDocumentoReceptor.SIN_IDENTIFICAR, 0, null,
                null);
    }
}
