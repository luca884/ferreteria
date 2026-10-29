package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.arca.ClienteWsaa;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.PropiedadesArca;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.arca.Tra;
import com.ferreteria.facturacion.arca.XmlIlegibleException;
import com.ferreteria.facturacion.arca.XmlSeguro;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.credencial.VerificadorDelSimulador;
import com.ferreteria.facturacion.model.Ambiente;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;

/**
 * WSAA del simulador en el borde (rama 6, S6): recibe el {@code loginCms} que armó {@code ClienteWsaa} con el CMS real de
 * {@code FirmaCms}, y lo revisa como ARCA (especificación técnica WSAA 1.2.2), en este orden:
 * <ol>
 *   <li>el sobre trae {@code loginCms} con {@code in0}: si no, {@code xml.bad};</li>
 *   <li>el CMS verifica contra la CA del simulador de la ferretería ({@link VerificadorDelSimulador}): si no es Base64,
 *       {@code cms.bad.base64}; si no es un SignedData con el contenido, {@code cms.bad}; sin el certificado del firmante,
 *       {@code cms.cert.notFound}; con la firma rota, {@code cms.sign.invalid}; firmado por otra CA (o la ferretería no tiene
 *       CA del simulador), {@code cms.cert.untrusted};</li>
 *   <li>el certificado del firmante está vigente con el {@code Clock}: si no, {@code cms.cert.expired};</li>
 *   <li>el TRA valida contra el XSD de la especificación ({@link Tra#validar}): si no, {@code xml.bad}; su {@code service}
 *       es {@value ClienteWsfe#SERVICIO} (si no, {@code wsn.notFound}) y su {@code generationTime} no es posterior al
 *       {@code Clock} (si no, {@code xml.generationTime.invalid});</li>
 *   <li>el lapso del TA ({@code app.facturacion.arca.lapso-ta.simulador}, C6): si el último TA que emitió para ese servicio
 *       sigue vigente y no pasó el lapso desde que lo emitió, {@code coe.alreadyAuthenticated}, como ARCA. ARCA lleva un TA
 *       por certificado, y el simulador uno por ferretería: un certificado emitido después del último TA (el Dueño activó
 *       otro) saca uno nuevo igual.</li>
 * </ol>
 * Si todo pasa, emite un TA vigente {@link #VIGENCIA_DEL_TA}, con un token y un sign al azar, y recuerda su huella en
 * {@code ticket_simulado} (V35), con la que {@link WsfeDelSimulador} reconoce el {@code Auth}. Un fault va con status 500.
 *
 * <p>El estado se escribe en una transacción propia ({@code REQUIRES_NEW}), con la fila del ticket tomada: dos
 * {@code loginCms} de la misma ferretería se ordenan. Ni el CMS, ni el token, ni el sign van a un log.
 */
@Component
class WsaaDelSimulador {

    /** Lo que dura un TA del simulador: 12 horas, como los de ARCA. */
    static final Duration VIGENCIA_DEL_TA = Duration.ofHours(12);

    private static final ZoneOffset ZONA = ZoneOffset.ofHours(-3);
    private static final DateTimeFormatter TIEMPO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final SecureRandom AZAR = new SecureRandom();

    private final VerificadorDelSimulador verificador;
    private final TicketSimuladoRepository tickets;
    private final TransactionTemplate escritura;
    private final Clock reloj;
    private final Duration lapso;

    WsaaDelSimulador(VerificadorDelSimulador verificador, TicketSimuladoRepository tickets,
            PlatformTransactionManager transacciones, Clock reloj, PropiedadesArca propiedades) {
        this.verificador = verificador;
        this.tickets = tickets;
        this.escritura = new TransactionTemplate(transacciones);
        this.escritura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reloj = reloj;
        this.lapso = propiedades.lapsoTa().de(Ambiente.SIMULADOR);
    }

    /** Atiende un {@code loginCms}: el TA o el fault. */
    Transporte.Respuesta loginCms(String sobre) {
        try {
            FirmaCms.Verificado verificado = verificador.verificar(in0(sobre));
            Instant ahora = reloj.instant();
            if (!vigente(verificado.firmante(), ahora)) {
                throw new Fault("cms.cert.expired", "Certificado expirado");
            }
            Element tra = Tra.validar(new String(verificado.contenido(), StandardCharsets.UTF_8));
            String servicio = XmlSeguro.texto(tra, "service").orElse("");
            if (!ClienteWsfe.SERVICIO.equals(servicio)) {
                throw new Fault("wsn.notFound", "Servicio informado inexistente");
            }
            if (generacion(tra).isAfter(ahora)) {
                throw new Fault("xml.generationTime.invalid", "generationTime posee formato o dato inválido");
            }
            return escritura.execute(estado -> emitir(servicio, verificado.firmante(), ahora));
        } catch (Fault f) {
            return fault(f.codigo, f.getMessage());
        } catch (XmlIlegibleException e) {
            return fault("xml.bad", "No se pudo interpretar el XML");
        } catch (FirmaCms.CmsInvalidoException e) {
            return fault(e.motivo().codigoWsaa(), "El CMS no verifica: " + e.motivo());
        }
    }

