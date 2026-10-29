package com.ferreteria.facturacion.service;

import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository.AvisosFiscales;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los avisos de facturación del mostrador del Dueño (cu-07-contingencias C4; CU-16 punto 8), de la ferretería del request:
 * cuántas ventas tiene rechazadas, pendientes de CAE desde hace más de 30 minutos (S11, desde la venta) y en revisión, y el
 * bloqueo de ARCA de la rama 6 (C4), con el mismo texto que muestra «Facturación». Solo lecturas; la hora sale del
 * {@code Clock}.
 */
@Service
public class AvisosDelMostradorService {

    /** S11: desde cuándo una pendiente de CAE es «vieja» para el aviso. */
    static final Duration PENDIENTE_VIEJA = Duration.ofMinutes(30);

    /**
     * Lo que el mostrador muestra (solo lo que no es cero) con un enlace a la bandeja.
     *
     * @param rechazadas RECHAZADO y FUERA_DE_FECHA
     * @param pendientesViejas PENDIENTE, EN_CURSO y EN_VERIFICACION de hace más de 30 minutos
     * @param enRevision CAE_NO_COINCIDE y SERIE_DESINCRONIZADA
     * @param bloqueo el texto del bloqueo de ARCA, tal cual lo muestra «Facturación»; null sin bloqueo
     */
    public record AvisosDelMostrador(long rechazadas, long pendientesViejas, long enRevision, String bloqueo) {}

    private final ComprobanteFiscalRepository registros;
    private final ConfigArcaRepository configs;
    private final CredencialArcaService credenciales;
    private final Clock reloj;

    public AvisosDelMostradorService(ComprobanteFiscalRepository registros, ConfigArcaRepository configs,
            CredencialArcaService credenciales, Clock reloj) {
        this.registros = registros;
        this.configs = configs;
        this.credenciales = credenciales;
        this.reloj = reloj;
    }

    /**
     * Las cuentas, sobre los registros vigentes, y el bloqueo. El texto del bloqueo es el primer aviso de la configuración
     * de «Facturación» ({@code ConfiguracionFacturacion.avisos}, que lo pone primero): así hay uno solo.
     */
    @Transactional(readOnly = true)
    public AvisosDelMostrador leer() {
        AvisosFiscales cuentas = registros.avisosScoped(reloj.instant().minus(PENDIENTE_VIEJA));
        String bloqueo = configs.deFerreteriaScoped().filter(ConfigArca::estaBloqueada)
                .map(config -> credenciales.configuracion().avisos().get(0))
                .orElse(null);
        return new AvisosDelMostrador(cuentas.rechazadas(), cuentas.pendientesViejas(), cuentas.enRevision(), bloqueo);
    }
}
