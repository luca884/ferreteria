package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.DatoIlegibleException;
import com.ferreteria.facturacion.model.ConfigArca;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * El estado de la credencial activa y su parte en la regla de facturación (rama 5, S6, S8 y S13). Sin job diario (S12): se
 * calcula al leer, con la hora que pasa quien llama (de su {@code Clock}).
 *
 * <p>La regla, en los tres ambientes: la ferretería factura solo con una credencial ACTIVA de ese mismo ambiente, vigente y
 * legible (su clave se descifra con la clave maestra actual o la anterior). Desde la rama 6 (S6) también en SIMULADOR, que
 * usa el mismo cliente SOAP que ARCA y firma el pedido del ticket con esa credencial (la que firma la CA del simulador de la
 * ferretería). Una credencial vencida, ilegible o que falta bloquea solo la facturación de su ferretería: la venta sale sin
 * factura (D5).
 */
@Component
public class RevisionDeCredencial {

    /** Desde cuántos días antes del vencimiento se avisa (S8). */
    static final Duration AVISO = Duration.ofDays(30);

    private final CifradorDeClaves cifrador;
    private final CredencialArcaRepository credenciales;

    public RevisionDeCredencial(CifradorDeClaves cifrador, CredencialArcaRepository credenciales) {
        this.cifrador = cifrador;
        this.credenciales = credenciales;
    }

    /** ILEGIBLE si la clave no se descifra; si no, VENCIDA, POR_VENCER (30 días o menos) o VIGENTE. */
    public EstadoCredencial estado(CredencialArca activa, Instant ahora) {
        if (!legible(activa)) {
            return EstadoCredencial.ILEGIBLE;
        }
        if (!ahora.isBefore(activa.getVigenteHasta())) {
            return EstadoCredencial.VENCIDA;
        }
        if (!activa.getVigenteHasta().isAfter(ahora.plus(AVISO))) {
            return EstadoCredencial.POR_VENCER;
        }
        return EstadoCredencial.VIGENTE;
    }

    /**
     * La parte de la credencial de la regla de facturación de la ferretería del request, para una configuración que ya deja
     * facturar ({@link ConfigArca#puedeFacturar()}). Con {@code conLock}, lee la activa con {@code FOR SHARE} hasta el fin
     * de la transacción de quien llama, después del {@code FOR SHARE} de {@code config_arca} (el mismo orden que el que
     * activa un certificado, que toma las dos filas con lock exclusivo): lo que decide la venta. Sin lock, lo que anticipa
     * el mostrador.
     */
    public boolean dejaFacturar(ConfigArca config, Instant ahora, boolean conLock) {
        Optional<CredencialArca> activa = conLock ? credenciales.activaCompartidaScoped() : credenciales.activaScoped();
        return activa.filter(credencial -> credencial.getAmbiente() == config.getAmbiente())
                .map(credencial -> estado(credencial, ahora).dejaFacturar())
                .orElse(false);
    }

    /** Si la clave guardada se descifra. El dato en claro no sale de acá: se borra apenas se descifra. */
    private boolean legible(CredencialArca credencial) {
        try {
            byte[] claro = cifrador.descifrar(credencial.getClavePrivada(), credencial.contexto());
            Arrays.fill(claro, (byte) 0);
            return true;
        } catch (DatoIlegibleException e) {
            return false;
        }
    }
}
