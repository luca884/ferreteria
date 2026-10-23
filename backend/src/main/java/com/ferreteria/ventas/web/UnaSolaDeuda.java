package com.ferreteria.ventas.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Un renglón de imputación va a una sola deuda (GDS-20): a un comprobante ({@code comprobanteId}) o a un Recargo
 * ({@code ajusteId}), nunca a los dos ni a ninguno. Es de la clase porque mira dos campos, pero el error sale en el campo, con la
 * misma clave y el mismo mensaje que daba {@code @NotNull} antes de los Recargos y que da {@code RegistroCobranza.validarForma}:
 * así el 400 trae este error junto con los demás errores del renglón.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UnaSolaDeuda.Validador.class)
public @interface UnaSolaDeuda {

    String message() default "Elegí el comprobante.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String MENSAJE_LOS_DOS = "Elegí el comprobante o el Recargo, no los dos.";

    class Validador implements ConstraintValidator<UnaSolaDeuda, CobranzaRequest.ImputacionRequest> {

        @Override
        public boolean isValid(CobranzaRequest.ImputacionRequest renglon, ConstraintValidatorContext contexto) {
            if (renglon == null) {
                return true;
            }
            boolean aComprobante = renglon.comprobanteId() != null;
            boolean aRecargo = renglon.ajusteId() != null;
            if (aComprobante != aRecargo) {
                return true;
            }
            contexto.disableDefaultConstraintViolation();
            if (aComprobante) {
                contexto.buildConstraintViolationWithTemplate(MENSAJE_LOS_DOS).addPropertyNode("ajusteId")
                        .addConstraintViolation();
            } else {
                contexto.buildConstraintViolationWithTemplate(contexto.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("comprobanteId").addConstraintViolation();
            }
            return false;
        }
    }
}
