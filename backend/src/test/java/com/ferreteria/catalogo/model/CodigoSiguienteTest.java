package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dominio puro (mejoras-ferreteros-bloque-1, S3): el codigo interno que se sugiere en el alta es el ultimo cargado a
 * mano mas uno, con el mismo prefijo y los mismos ceros, salteando los ocupados.
 */
class CodigoSiguienteTest {

    private static final Set<String> NINGUNO = Set.of();

    @Test
    @DisplayName("el ejemplo del pedido: despues de 002 viene 003")
    void ejemploDelPedido() {
        assertThat(CodigoSiguiente.despuesDe("002", NINGUNO::contains)).hasValue("003");
    }

    @Test
    @DisplayName("mantiene el prefijo y los ceros: TOR-009 a TOR-010, A1 a A2, 41 a 42")
    void mantieneElFormato() {
        assertThat(CodigoSiguiente.despuesDe("TOR-009", NINGUNO::contains)).hasValue("TOR-010");
        assertThat(CodigoSiguiente.despuesDe("A1", NINGUNO::contains)).hasValue("A2");
        assertThat(CodigoSiguiente.despuesDe("41", NINGUNO::contains)).hasValue("42");
    }

    @Test
    @DisplayName("cuando se terminan los digitos suma uno: 999 a 1000, TOR-99 a TOR-100")
    void sumaUnDigito() {
        assertThat(CodigoSiguiente.despuesDe("999", NINGUNO::contains)).hasValue("1000");
        assertThat(CodigoSiguiente.despuesDe("TOR-99", NINGUNO::contains)).hasValue("TOR-100");
    }

    @Test
    @DisplayName("saltea los codigos ocupados hasta encontrar uno libre")
    void salteaOcupados() {
        Set<String> ocupados = Set.of("003", "004");

        assertThat(CodigoSiguiente.despuesDe("002", ocupados::contains)).hasValue("005");
    }

    @Test
    @DisplayName("si el codigo no termina en numero, o no hay ninguno, no sugiere nada")
    void sinNumeroFinal() {
        assertThat(CodigoSiguiente.despuesDe("TORNILLO", NINGUNO::contains)).isEmpty();
        assertThat(CodigoSiguiente.despuesDe("12-A", NINGUNO::contains)).isEmpty();
        assertThat(CodigoSiguiente.despuesDe(null, NINGUNO::contains)).isEmpty();
        assertThat(CodigoSiguiente.despuesDe("  ", NINGUNO::contains)).isEmpty();
    }

    @Test
    @DisplayName("si el siguiente superaria los 30 caracteres del codigo, no sugiere nada")
    void respetaElLargoMaximo() {
        String treinta = "A".repeat(27) + "999";

        assertThat(CodigoSiguiente.despuesDe(treinta, NINGUNO::contains)).isEmpty();
    }
}
