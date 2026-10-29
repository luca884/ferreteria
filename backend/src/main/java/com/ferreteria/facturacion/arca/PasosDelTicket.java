package com.ferreteria.facturacion.arca;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.DatoIlegibleException;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los pasos del ticket de acceso que tocan la base, cada uno en su transacción corta (rama 6, S2 y C4), para
 * {@link TicketsDeAcceso}, que no es transaccional: ninguna llamada a WSAA ocurre con una transacción o una conexión tomada.
 * Todo es de la ferretería del request ({@code TenantContext}).
 *
 * <p>El token y el sign se cifran con {@link CifradorDeClaves}, con el propósito {@link Proposito#TICKET_DE_ACCESO} y la
 * huella de la credencial que pidió el TA en el AAD: un TA guardado con otra huella no se descifra, y un TA de otra
 * credencial no se usa.
 *
 * <p>Los locks, siempre en el mismo orden: {@code config_arca} antes que {@code ticket_acceso} (el bloqueo de C4), y nadie
 * toma {@code ticket_acceso} antes que {@code config_arca}.
 */
@Component
public class PasosDelTicket {

    /** El largo de {@code ticket_acceso.ultima_falla} (V35). */
    static final int LARGO_FALLA = 64;

    private final TicketAccesoRepository tickets;
    private final ConfigArcaRepository configs;
    private final FirmaCms firma;
    private final CifradorDeClaves cifrador;

    public PasosDelTicket(TicketAccesoRepository tickets, ConfigArcaRepository configs, FirmaCms firma,
            CifradorDeClaves cifrador) {
        this.tickets = tickets;
        this.configs = configs;
        this.firma = firma;
        this.cifrador = cifrador;
    }

    /**
     * Cómo está el ticket de ese servicio en ese ambiente: el bloqueo de C4, la credencial activa del ambiente (su huella y
     * su CUIT), el TA guardado si es de esa credencial y se descifra, el lease y la espera. Sin locks.
     */
    @Transactional(readOnly = true)
    public Estado leer(Ambiente ambiente, String servicio) {
        return estado(ambiente, tickets.deClaveScoped(servicio, ambiente));
    }

    /**
     * Toma el lease del ticket (creando la fila si falta) y devuelve cómo está, releído con el lock de la fila: si otro dejó
     * un TA mientras tanto, se ve. Vacío si el lease lo tiene otro.
     */
    @Transactional
    public Optional<Estado> tomar(Ambiente ambiente, String servicio, String dueno, Instant ahora, Instant vence) {
        tickets.crearSiFaltaScoped(servicio, ambiente);
        if (!tickets.tomarScoped(servicio, ambiente, dueno, ahora, vence)) {
            return Optional.empty();
        }
        return Optional.of(estado(ambiente, tickets.bloquearScoped(servicio, ambiente)));
    }

    /** Suelta el lease si sigue siendo de {@code dueno}. */
    @Transactional
    public void soltar(Ambiente ambiente, String servicio, String dueno) {
        tickets.soltarScoped(servicio, ambiente, dueno);
    }

    /** Guarda el TA nuevo, cifrado con la huella de la credencial que lo firmó, y suelta el lease, en una transacción. */
    @Transactional
    public void guardar(Ambiente ambiente, String servicio, String dueno, String huella, RespuestaLoginCms ta) {
        ContextoDeCifrado contexto = contexto(huella);
        tickets.guardarTaScoped(servicio, ambiente, huella,
                cifrador.cifrar(ta.token().getBytes(StandardCharsets.UTF_8), contexto),
                cifrador.cifrar(ta.sign().getBytes(StandardCharsets.UTF_8), contexto), ta.generado(), ta.vence());
        tickets.soltarScoped(servicio, ambiente, dueno);
    }

    /** Un fault que espera (C4): la última falla, sin llamar a WSAA hasta {@code hasta}, y el lease suelto. */
    @Transactional
    public void esperar(Ambiente ambiente, String servicio, String dueno, String codigo, Instant ahora, Instant hasta) {
        tickets.esperarScoped(servicio, ambiente, recortar(codigo), ahora, hasta);
        tickets.soltarScoped(servicio, ambiente, dueno);
    }

    /**
     * Un fault de CREDENCIAL (C4): frena la facturación en {@code config_arca} (con su {@code FOR UPDATE}, primero) con el
     * código, el texto de ARCA y la hora; deja la última falla en el ticket y suelta el lease.
     */
    @Transactional
    public void bloquear(Ambiente ambiente, String servicio, String dueno, String codigo, String mensaje, Instant ahora) {
        ConfigArca config = configs.bloquearScoped()
                .orElseThrow(() -> new ResourceNotFoundException("Ferretería no encontrada"));
        config.bloquear(codigo, mensaje, ahora);
        configs.saveAndFlush(config);
        tickets.registrarFallaScoped(servicio, ambiente, recortar(codigo), ahora);
        tickets.soltarScoped(servicio, ambiente, dueno);
    }

    /**
     * Borra el TA guardado solo si es {@code rechazado} (el mismo token, comparado descifrado), con la fila tomada: si otro
     * pedido ya guardó uno más nuevo, no lo toca. Si no se descifra, tampoco sirve: se borra.
     *
     * @return si lo borró
     */
    @Transactional
    public boolean descartar(Ambiente ambiente, String servicio, TicketDeAcceso rechazado) {
        Optional<TicketAcceso> fila = tickets.bloquearScoped(servicio, ambiente).filter(TicketAcceso::tieneTa);
        if (fila.isEmpty()) {
            return false;
        }
        Optional<String> token = descifrar(fila.get().getTokenCifrado(), fila.get().getHuellaCredencial());
        if (token.isPresent() && !token.get().equals(rechazado.token())) {
            return false;
        }
        tickets.borrarTaScoped(servicio, ambiente);
        return true;
    }

    private Estado estado(Ambiente ambiente, Optional<TicketAcceso> fila) {
        String bloqueo = configs.deFerreteriaScoped().filter(ConfigArca::estaBloqueada).map(ConfigArca::getBloqueoCodigo)
                .orElse(null);
        FirmaCms.Firmante firmante = firma.firmante(ambiente).orElse(null);
        Guardado guardado = fila.filter(TicketAcceso::tieneTa)
                .filter(t -> firmante != null && t.getHuellaCredencial().equals(firmante.huella()))
                .flatMap(this::guardado).orElse(null);
        return new Estado(bloqueo, firmante, guardado, fila.map(TicketAcceso::getLeaseDueno).orElse(null),
                fila.map(TicketAcceso::getLeaseVence).orElse(null), fila.map(TicketAcceso::getEsperaHasta).orElse(null),
                fila.map(TicketAcceso::getUltimaFalla).orElse(null));
    }

    /** El TA guardado, descifrado; vacío si no se descifra (por ejemplo, con una clave maestra que ya no está). */
    private Optional<Guardado> guardado(TicketAcceso fila) {
        Optional<String> token = descifrar(fila.getTokenCifrado(), fila.getHuellaCredencial());
        Optional<String> sign = descifrar(fila.getSignCifrado(), fila.getHuellaCredencial());
        if (token.isEmpty() || sign.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Guardado(token.get(), sign.get(), fila.getGenerado(), fila.getVence()));
    }

    private Optional<String> descifrar(byte[] cifrado, String huella) {
        try {
            return Optional.of(new String(cifrador.descifrar(cifrado, contexto(huella)), StandardCharsets.UTF_8));
        } catch (DatoIlegibleException e) {
            return Optional.empty();
        }
    }

    private static ContextoDeCifrado contexto(String huella) {
        return new ContextoDeCifrado(Proposito.TICKET_DE_ACCESO, TenantContext.requireFerreteriaId(), huella);
    }

    private static String recortar(String codigo) {
        return codigo.length() <= LARGO_FALLA ? codigo : codigo.substring(0, LARGO_FALLA);
    }

    /**
     * Cómo está el ticket de un servicio en un ambiente, leído en un momento.
     *
     * @param bloqueo el código del bloqueo de C4 de la ferretería, o null
     * @param firmante la credencial activa de ese ambiente, o null si no hay
     * @param guardado el TA guardado de esa credencial (descifrado), o null si no hay uno que sirva
     * @param leaseDueno quién tiene el lease, o null
     * @param leaseVence hasta cuándo, o null
     * @param esperaHasta antes de esta hora no se llama a WSAA; null sin espera
     * @param ultimaFalla el código de lo último que falló, o null
     */
    public record Estado(String bloqueo, FirmaCms.Firmante firmante, Guardado guardado, String leaseDueno,
            Instant leaseVence, Instant esperaHasta, String ultimaFalla) {

        /** Si otro tiene el lease y no venció en {@code ahora}. */
        public boolean leaseDeOtro(Instant ahora) {
            return leaseDueno != null && leaseVence.isAfter(ahora);
        }

        /** Si en {@code ahora} todavía hay que esperar para llamar a WSAA. */
        public boolean enEspera(Instant ahora) {
            return esperaHasta != null && esperaHasta.isAfter(ahora);
        }
    }

    /**
     * Un TA guardado, descifrado. {@link #toString()} no muestra el token ni el sign.
     *
     * @param token el token
     * @param sign el sign
     * @param generado su {@code generationTime}
     * @param vence su {@code expirationTime}
     */
    public record Guardado(String token, String sign, Instant generado, Instant vence) {

        @Override
        public String toString() {
            return "PasosDelTicket.Guardado[token=***, sign=***, generado=" + generado + ", vence=" + vence + "]";
        }
    }
}
