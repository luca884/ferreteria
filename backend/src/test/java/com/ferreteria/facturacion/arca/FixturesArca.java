package com.ferreteria.facturacion.arca;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Las respuestas de WSFEv1 de {@code src/test/resources/facturacion/arca/}: los dos FEDummy reales (C7) y el resto armado con
 * la forma del WSDL, cada uno con un comentario que lo dice.
 */
final class FixturesArca {

    private FixturesArca() {
    }

    /** El sobre de {@code facturacion/arca/<nombre>.xml}, en UTF-8. */
    static String sobre(String nombre) {
        String recurso = "facturacion/arca/" + nombre + ".xml";
        try (InputStream entrada = FixturesArca.class.getClassLoader().getResourceAsStream(recurso)) {
            if (entrada == null) {
                throw new IllegalArgumentException("No está el fixture " + recurso);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
