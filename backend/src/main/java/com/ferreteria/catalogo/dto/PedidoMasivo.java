package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.ModoMasivo;
import com.ferreteria.catalogo.model.OperacionMasiva;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.AssertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * Lo que comparten la vista previa y aplicar: el filtro, el modo y el valor. Las reglas que cruzan el
 * modo con el valor (cero, rangos) van aca como {@code @AssertTrue}; cada una da true cuando no aplica
 * (otro modo, modo o valor ausentes, o mas de 2 decimales, que ya informa su propio mensaje).
 *
 * <p>{@code CAMBIAR_RUBRO} no lleva valor y lleva {@code rubroDestinoId}; los otros modos llevan valor y no llevan
 * destino. El valor obligatorio lo controla {@link ValorSegunElModo}, que deja el error en {@code valor} con el mismo
 * mensaje que daba {@code @NotNull}.
 */
public interface PedidoMasivo {

    String MENSAJE_MODO = "Elegí qué querés hacer: cambiar el costo en porcentaje, sumarle un monto al costo, fijar el "
            + "margen, cambiar el precio final en porcentaje o en monto, o cambiar el rubro.";
    String MENSAJE_VALOR_AUSENTE = "Ingresá un valor.";
    String MENSAJE_DECIMALES = "El valor puede tener como máximo 2 decimales.";
    String MENSAJE_CERO = "El porcentaje no puede ser cero.";
    String MENSAJE_MONTO_CERO = "El monto no puede ser cero.";
    String MENSAJE_RANGO_PORCENTAJE = "El porcentaje tiene que estar entre -99,99 y 1.000.";
    String MENSAJE_RANGO_MARGEN = "El margen tiene que estar entre 0 y 1.000.";
    String MENSAJE_RUBRO_DESTINO_AUSENTE = "Elegí el rubro al que pasan los artículos.";
    String MENSAJE_VALOR_DE_MAS = "Al cambiar el rubro no va un valor.";
    String MENSAJE_RUBRO_DESTINO_DE_MAS = "El rubro destino va solo al cambiar el rubro.";

    /** Valores posibles de {@code modo}; es un String para que un modo desconocido no falle antes de los 404. */
    String MODOS = "COSTO_PORCENTAJE|COSTO_MONTO|MARGEN|PRECIO_PORCENTAJE|PRECIO_MONTO|CAMBIAR_RUBRO";

    BigDecimal PORCENTAJE_MINIMO = new BigDecimal("-99.99");
    BigDecimal PORCENTAJE_MAXIMO = new BigDecimal("1000");
    BigDecimal MARGEN_MINIMO = BigDecimal.ZERO;
    BigDecimal MARGEN_MAXIMO = new BigDecimal("1000");

    Long proveedorId();

    Long rubroId();

    /** Solo los articulos cuya ultima importacion es esta; null no filtra. */
    Long importacionId();

    String q();

    boolean incluirInactivos();

    String modo();

    BigDecimal valor();

    /** El rubro al que pasan los articulos con {@code CAMBIAR_RUBRO}; null en los otros modos. */
    Long rubroDestinoId();

    /** El filtro normalizado (el texto en blanco queda ausente). */
    default FiltroArticulos toFiltro() {
        return new FiltroArticulos(proveedorId(), rubroId(), q(), incluirInactivos(), importacionId());
    }

    /**
     * Solo despues de validar: el modo tiene que ser valido y el valor tener a lo sumo 2 decimales (o, al cambiar el
     * rubro, faltar el valor y estar el destino).
     */
    default OperacionMasiva toOperacion() {
        if (esCambioDeRubro()) {
            return OperacionMasiva.cambiarRubro(rubroDestinoId());
        }
        return new OperacionMasiva(ModoMasivo.valueOf(modo()), valor());
    }

    @AssertTrue(message = MENSAJE_CERO, groups = ValidacionActualizacionMasiva.class)
    default boolean isPorcentajeDistintoDeCero() {
        return !aplica(ModoMasivo.COSTO_PORCENTAJE, ModoMasivo.PRECIO_PORCENTAJE) || valor().signum() != 0;
    }

