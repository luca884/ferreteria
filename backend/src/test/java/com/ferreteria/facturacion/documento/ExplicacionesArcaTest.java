package com.ferreteria.facturacion.documento;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * La explicación en criollo de los códigos de ARCA que más aparecen (cu-07-contingencias C1): una tabla chica, para la
 * bandeja de Ventas. El texto de ARCA se muestra igual, tal cual; esto solo lo explica.
 */
class ExplicacionesArcaTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {10013, 10015, 10016, 10048, 10051, 10192, 10242, 10243, 10246})
    @DisplayName("los códigos del manual que más aparecen tienen su explicación")
    void losConocidosTienenExplicacion(int codigo) {
        assertThat(ExplicacionesArca.de(codigo)).isNotBlank().endsWith(".");
    }

    @Test
    @DisplayName("cada rechazo que da el simulador (los E del manual que copia, salvo el 602 de FECompConsultar) tiene la suya")
    void losDelSimuladorTienenExplicacion() throws Exception {
        List<Integer> sinExplicacion = new ArrayList<>();
        for (int codigo : excluyentesDelSimulador()) {
            if (ExplicacionesArca.de(codigo) == null) {
                sinExplicacion.add(codigo);
            }
        }

        assertThat(excluyentesDelSimulador()).isNotEmpty();
        assertThat(sinExplicacion).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {0, 602, 10063, 10188, 19999, -1})
    @DisplayName("un código que no está en la tabla (o una observación de una autorizada) no tiene explicación: null")
    void losDemasNoTienen(int codigo) {
        assertThat(ExplicacionesArca.de(codigo)).isNull();
    }

    /** Los códigos E del simulador ({@code CodigoManual}, del paquete del simulador), menos el 602. */
    private static List<Integer> excluyentesDelSimulador() throws Exception {
        Class<?> manual = Class.forName("com.ferreteria.facturacion.simulador.CodigoManual");
        Method codigo = manual.getDeclaredMethod("codigo");
        codigo.setAccessible(true);
        List<Integer> codigos = new ArrayList<>();
        for (Object constante : manual.getEnumConstants()) {
            int valor = (int) codigo.invoke(constante);
            if (((Enum<?>) constante).name().startsWith("E") && valor != 602) {
                codigos.add(valor);
            }
        }
        return codigos;
    }
}
