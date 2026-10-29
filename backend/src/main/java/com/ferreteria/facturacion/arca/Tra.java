package com.ferreteria.facturacion.arca;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * El TRA de WSAA (C3): el {@code loginTicketRequest} que se firma en el CMS de {@code loginCms}. Se arma como texto, con la
 * hora que pasa quien llama (de su {@code Clock}):
 * <ul>
 *   <li>{@code uniqueId}: los segundos epoch de esa hora (un {@code unsignedInt}, que alcanza hasta 2106);</li>
 *   <li>{@code generationTime}: {@value #ANTES_MINUTOS} minutos antes, por si el reloj de ARCA va atrasado (ARCA rechaza
 *       un {@code generationTime} posterior a su hora con {@code xml.generationTime.invalid});</li>
 *   <li>{@code expirationTime}: {@value #DESPUES_MINUTOS} minutos después;</li>
 *   <li>los dos con la zona -03:00 explícita y sin fracción de segundo;</li>
 *   <li>sin {@code source} ni {@code destination}: WSAA toma el primer certificado de firma del CMS.</li>
 * </ul>
 *
 * <p>El XSD es el de la especificación técnica WSAA 1.2.2 ({@value #RECURSO}). Lo usan los tests y, desde la rama 6 (T6),
 * el simulador en el borde, que contesta {@code xml.bad} a un TRA que no valida. El {@code Schema} se carga una vez y es
 * thread-safe; el {@code Validator}, no: uno por validación. Ninguno accede a DTD ni schemas externos.
 */
public final class Tra {

    /** El XSD del TRA en el classpath. */
    public static final String RECURSO = "facturacion/arca/login-ticket-request-wsaa-1.2.2.xsd";

    static final long ANTES_MINUTOS = 5;
    static final long DESPUES_MINUTOS = 10;

    /** La zona de los tiempos del TRA: la de Argentina, explícita. */
    private static final ZoneOffset ZONA = ZoneOffset.ofHours(-3);
    private static final DateTimeFormatter TIEMPO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Tra() {
    }

    /**
     * El TRA para ese servicio de WSAA ({@code "wsfe"}), armado en {@code ahora}.
     *
     * @param ahora la hora del {@code Clock} de quien llama
     * @param servicio el servicio para el que se pide el TA
     */
    public static String armar(Instant ahora, String servicio) {
        Objects.requireNonNull(ahora, "la hora");
        Objects.requireNonNull(servicio, "el servicio");
        Instant enSegundos = ahora.truncatedTo(ChronoUnit.SECONDS);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<loginTicketRequest version=\"1.0\"><header>"
                + "<uniqueId>" + enSegundos.getEpochSecond() + "</uniqueId>"
                + "<generationTime>" + tiempo(enSegundos.minus(Duration.ofMinutes(ANTES_MINUTOS))) + "</generationTime>"
                + "<expirationTime>" + tiempo(enSegundos.plus(Duration.ofMinutes(DESPUES_MINUTOS))) + "</expirationTime>"
                + "</header><service>" + XmlSeguro.escapar(servicio) + "</service></loginTicketRequest>";
    }

    /**
     * Lee un TRA (con {@link XmlSeguro}) y lo valida contra el XSD; devuelve su raíz.
     *
     * @throws XmlIlegibleException si no se puede leer o no valida, con el motivo del validador
     */
    public static Element validar(String tra) {
        Element raiz = XmlSeguro.leer(tra).getDocumentElement();
        try {
            Validator validador = Cargado.ESQUEMA.newValidator();
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validador.validate(new DOMSource(raiz));
            return raiz;
        } catch (SAXException e) {
            throw new XmlIlegibleException("El TRA no valida contra el XSD de WSAA: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new XmlIlegibleException("El TRA no se pudo validar contra el XSD de WSAA.", e);
        }
    }

    private static String tiempo(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZONA).format(TIEMPO);
    }

    /** El schema, cargado la primera vez que se usa (idiom del holder, como {@link EsquemaWsfe}). */
    private static final class Cargado {

        static final Schema ESQUEMA = cargar();

        private static Schema cargar() {
            URL ubicacion = Tra.class.getClassLoader().getResource(RECURSO);
            if (ubicacion == null) {
                throw new IllegalStateException("Falta el XSD del TRA en el classpath: " + RECURSO);
            }
            try (InputStream xsd = ubicacion.openStream()) {
                SchemaFactory fabrica = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return fabrica.newSchema(new StreamSource(xsd, ubicacion.toExternalForm()));
            } catch (SAXException | IOException e) {
                throw new IllegalStateException("No se pudo cargar el XSD del TRA (" + RECURSO + ").", e);
            }
        }
    }
}
