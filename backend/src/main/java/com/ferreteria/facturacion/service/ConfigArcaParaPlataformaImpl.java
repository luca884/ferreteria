package com.ferreteria.facturacion.service;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.ConfigArcaParaPlataforma;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.RegistrosFiscales;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lo que el super admin hace con la configuración de ARCA (CU-01 paso 2, D3). Las reglas viven en {@link ConfigArca}; acá
 * se juntan los registros fiscales de la ferretería, sus datos de CU-01 y la hora del {@code Clock} inyectado.
 *
 * <p>Los tres cambios empiezan tomando el lock de la fila de {@code config_arca} ({@link #bloquear}): dos cambios a la vez
 * se serializan y el segundo decide sobre lo que dejó el primero.
 *
 * <p>El super admin no tiene ferretería en el token: opera por el id de la ruta, y las consultas reciben esa ferretería
 * explícita. Por eso los métodos que las llaman llevan {@code @UnscopedTenantAccess}
 * (molde de {@code UsuariosParaPlataformaImpl}). No se fija el {@code TenantContext} con el id de la ruta: la ferretería
 * del contexto sale solo del JWT.
 */
@Service
public class ConfigArcaParaPlataformaImpl implements ConfigArcaParaPlataforma {

    private static final String PLATAFORMA = "El super admin cambia la configuración de ARCA de una ferretería por su id "
            + "explícito (ruta de /api/admin, solo SUPER_ADMIN); no tiene ferretería en el token.";

    private final ConfigArcaRepository configs;
    private final FerreteriaRepository ferreterias;
    private final Clock reloj;

    public ConfigArcaParaPlataformaImpl(ConfigArcaRepository configs, FerreteriaRepository ferreterias, Clock reloj) {
        this.configs = configs;
        this.ferreterias = ferreterias;
        this.reloj = reloj;
    }

    @Override
    @Transactional(readOnly = true)
    @UnscopedTenantAccess(reason = "La lista del super admin muestra la configuración de ARCA de todas las ferreterías.")
    public Map<Long, ResumenArca> resumenPorFerreteria() {
        return configs.deTodasLasFerreterias().stream()
                .collect(Collectors.toMap(ConfigArca::getFerreteriaId, ConfigArcaParaPlataformaImpl::resumen));
    }

    @Override
    @Transactional(readOnly = true)
    @UnscopedTenantAccess(reason = PLATAFORMA)
    public ResumenArca resumenDe(long ferreteriaId) {
        return configs.deFerreteria(ferreteriaId).map(ConfigArcaParaPlataformaImpl::resumen)
                .orElse(ResumenArca.SIN_CONFIGURAR);
    }

    @Override
    @Transactional
    public ResumenArca cambiarDePrueba(long ferreteriaId, boolean dePrueba) {
        ConfigArca config = bloquear(ferreteriaId);
        config.cambiarDePrueba(dePrueba);
        return guardar(config);
    }

    @Override
    @Transactional
    public ResumenArca cambiarSimulador(long ferreteriaId, boolean simulador) {
        ConfigArca config = bloquear(ferreteriaId);
        RegistrosFiscales registros = registrosDe(ferreteriaId);
        if (simulador) {
            config.asignarSimulador(registros);
        } else {
            config.quitarAmbiente(registros);
        }
        return guardar(config);
    }

    @Override
    @Transactional
    public ResumenArca cambiarFacturacionHabilitada(long ferreteriaId, boolean habilitada) {
        ConfigArca config = bloquear(ferreteriaId);
        if (habilitada) {
            config.habilitar(datosFaltantes(ferreteriaId), Instant.now(reloj));
        } else {
            config.deshabilitar();
        }
        return guardar(config);
    }

    /**
     * La fila de la ferretería con su lock exclusivo, que se sostiene hasta el fin de la transacción. Si no existía la crea
     * con los valores por defecto (sin marca, sin ambiente, deshabilitada): una regla rota (422) revierte la transacción y la
     * fila no queda, pero un cambio que no cambia nada (sacar una marca que no estaba, apagar una facturación apagada) la
     * deja creada con esos valores. Tiene que ser lo primero de la transacción, antes de cualquier lectura sin lock: en
     * REPEATABLE READ la foto de las lecturas se toma en la primera, y así incluye todo lo confirmado antes del lock (por
     * ejemplo, un registro fiscal que se dio de alta mientras se esperaba). Ferretería inexistente: 404.
     */
    @UnscopedTenantAccess(reason = PLATAFORMA)
    private ConfigArca bloquear(long ferreteriaId) {
        configs.crearSiFalta(ferreteriaId);
        return configs.bloquearDeFerreteria(ferreteriaId)
                .orElseThrow(() -> new ResourceNotFoundException("Ferretería no encontrada"));
    }

    @UnscopedTenantAccess(reason = PLATAFORMA)
    private RegistrosFiscales registrosDe(long ferreteriaId) {
        return new RegistrosFiscales(configs.registrosAbiertos(ferreteriaId) > 0,
                configs.registrosDeHomologacionOProduccion(ferreteriaId) > 0);
    }

    private ResumenArca guardar(ConfigArca config) {
        return resumen(configs.saveAndFlush(config));
    }

    /** Los datos de CU-01 que la factura imprime y todavía faltan, con el nombre que ve el super admin. */
    private List<String> datosFaltantes(long ferreteriaId) {
        // findById es valido: la ferreteria es la raiz del tenant, no un repositorio de tenant.
        DatosFiscales datos = DatosFiscales.de(ferreterias.findById(ferreteriaId)
                .orElseThrow(() -> new ResourceNotFoundException("Ferretería no encontrada")));
        List<String> faltan = new ArrayList<>();
        if (vacio(datos.razonSocial())) {
            faltan.add("razón social");
        }
        if (datos.condicionIva() == null) {
            faltan.add("condición de IVA");
        }
        if (vacio(datos.domicilioComercial())) {
            faltan.add("domicilio comercial");
        }
        if (datos.inicioActividades() == null) {
            faltan.add("inicio de actividades");
        }
        if (vacio(datos.ingresosBrutos())) {
            faltan.add("Ingresos Brutos");
        }
        return faltan;
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    private static ResumenArca resumen(ConfigArca c) {
        return new ResumenArca(c.isDePrueba(), c.getAmbiente() == null ? null : c.getAmbiente().name(),
                c.getPuntoVenta(), c.isFacturacionHabilitada(), c.getHabilitadaEn());
    }
}
