package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.credencial.AutoridadDePrueba;
import com.ferreteria.facturacion.credencial.FirmaCms;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Un WSAA falso detrás del {@link Transporte}, para los tests del ticket de acceso: cuenta cada {@code loginCms}, guarda el
 * CMS que le llega ({@code in0}) y lo revisa como ARCA, con {@link FirmaCms#verificar} contra la CA que emitió el certificado
 * y {@link Tra#validar} sobre el TRA firmado. Contesta en orden lo que se le programó ({@link #luego}) y, sin nada
 * programado, un TA nuevo con un token distinto cada vez, generado con la hora del reloj y vigente {@link #vigencia}.
 * Puede tardar ({@link #demora}) para que los pedidos se crucen. Seguro entre hilos.
 */
final class WsaaDePrueba implements Transporte {

    final AtomicInteger llamadas = new AtomicInteger();
    /** Los CMS que llegaron, en orden. */
    final List<String> cms = new CopyOnWriteArrayList<>();
    /** Los tokens que emitió, en orden. */
    final List<String> tokens = new CopyOnWriteArrayList<>();
    /** Los sign que emitió, en orden. */
    final List<String> signs = new CopyOnWriteArrayList<>();
    volatile Duration demora = Duration.ZERO;
    volatile Duration vigencia = Duration.ofHours(12);

    private final Clock reloj;
    private final String caPem;
    private final Deque<Supplier<Respuesta>> guion = new ConcurrentLinkedDeque<>();

    WsaaDePrueba(Clock reloj, AutoridadDePrueba ca) {
        this.reloj = reloj;
        try {
            this.caPem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(ca.certificado().getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** La próxima llamada contesta esto (en orden; después, TA nuevos). */
    WsaaDePrueba luego(Supplier<Respuesta> respuesta) {
        guion.addLast(respuesta);
        return this;
    }

    /** La próxima llamada contesta un SOAP Fault de Axis con ese código (con el prefijo ns1:) y ese texto, con status 500. */
    WsaaDePrueba luegoFault(String codigo, String texto) {
        return luego(() -> new Respuesta(500, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\""
                + XmlSeguro.SOAP_ENV + "\"><soapenv:Body><soapenv:Fault><faultcode xmlns:ns1=\"" + ClienteWsaa.NAMESPACE
                + "\">ns1:" + codigo + "</faultcode><faultstring>" + XmlSeguro.escapar(texto) + "</faultstring>"
                + "</soapenv:Fault></soapenv:Body></soapenv:Envelope>"));
    }

    /** La próxima llamada falla en el transporte, con esa excepción. */
    WsaaDePrueba luegoFalla(RuntimeException falla) {
        return luego(() -> {
            throw falla;
        });
    }

    @Override
    public Respuesta enviar(Pedido pedido) {
        llamadas.incrementAndGet();
        String in0 = XmlSeguro.texto(XmlSeguro.cuerpoSoap(XmlSeguro.leer(pedido.sobre())), "in0").orElseThrow();
        cms.add(in0);
        Tra.validar(new String(FirmaCms.verificar(in0, caPem).contenido(), StandardCharsets.UTF_8));
        demorar();
        Supplier<Respuesta> programada = guion.pollFirst();
        return programada != null ? programada.get() : taNuevo();
    }

    private Respuesta taNuevo() {
        String token = "TOKEN-WSAA-" + UUID.randomUUID();
        String sign = "SIGN-WSAA-" + UUID.randomUUID();
        tokens.add(token);
        signs.add(sign);
        Instant generado = Instant.now(reloj);
        String ta = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<loginTicketResponse version=\"1.0\">"
                + "<header><source>CN=wsaahomo, O=AFIP, C=AR, SERIALNUMBER=CUIT 33693450239</source>"
                + "<destination>CN=Ferreteria SaaS</destination><uniqueId>" + generado.getEpochSecond() + "</uniqueId>"
                + "<generationTime>" + tiempo(generado) + "</generationTime>"
                + "<expirationTime>" + tiempo(generado.plus(vigencia)) + "</expirationTime></header>"
                + "<credentials><token>" + token + "</token><sign>" + sign + "</sign></credentials></loginTicketResponse>";
        return new Respuesta(200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\""
                + XmlSeguro.SOAP_ENV + "\"><soapenv:Body><loginCmsResponse xmlns=\"" + ClienteWsaa.NAMESPACE + "\">"
                + "<loginCmsReturn>" + XmlSeguro.escapar(ta) + "</loginCmsReturn></loginCmsResponse></soapenv:Body>"
                + "</soapenv:Envelope>");
    }

    private void demorar() {
        if (demora.isZero()) {
            return;
        }
        try {
            Thread.sleep(demora.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String tiempo(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.ofHours(-3)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
