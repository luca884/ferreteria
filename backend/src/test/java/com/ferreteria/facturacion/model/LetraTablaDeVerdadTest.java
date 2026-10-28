package com.ferreteria.facturacion.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 (D2): la letra es una función pura de la condición frente al IVA del emisor y del receptor. La tabla de verdad
 * vive en {@code facturacion/letra-tabla-de-verdad.json}, que también lee el simulador de WSFEv1: este test la recorre
 * entera y exige que cubra cada combinación una sola vez.
 */
class LetraTablaDeVerdadTest {

    private static final String SIN_CLIENTE = "SIN_CLIENTE";

    private static JsonNode filas;

    @BeforeAll
    static void leerJson() throws IOException {
        try (InputStream in = LetraTablaDeVerdadTest.class.getResourceAsStream("/facturacion/letra-tabla-de-verdad.json")) {
            assertThat(in).as("facturacion/letra-tabla-de-verdad.json en el classpath de test").isNotNull();
            filas = new ObjectMapper().readTree(in).get("filas");
        }
    }

    private static String texto(JsonNode fila, String campo) {
        assertThat(fila.get(campo)).as("campo %s de %s", campo, fila).isNotNull();
        return fila.get(campo).asText();
    }

    private static int entero(JsonNode fila, String campo) {
        assertThat(fila.get(campo)).as("campo %s de %s", campo, fila).isNotNull();
        assertThat(fila.get(campo).isInt()).as("%s va como entero en %s", campo, fila).isTrue();
        return fila.get(campo).asInt();
    }

    @Test
    @DisplayName("la tabla tiene una fila por cada emisor y cada receptor, más la venta sin cliente: 3 x 5 = 15")
    void laTablaEsExhaustiva() {
        Set<String> esperadas = new HashSet<>();
        for (CondicionIvaEmisor emisor : CondicionIvaEmisor.values()) {
            for (CondicionIvaReceptor receptor : CondicionIvaReceptor.values()) {
                esperadas.add(emisor + "/" + receptor);
            }
            esperadas.add(emisor + "/" + SIN_CLIENTE);
        }

        List<String> presentes = new ArrayList<>();
        for (JsonNode fila : filas) {
            presentes.add(texto(fila, "emisor") + "/" + texto(fila, "receptor"));
        }

        assertThat(presentes).hasSize(15).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(esperadas);
    }

    @Test
    @DisplayName("cada fila da su letra, el CbteTipo de la factura y de la NC, el CondicionIVAReceptorId y la leyenda de la Ley 27.618")
    void cadaFilaDeLaTabla() {
        for (JsonNode fila : filas) {
            CondicionIvaEmisor emisor = CondicionIvaEmisor.valueOf(texto(fila, "emisor"));
            String receptor = texto(fila, "receptor");
            EncuadreFiscal encuadre = receptor.equals(SIN_CLIENTE)
                    ? EncuadreFiscal.sinCliente(emisor)
                    : EncuadreFiscal.de(emisor, CondicionIvaReceptor.valueOf(receptor));
            String caso = emisor + " a " + receptor;

            assertThat(encuadre.letra()).as("letra de %s", caso).isEqualTo(Letra.valueOf(texto(fila, "letra")));
            assertThat(encuadre.cbteTipo(ClaseComprobante.FACTURA)).as("CbteTipo de la factura de %s", caso)
                    .isEqualTo(entero(fila, "cbteTipoFactura"));
            assertThat(encuadre.cbteTipo(ClaseComprobante.NOTA_CREDITO)).as("CbteTipo de la NC de %s", caso)
                    .isEqualTo(entero(fila, "cbteTipoNotaCredito"));
            assertThat(encuadre.condicionReceptor().condicionIvaReceptorId()).as("CondicionIVAReceptorId de %s", caso)
                    .isEqualTo(entero(fila, "condicionIvaReceptorId"));
            assertThat(encuadre.leyendaLey27618()).as("leyenda de la Ley 27.618 de %s", caso)
                    .isEqualTo(fila.get("leyendaLey27618").asBoolean());
        }
    }
}