    /**
     * La huella con que el simulador recuerda un TA sin guardarlo: SHA-256 del token y el sign (separados por un salto de
     * línea, que no aparece en ninguno de los dos), en hexa mayúscula.
     */
    static String huella(String token, String sign) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token + "\n" + sign).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("El JDK no tiene SHA-256.", e);
        }
    }

    /** Con la fila tomada: el lapso del TA anterior y, si pasa, el TA nuevo. */
    private Transporte.Respuesta emitir(String servicio, X509Certificate firmante, Instant ahora) {
        Optional<TicketSimulado> anterior = tickets.bloquearScoped(servicio);
        if (anterior.isPresent() && dentroDelLapso(anterior.get(), firmante, ahora)) {
            return fault("coe.alreadyAuthenticated", "El CEE ya posee un TA valido para el acceso al WSN solicitado");
        }
        Instant generado = ahora.truncatedTo(ChronoUnit.MILLIS);
        Instant vence = generado.plus(VIGENCIA_DEL_TA);
        String token = azar(96);
        String sign = azar(64);
        tickets.emitirScoped(servicio, huella(token, sign), generado, vence);
        String ta = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<loginTicketResponse version=\"1.0\">"
                + "<header><source>CN=wsaa, O=Simulador de ARCA, C=AR</source>"
                + "<destination>" + XmlSeguro.escapar(firmante.getSubjectX500Principal().getName()) + "</destination>"
                + "<uniqueId>" + Integer.toUnsignedString(AZAR.nextInt()) + "</uniqueId>"
                + "<generationTime>" + tiempo(generado) + "</generationTime>"
                + "<expirationTime>" + tiempo(vence) + "</expirationTime></header>"
                + "<credentials><token>" + token + "</token><sign>" + sign + "</sign></credentials>"
                + "</loginTicketResponse>";
        return new Transporte.Respuesta(200, SobresDelSimulador.loginCms(ta));
    }

    /**
     * Si el TA anterior todavía frena uno nuevo: sigue vigente, no pasó el lapso desde que se emitió, y el certificado que pide
     * no es posterior a ese TA (ARCA lleva un TA por certificado).
     */
    private boolean dentroDelLapso(TicketSimulado anterior, X509Certificate firmante, Instant ahora) {
        boolean otroCertificado = firmante.getNotBefore().toInstant().isAfter(anterior.getGenerado());
        return anterior.vigenteEn(ahora) && ahora.isBefore(anterior.getGenerado().plus(lapso)) && !otroCertificado;
    }

    /** El {@code in0} del {@code loginCms} del sobre. */
    private static String in0(String sobre) {
        Element cuerpo = XmlSeguro.cuerpoSoap(XmlSeguro.leer(sobre));
        if (!XmlSeguro.es(cuerpo, ClienteWsaa.NAMESPACE, "loginCms")) {
            throw new XmlIlegibleException("El pedido a WSAA no es loginCms.");
        }
        return XmlSeguro.texto(cuerpo, "in0").orElseThrow(() -> new XmlIlegibleException("loginCms no trae in0."));
    }

    private static Instant generacion(Element tra) {
        String texto = XmlSeguro.hijo(tra, "header").flatMap(h -> XmlSeguro.texto(h, "generationTime"))
                .orElseThrow(() -> new XmlIlegibleException("El TRA no trae generationTime."));
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeException e) {
            throw new Fault("xml.generationTime.invalid", "generationTime posee formato o dato inválido");
        }
    }

    private static boolean vigente(X509Certificate certificado, Instant ahora) {
        return !ahora.isBefore(certificado.getNotBefore().toInstant()) && ahora.isBefore(certificado.getNotAfter()
                .toInstant());
    }

    private static String azar(int bytes) {
        byte[] valor = new byte[bytes];
        AZAR.nextBytes(valor);
        return Base64.getEncoder().encodeToString(valor);
    }

    private static String tiempo(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZONA).format(TIEMPO);
    }

    private static Transporte.Respuesta fault(String codigo, String texto) {
        return new Transporte.Respuesta(500, SobresDelSimulador.faultWsaa(codigo, texto));
    }

    /** Un rechazo de WSAA, con su código; no sale de esta clase. */
    private static final class Fault extends RuntimeException {

        private final String codigo;

        Fault(String codigo, String texto) {
            super(texto, null, false, false);
            this.codigo = codigo;
        }
    }
}
