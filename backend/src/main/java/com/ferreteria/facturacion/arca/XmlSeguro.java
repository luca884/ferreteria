package com.ferreteria.facturacion.arca;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * El XML de ARCA, leído y escrito sin sorpresas (S4). El parser es DOM con namespaces y cierra todo lo que permite XXE: un
 * DOCTYPE se rechaza siempre ({@code disallow-doctype-decl}), sin acceso a DTD ni schemas externos, con
 * {@code FEATURE_SECURE_PROCESSING}, sin XInclude y sin expandir entidades. ARCA nunca manda un DOCTYPE, así que no se pierde
 * nada. Los errores no se imprimen en la consola (el {@code ErrorHandler} por defecto lo hace) y el mensaje no lleva el
 * documento.
 *
 * <p>También tiene lo que comparten los lectores: el elemento del {@code soap:Body}, la navegación por hijos del mismo
 * namespace y el escape de un valor que va en una plantilla.
 */
public final class XmlSeguro {

    /** El namespace del sobre SOAP 1.1. */
    public static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final ErrorHandler SIN_CONSOLA = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
            // Las advertencias no cambian el documento: se ignoran, sin imprimirlas.
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private XmlSeguro() {
    }

    /**
     * Lee un documento con namespaces.
     *
     * @throws XmlIlegibleException si está mal formado o trae un DOCTYPE (con o sin entidades)
     */
    public static Document leer(String xml) {
        Objects.requireNonNull(xml, "el XML");
        try {
            DocumentBuilder lector = fabrica().newDocumentBuilder();
            lector.setErrorHandler(SIN_CONSOLA);
            // Con el DOCTYPE prohibido no se llega acá; si un parser lo dejara pasar, tampoco resuelve nada externo.
            lector.setEntityResolver((publico, sistema) -> {
                throw new SAXException("El XML pide una entidad externa: no se resuelve.");
            });
            return lector.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            throw new XmlIlegibleException("El XML no se pudo leer: " + motivo(e), e);
        } catch (IOException e) {
            throw new XmlIlegibleException("El XML no se pudo leer.", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("El parser XML del JDK no acepta la configuración segura.", e);
        }
    }

    /**
     * El elemento dentro del {@code soap:Body} de un sobre SOAP 1.1 (puede ser un {@code soap:Fault}: lo decide quien lee). El
     * {@code soap:Header}, si viene (WSFEv1 manda {@code FEHeaderInfo}), se ignora.
     *
     * @throws XmlIlegibleException si no es un {@code soap:Envelope} con un {@code soap:Body} que tenga un elemento
     */
    public static Element cuerpoSoap(Document sobre) {
        Element envelope = sobre.getDocumentElement();
        if (!es(envelope, SOAP_ENV, "Envelope")) {
            throw new XmlIlegibleException("El XML no es un sobre SOAP 1.1: la raíz es «" + envelope.getLocalName() + "».");
        }
        Element body = hijo(envelope, "Body")
                .orElseThrow(() -> new XmlIlegibleException("El sobre SOAP no tiene soap:Body."));
        return elementos(body).stream().findFirst()
                .orElseThrow(() -> new XmlIlegibleException("El soap:Body viene vacío."));
    }

    /** Si el elemento tiene ese namespace y ese nombre local. */
    public static boolean es(Element elemento, String namespace, String nombre) {
        return nombre.equals(elemento.getLocalName()) && Objects.equals(namespace, elemento.getNamespaceURI());
    }

    /** Los hijos elemento, en orden (sin texto ni comentarios). */
    public static List<Element> elementos(Element padre) {
        List<Element> hijos = new ArrayList<>();
        for (Node nodo = padre.getFirstChild(); nodo != null; nodo = nodo.getNextSibling()) {
            if (nodo instanceof Element elemento) {
                hijos.add(elemento);
            }
        }
        return hijos;
    }

    /** Los hijos con ese nombre local y el namespace del padre (con elementFormDefault qualified, el de todo el pedido). */
    public static List<Element> hijos(Element padre, String nombre) {
        return elementos(padre).stream().filter(h -> es(h, padre.getNamespaceURI(), nombre)).toList();
    }

    /** El primer hijo con ese nombre, si está. */
    public static Optional<Element> hijo(Element padre, String nombre) {
        return hijos(padre, nombre).stream().findFirst();
    }

    /** El texto del hijo, sin espacios alrededor; vacío si el hijo no está o no tiene texto. */
    public static Optional<String> texto(Element padre, String nombre) {
        return hijo(padre, nombre).map(Node::getTextContent).map(String::strip).filter(t -> !t.isEmpty());
    }

    /** Un valor listo para ir entre etiquetas o en un atributo: {@code & < > " '} escapados. */
    public static String escapar(String valor) {
        StringBuilder escapado = new StringBuilder(valor.length() + 16);
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            switch (c) {
                case '&' -> escapado.append("&amp;");
                case '<' -> escapado.append("&lt;");
                case '>' -> escapado.append("&gt;");
                case '"' -> escapado.append("&quot;");
                case '\'' -> escapado.append("&apos;");
                default -> escapado.append(c);
            }
        }
        return escapado.toString();
    }

    private static DocumentBuilderFactory fabrica() {
        // Una fábrica por documento: DocumentBuilderFactory no es thread-safe, y ARCA se llama de a una factura.
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        try {
            fabrica.setNamespaceAware(true);
            fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
            fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            fabrica.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            fabrica.setXIncludeAware(false);
            fabrica.setExpandEntityReferences(false);
            return fabrica;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("El parser XML del JDK no acepta la configuración segura.", e);
        }
    }

    /** El motivo del parser (dice qué regla rompió, no el contenido), con la línea y la columna si las tiene. */
    private static String motivo(SAXException e) {
        String motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof SAXParseException p && p.getLineNumber() > 0) {
            return motivo + " (línea " + p.getLineNumber() + ", columna " + p.getColumnNumber() + ")";
        }
        return motivo;
    }
}
