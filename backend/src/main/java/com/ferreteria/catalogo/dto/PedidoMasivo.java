package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.ModoMasivo;
import com.ferreteria.catalogo.model.OperacionMasiva;
import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;

/**
 * Lo que comparten la vista previa y aplicar: el filtro, el modo y el valor. Las reglas que cruzan el
 * modo con el valor (cero, rangos) van aca como {@code @AssertTrue}; cada una da true cuando no aplica
 * (otro modo, modo o valor ausentes, o mas de 2 decimales, que ya informa su propio mensaje).
 */
public interface PedidoMasivo {

    String MENSAJE_MODO = "Elegí qué querés hacer: cambiar el costo en porcentaje, sumarle un monto al costo o fijar el margen.";
    String MENSAJE_VALOR_AUSENTE = "Ingresá un valor.";
    String MENSAJE_DECIMALES = "El valor puede tener como máximo 2 decimales.";
    String MENSAJE_CERO = "El porcentaje no puede ser cero.";
    String MENSAJE_MONTO_CERO = "El monto no puede ser cero.";
    String MENSAJE_RANGO_PORCENTAJE = "El porcentaje tiene que estar entre -99,99 y 1.000.";
    String MENSAJE_RANGO_MARGEN = "El margen tiene que estar entre 0 y 1.000.";

    /** Valores posibles de {@code modo}; es un String para que un modo desconocido no falle antes de los 404. */
    String MODOS = "COSTO_PORCENTAJE|COSTO_MONTO|MARGEN";

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

    /** El filtro normalizado (el texto en blanco queda ausente). */
    default FiltroArticulos toFiltro() {
        return new FiltroArticulos(proveedorId(), rubroId(), q(), incluirInactivos(), importacionId());
    }

    /** Solo despues de validar: el modo tiene que ser valido y el valor tener a lo sumo 2 decimales. */
    default OperacionMasiva toOperacion() {
        return new OperacionMasiva(ModoMasivo.valueOf(modo()), valor());
    }

    @AssertTrue(message = MENSAJE_CERO, groups = ValidacionActualizacionMasiva.class)
    default boolean isPorcentajeDistintoDeCero() {
        return !aplica(ModoMasivo.COSTO_PORCENTAJE) || valor().signum() != 0;
    }

    @AssertTrue(message = MENSAJE_MONTO_CERO, groups = ValidacionActualizacionMasiva.class)
    default boolean isMontoDistintoDeCero() {
        return !aplica(ModoMasivo.COSTO_MONTO) || valor().signum() != 0;
    }

    @AssertTrue(message = MENSAJE_RANGO_PORCENTAJE, groups = ValidacionActualizacionMasiva.class)
    default boolean isPorcentajeEnRango() {
        return !aplica(ModoMasivo.COSTO_PORCENTAJE)
                || (valor().compareTo(PORCENTAJE_MINIMO) >= 0 && valor().compareTo(PORCENTAJE_MAXIMO) <= 0);
    }

    @AssertTrue(message = MENSAJE_RANGO_MARGEN, groups = ValidacionActualizacionMasiva.class)
    default boolean isMargenEnRango() {
        return !aplica(ModoMasivo.MARGEN)
                || (valor().compareTo(MARGEN_MINIMO) >= 0 && valor().compareTo(MARGEN_MAXIMO) <= 0);
    }

    /** True si el modo es ese, hay valor y tiene a lo sumo 2 decimales (si no, ya lo informan otras reglas). */
    private boolean aplica(ModoMasivo esperado) {
        return modo() != null
                && modo().equals(esperado.name())
                && valor() != null
                && valor().scale() <= 2;
    }
}
