package com.ferreteria.facturacion.dev;

import com.ferreteria.facturacion.credencial.CredencialArcaRepository;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Solo en el perfil dev (rama 6, S6): al arrancar, deja una credencial activa del simulador en las ferreterías demo que
 * facturan con él, así siguen facturando ahora que SIMULADOR exige una credencial vigente. Lo hace por el alta real del Dueño
 * ({@link CredencialArcaService}: el pedido de certificado con su clave nueva, cifrada con la clave maestra de dev, y la firma
 * con la CA del simulador de la ferretería): nunca hay datos cifrados en {@code db/dev-data} ni en {@code db/dev-only}.
 *
 * <p>Idempotente: a una demo que no está en SIMULADOR, o que ya tiene una credencial activa (la que dejó el arranque anterior,
 * o una que cargó el Dueño desde la pantalla), no le hace nada. Las demo son las de V1006, por su CUIT. Si una falla, se
 * loguea y se sigue: la app arranca igual, y esa demo vende sin factura hasta que el Dueño haga el alta.
 */
@Component
@Profile("dev")
public class CredencialesDemoDelSimulador implements ApplicationRunner {

    /** Las CUIT de las ferreterías demo (V1006): El Tornillo y La Tuerca. */
    static final Set<String> DEMO = Set.of("30-00000001-5", "30-00000002-3");

    private static final Logger log = LoggerFactory.getLogger(CredencialesDemoDelSimulador.class);

    private final FerreteriaRepository ferreterias;
    private final ConfigArcaRepository configs;
    private final CredencialArcaRepository credencialesGuardadas;
    private final CredencialArcaService credenciales;
    private final TransactionTemplate lectura;
    private final Set<String> cuits;

    @Autowired
    public CredencialesDemoDelSimulador(FerreteriaRepository ferreterias, ConfigArcaRepository configs,
            CredencialArcaRepository credencialesGuardadas, CredencialArcaService credenciales,
            PlatformTransactionManager transacciones) {
        this(ferreterias, configs, credencialesGuardadas, credenciales, transacciones, DEMO);
    }

    /** Con otras CUIT en lugar de las demo (los tests no tocan las demo, que comparten con toda la suite). */
    CredencialesDemoDelSimulador(FerreteriaRepository ferreterias, ConfigArcaRepository configs,
            CredencialArcaRepository credencialesGuardadas, CredencialArcaService credenciales,
            PlatformTransactionManager transacciones, Set<String> cuits) {
        this.ferreterias = ferreterias;
        this.configs = configs;
        this.credencialesGuardadas = credencialesGuardadas;
        this.credenciales = credenciales;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.cuits = Set.copyOf(cuits);
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        List<Ferreteria> demo = ferreterias.todasOrdenadas().stream().filter(f -> cuits.contains(f.getCuit())).toList();
        for (Ferreteria ferreteria : demo) {
            TenantContext.setCurrentFerreteriaId(ferreteria.getId());
            try {
                if (Boolean.TRUE.equals(lectura.execute(estado -> necesitaCredencial()))) {
                    credenciales.generarPedido(null);
                    credenciales.firmarConElSimulador();
                    log.info("Perfil dev: credencial del simulador creada para la ferretería demo {}", ferreteria.getId());
                }
            } catch (RuntimeException e) {
                log.warn("Perfil dev: no se pudo crear la credencial del simulador de la ferretería demo {}: {}",
                        ferreteria.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** Si la ferretería del contexto factura con el simulador y no tiene una credencial activa. */
    private boolean necesitaCredencial() {
        boolean enSimulador = configs.deFerreteriaScoped().filter(c -> c.getAmbiente() == Ambiente.SIMULADOR).isPresent();
        return enSimulador && credencialesGuardadas.activaScoped().isEmpty();
    }
}
