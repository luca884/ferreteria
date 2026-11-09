package com.ferreteria.auth.service;

import java.util.Map;

/**
 * Un rechazo de {@code /api/auth/totp/...} (totp-super-admin), con el status, el mensaje y los {@code errors} del contrato.
 * El 401 lleva {@code errors.motivo}: el front distingue el tokenTotp vencido (vuelve al email y la contraseña) del código
 * incorrecto (se queda en el código), y ninguno de los dos es el 401 de una sesión vencida.
 */
public class SegundoPasoRechazadoException extends RuntimeException {

    public static final String MENSAJE_TOTP_VENCIDO =
            "Pasaron más de 5 minutos. Volvé a ingresar tu email y contraseña.";
    public static final String MENSAJE_CODIGO_INCORRECTO =
            "El código no es correcto. Fijate en la app y probá de nuevo.";
    public static final String MENSAJE_CODIGO_MAL_ESCRITO = "El código tiene 6 números.";

    private final int status;
    private final Map<String, String> errors;

    private SegundoPasoRechazadoException(int status, String mensaje, Map<String, String> errors) {
        super(mensaje);
        this.status = status;
        this.errors = Map.copyOf(errors);
    }

    /** El tokenTotp es inválido, venció o no es de un super admin activo: 401 {@code TOTP_VENCIDO}. */
    static SegundoPasoRechazadoException totpVencido() {
        return new SegundoPasoRechazadoException(401, MENSAJE_TOTP_VENCIDO, Map.of("motivo", "TOTP_VENCIDO"));
    }

    /** El código no es el de la app o ya se usó: 401 {@code CODIGO_INCORRECTO}. */
    static SegundoPasoRechazadoException codigoIncorrecto() {
        return new SegundoPasoRechazadoException(401, MENSAJE_CODIGO_INCORRECTO, Map.of("motivo", "CODIGO_INCORRECTO"));
    }

    /** El código no son 6 números: 400, con el mensaje también en {@code errors.codigo}. */
    static SegundoPasoRechazadoException codigoMalEscrito() {
        return new SegundoPasoRechazadoException(400, MENSAJE_CODIGO_MAL_ESCRITO,
                Map.of("codigo", MENSAJE_CODIGO_MAL_ESCRITO));
    }

    public int status() {
        return status;
    }

    public Map<String, String> errors() {
        return errors;
    }
}
