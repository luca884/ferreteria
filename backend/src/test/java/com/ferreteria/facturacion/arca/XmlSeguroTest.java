package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/**
 * S4: el parser de lo que llega de ARCA (y, desde la rama 6, de lo que recibe el simulador en el borde) lee con namespaces y
 * rechaza cualquier DOCTYPE, así que tampoco hay entidades externas (XXE) ni expansiones.
 */
class XmlSeguroTest {

    @Test
    @DisplayName("lee un XML con namespaces")
    void leeConNamespaces() {
        Document documento = XmlSeguro.leer("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>");

        assertThat(documento.getDocumentElement().getLocalName()).isEqualTo("Envelope");
        assertThat(documento.getDocumentElement().getNamespaceURI()).isEqualTo("http://schemas.xmlsoap.org/soap/envelope/");
    }

    @Test
    @DisplayName("un DOCTYPE se rechaza, aunque sea inofensivo")
    void rechazaDoctype() {
        String conDoctype = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ELEMENT a (#PCDATA)>]><a>hola</a>";

        assertThatThrownBy(() -> XmlSeguro.leer(conDoctype)).isInstanceOf(XmlIlegibleException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    @DisplayName("una entidad externa no se resuelve: el XML se rechaza y el archivo no aparece en ningún lado")
    void rechazaEntidadExterna(@TempDir Path carpeta) throws Exception {
        Path secreto = carpeta.resolve("secreto.txt");
        Files.writeString(secreto, "contenido-que-no-tiene-que-salir", StandardCharsets.UTF_8);
        String conEntidad = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY s SYSTEM \"" + secreto.toUri() + "\">]>"
                + "<a>&s;</a>";

        assertThatThrownBy(() -> XmlSeguro.leer(conEntidad)).isInstanceOf(XmlIlegibleException.class)
                .hasMessageNotContaining("contenido-que-no-tiene-que-salir")
                .satisfies(e -> assertThat(e.getCause()).hasMessageNotContaining("contenido-que-no-tiene-que-salir"));
    }
}