    @AssertTrue(message = MENSAJE_MONTO_CERO, groups = ValidacionActualizacionMasiva.class)
    default boolean isMontoDistintoDeCero() {
        return !aplica(ModoMasivo.COSTO_MONTO, ModoMasivo.PRECIO_MONTO) || valor().signum() != 0;
    }

    @AssertTrue(message = MENSAJE_RANGO_PORCENTAJE, groups = ValidacionActualizacionMasiva.class)
    default boolean isPorcentajeEnRango() {
        return !aplica(ModoMasivo.COSTO_PORCENTAJE, ModoMasivo.PRECIO_PORCENTAJE)
                || (valor().compareTo(PORCENTAJE_MINIMO) >= 0 && valor().compareTo(PORCENTAJE_MAXIMO) <= 0);
    }

    @AssertTrue(message = MENSAJE_RANGO_MARGEN, groups = ValidacionActualizacionMasiva.class)
    default boolean isMargenEnRango() {
        return !aplica(ModoMasivo.MARGEN)
                || (valor().compareTo(MARGEN_MINIMO) >= 0 && valor().compareTo(MARGEN_MAXIMO) <= 0);
    }

    /** {@code CAMBIAR_RUBRO} necesita el rubro destino. */
    @AssertTrue(message = MENSAJE_RUBRO_DESTINO_AUSENTE, groups = ValidacionActualizacionMasiva.class)
    default boolean isRubroDestinoElegido() {
        return !esCambioDeRubro() || rubroDestinoId() != null;
    }

    /** {@code CAMBIAR_RUBRO} no lleva valor. */
    @AssertTrue(message = MENSAJE_VALOR_DE_MAS, groups = ValidacionActualizacionMasiva.class)
    default boolean isSinValorAlCambiarRubro() {
        return !esCambioDeRubro() || valor() == null;
    }

    /** Los otros modos no llevan rubro destino. Con un modo desconocido o ausente ya informa el error del modo. */
    @AssertTrue(message = MENSAJE_RUBRO_DESTINO_DE_MAS, groups = ValidacionActualizacionMasiva.class)
    default boolean isRubroDestinoSoloAlCambiarRubro() {
        return rubroDestinoId() == null || modo() == null || !modo().matches(MODOS) || esCambioDeRubro();
    }

    /** True si el modo es {@code CAMBIAR_RUBRO} (tal cual, como lo pide {@link #MODOS}). */
    default boolean esCambioDeRubro() {
        return ModoMasivo.CAMBIAR_RUBRO.name().equals(modo());
    }

    /** True si el modo es uno de esos, hay valor y tiene a lo sumo 2 decimales (si no, ya lo informan otras reglas). */
    private boolean aplica(ModoMasivo... esperados) {
        return modo() != null
                && java.util.Arrays.stream(esperados).anyMatch(esperado -> modo().equals(esperado.name()))
                && valor() != null
                && valor().scale() <= 2;
    }

    /**
     * El valor es obligatorio salvo al cambiar el rubro, que no lleva. Es de la clase porque mira el modo, pero el error
     * sale en {@code valor}, con la misma clave y el mismo mensaje que daba {@code @NotNull} antes de {@code CAMBIAR_RUBRO}
     * (como {@code UnaSolaDeuda}). Con el modo ausente o desconocido tambien lo pide, como antes.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ValorSegunElModo.Validador.class)
    @interface ValorSegunElModo {

        String message() default MENSAJE_VALOR_AUSENTE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        class Validador implements ConstraintValidator<ValorSegunElModo, PedidoMasivo> {

            @Override
            public boolean isValid(PedidoMasivo pedido, ConstraintValidatorContext contexto) {
                if (pedido == null || pedido.valor() != null || pedido.esCambioDeRubro()) {
                    return true;
                }
                contexto.disableDefaultConstraintViolation();
                contexto.buildConstraintViolationWithTemplate(contexto.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("valor").addConstraintViolation();
                return false;
            }
        }
    }
}
