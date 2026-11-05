package com.ferreteria.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Todo numero decimal que llega en un pedido (cuerpo JSON o parametro de la URL) tiene que tener un exponente razonable
 * (hallazgo alto 2 de la revision del 05/11). {@code 1E+1000000000} se lee en un instante, pero sumarlo, cambiarle la
 * escala o formatearlo arma un numero de mil millones de cifras: minutos de CPU o la memoria entera del servidor que
 * comparten todas las ferreterias. Se rechaza al leer el pedido, antes de la logica, con el 400 de siempre para un cuerpo
 * o un parametro invalido. El tope (100 posiciones) deja pasar cualquier importe, cantidad o porcentaje real.
 */
@Configuration
public class NumeroDelPedido {

    /** Lo mas lejos que puede estar la coma: la escala de un {@link BigDecimal} entre -100 y 100. */
    static final int ESCALA_MAXIMA = 100;

    static final String FUERA_DE_RANGO = "El número está fuera de rango.";

    /** El numero si su escala esta en [-100, 100]; si no, {@link IllegalArgumentException}. */
    static BigDecimal validado(BigDecimal numero) {
        if (Math.abs((long) numero.scale()) > ESCALA_MAXIMA) {
            throw new IllegalArgumentException(FUERA_DE_RANGO);
        }
        return numero;
    }

    /** Los {@code BigDecimal} del cuerpo JSON. */
    @Bean
    Module numerosDelCuerpo() {
        return new SimpleModule("NumeroDelPedido").addDeserializer(BigDecimal.class, new DesdeJson());
    }

    /**
     * Los {@code BigDecimal} de {@code @RequestParam} y {@code @PathVariable}. Va como editor del binder y no como
     * {@code Converter}: si un conversor falla, Spring reintenta con su editor por defecto, que lo leeria igual. Un editor
     * propio gana sobre los dos, y su error es el 400 de un parametro invalido.
     */
    @ControllerAdvice
    static class EnLaUrl {

        @InitBinder
        void registrar(WebDataBinder binder) {
            binder.registerCustomEditor(BigDecimal.class, new DesdeTexto());
        }
    }

    static final class DesdeJson extends NumberDeserializers.BigDecimalDeserializer {

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            BigDecimal numero = super.deserialize(p, ctxt);
            if (numero == null) {
                return null;
            }
            try {
                return validado(numero);
            } catch (IllegalArgumentException e) {
                throw ctxt.weirdNumberException(numero, BigDecimal.class, FUERA_DE_RANGO);
            }
        }
    }

    /** Lee el texto como siempre (vacio es null, como el editor por defecto) y despues valida el exponente. */
    static final class DesdeTexto extends CustomNumberEditor {

        DesdeTexto() {
            super(BigDecimal.class, true);
        }

        @Override
        public void setAsText(String texto) {
            super.setAsText(texto);
            if (getValue() instanceof BigDecimal numero) {
                validado(numero);
            }
        }
    }
}
