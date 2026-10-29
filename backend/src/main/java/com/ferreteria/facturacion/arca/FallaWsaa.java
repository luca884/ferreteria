package com.ferreteria.facturacion.arca;

import java.util.Objects;

/**
 * Qué se hace con cada fault de WSAA (C4, el cortacircuito por ferretería), según su código (la tabla de errores de la
 * especificación técnica WSAA 1.2.2). En los cuatro casos el registro queda sin enviar: el pedido a WSFE no salió.
 */
public enum FallaWsaa {

    /**
     * {@code cms.cert.*} y {@code coe.notAuthorized}: ARCA rechaza el certificado o no lo autorizó a {@code wsfe}. Frena la
     * facturación de la ferretería en {@code config_arca} hasta que el Dueño cambie el certificado o pruebe la conexión; no
     * se vuelve a llamar a WSAA mientras dure.
     */
    CREDENCIAL,

    /**
     * {@code xml.*}, {@code wsn.notFound}, {@code cms.bad}, {@code cms.bad.base64}, {@code cms.sign.invalid} y cualquier
     * código que no se conoce: algo del sistema que no debería pasar nunca. Un log {@code ERROR} {@link AlertaArca} para Luca
     * y, durante {@code espera-error-sistema}, sin llamar a WSAA. No frena la facturación.
     */
    SISTEMA,

    /** {@code wsaa.*} y {@code wsn.unavailable}: ARCA fuera de servicio. Sin llamar a WSAA durante {@code espera-wsaa}. */
    TRANSITORIO,

    /**
     * {@code coe.alreadyAuthenticated}: ARCA dice que ya hay un TA vigente (el lapso preventivo). Sin llamar a WSAA durante
     * {@code espera-ta-vigente}.
     */
    TA_VIGENTE;

    /** La clase de un código de WSAA (sin prefijo de namespace). */
    public static FallaWsaa de(String codigo) {
        Objects.requireNonNull(codigo, "el código");
        if (codigo.startsWith("cms.cert.") || codigo.equals("coe.notAuthorized")) {
            return CREDENCIAL;
        }
        if (codigo.startsWith("wsaa.") || codigo.equals("wsn.unavailable")) {
            return TRANSITORIO;
        }
        if (codigo.equals("coe.alreadyAuthenticated")) {
            return TA_VIGENTE;
        }
        return SISTEMA;
    }
}
