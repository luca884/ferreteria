package com.ferreteria.facturacion.arca;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * El XSD de WSFEv1 (C5): el schema embebido del WSDL, bajado el 2026-10-29 e igual en homologación y producción, en
 * {@value #RECURSO}. Se carga una vez del classpath (también desde el jar) y valida el elemento del {@code soap:Body}, no el
 * {@code Envelope}: el XSD solo describe los métodos y sus respuestas. Lo usan los tests de las plantillas y, desde la rama
 * 6, el simulador en el borde para rechazar un pedido que ARCA no aceptaría.
 *
 * <p>{@code Schema} es thread-safe; el {@code Validator}, no: se arma uno por validación. Ninguno de los dos accede a DTD ni
 * schemas externos.
 */
public final class EsquemaWsfe {

    /** El XSD en el classpath. */
    public static final String RECURSO = "facturacion/arca/wsfev1-2026-10-29.xsd";

    private EsquemaWsfe() {
    }

    /**
     * Lee un sobre SOAP 1.1 (con {@link XmlSeguro}) y valida su elemento del {@code Body}, que devuelve.
     *
     * @throws XmlIlegibleException si el sobre no se puede leer o el elemento no valida
     */
    public static Element validarSobre(String sobre) {
        Element cuerpo = XmlSeguro.cuerpoSoap(XmlSeguro.leer(sobre));
        validar(cuerpo);
        return cuerpo;
    }

    /**
     * Valida un elemento (un pedido o una respuesta de WSFEv1) contra el XSD.
     *
     * @throws XmlIlegibleException si no valida, con el motivo del validador (el elemento o el tipo que falló)
     */
    public static void validar(Element elemento) {
        try {
            Validator validador = Cargado.ESQUEMA.newValidator();
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validador.validate(new DOMSource(elemento));
        } catch (SAXException e) {
            throw new XmlIlegibleException("El XML no valida contra el XSD de WSFEv1 (" + elemento.getLocalName() + "): "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new XmlIlegibleException("El XML no se pudo validar contra el XSD de WSFEv1.", e);
        }
    }

    /** El schema, cargado la primera vez que se usa (idiom del holder: sin sincronizar a mano). */
    private static final class Cargado {

        static final Schema ESQUEMA = cargar();

        private static Schema cargar() {
            URL ubicacion = EsquemaWsfe.class.getClassLoader().getResource(RECURSO);
            if (ubicacion == null) {
                throw new IllegalStateException("Falta el XSD de WSFEv1 en el classpath: " + RECURSO);
            }
            try (InputStream xsd = ubicacion.openStream()) {
                SchemaFactory fabrica = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return fabrica.newSchema(new StreamSource(xsd, ubicacion.toExternalForm()));
            } catch (SAXException | IOException e) {
                throw new IllegalStateException("No se pudo cargar el XSD de WSFEv1 (" + RECURSO + ").", e);
            }
        }
    }
}
