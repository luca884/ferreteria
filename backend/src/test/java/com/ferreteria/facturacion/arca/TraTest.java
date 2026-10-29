package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * C3: el TRA ({@code loginTicketRequest}) se arma con la hora del {@code Clock} que recibe, valida contra el XSD de la
 * especificación técnica WSAA 1.2.2 y lleva los tiempos con la zona -03:00 explícita: {@code generationTime} 5 minutos antes
 * y {@code expirationTime} 10 minutos después, con {@code uniqueId} en segundos epoch. Sin {@code source} ni
 * {@code destination}.
 */
class TraTest {

    /** El jueves 2026-10-29 a las 10:15:30.987 de Buenos Aires (13:15:30.987 UTC), con milisegundos a propósito. */
    private static final Instant AHORA = Instant.parse("2026-10-29T13:15:30.987Z");

    @Test
    @DisplayName("el TRA valida contra el XSD de WSAA 1.2.2, con los tiempos de C3 en -03:00 y uniqueId en segundos epoch")
    void validaConLosTiemposDeC3() {
        String tra = Tra.armar(AHORA, "wsfe");

        Element raiz = Tra.validar(tra);

        assertThat(raiz.getLocalName()).isEqualTo("loginTicketRequest");
        assertThat(raiz.getAttribute("version")).isEqualTo("1.0");
        Element header = XmlSeguro.hijo(raiz, "header").orElseThrow();
        assertThat(XmlSeguro.elementos(header)).extracting(Element::getLocalName)
                .containsExactly("uniqueId", "generationTime", "expirationTime");
        assertThat(XmlSeguro.texto(header, "uniqueId")).hasValue(String.valueOf(AHORA.getEpochSecond()));
        assertThat(XmlSeguro.texto(header, "generationTime")).hasValue("2026-10-29T10:10:30-03:00");
        assertThat(XmlSeguro.texto(header, "expirationTime")).hasValue("2026-10-29T10:25:30-03:00");
        assertThat(XmlSeguro.texto(raiz, "service")).hasValue("wsfe");
    }

    @Test
    @DisplayName("un TRA que no respeta el XSD (sin expirationTime, o con un servicio de dos letras) no valida")
    void unTraQueNoRespetaElXsd() {
        String sinVencimiento = Tra.armar(AHORA, "wsfe").replaceFirst("<expirationTime>[^<]*</expirationTime>", "");

        assertThatThrownBy(() -> Tra.validar(sinVencimiento)).isInstanceOf(XmlIlegibleException.class)
                .hasMessageContaining("TRA");
        assertThatThrownBy(() -> Tra.validar(Tra.armar(AHORA, "fe"))).isInstanceOf(XmlIlegibleException.class);
    }
}
