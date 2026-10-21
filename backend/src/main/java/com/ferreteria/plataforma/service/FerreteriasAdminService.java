package com.ferreteria.plataforma.service;

import com.ferreteria.auth.api.UsuariosParaPlataforma;
import com.ferreteria.auth.api.UsuariosParaPlataforma.EmailEnUsoException;
import com.ferreteria.auth.api.UsuariosParaPlataforma.ResumenUsuarios;
import com.ferreteria.common.Cuit;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El super admin da de alta, edita y suspende ferreterías (plataforma). Opera por id explícito y NO toca el TenantContext
 * (el super admin no tiene ferretería). Solo lee las tablas ferretería y usuario: nunca datos de ventas.
 */
@Service
public class FerreteriasAdminService {

    private static final String FERRETERIA_NO_ENCONTRADA = "Ferretería no encontrada";
    private static final String USUARIO_NO_ENCONTRADO = "Usuario no encontrado";
    private static final String CUIT_OBLIGATORIO = "El CUIT es obligatorio.";
    private static final String CUIT_PREFIJO = "El CUIT no es válido: revisá los dos primeros dígitos.";
    private static final String CUIT_REPETIDO = "Ya hay una ferretería con ese CUIT.";
    private static final String EMAIL_EN_USO = "Ese email ya está en uso.";

    private final FerreteriaRepository ferreterias;
    private final UsuariosParaPlataforma usuarios;

    public FerreteriasAdminService(FerreteriaRepository ferreterias, UsuariosParaPlataforma usuarios) {
        this.ferreterias = ferreterias;
        this.usuarios = usuarios;
    }

    /**
     * Ferretería y primer Dueño en UNA transacción: si falla cualquier parte (por ejemplo el email ya existe) no queda
     * ninguna de las dos. El CUIT se guarda con guiones (30-12345678-1).
     */
    @Transactional
    public FerreteriaAdmin alta(DatosAlta datos) {
        String cuit = cuitValido(datos.cuit());
        if (ferreterias.existsByCuit(cuit)) {
            throw cuitRepetido();
        }
        DatosAlta.Dueno dueno = datos.dueno();
        if (usuarios.emailEnUso(dueno.email())) {
            throw emailRepetido();
        }
        Ferreteria ferreteria;
        try {
            ferreteria = ferreterias.saveAndFlush(
                    new Ferreteria(datos.razonSocial().trim(), cuit, datos.condicionIva()));
        } catch (DataIntegrityViolationException e) {
            // La unica unique de la tabla es uk_ferreteria_cuit.
            if (ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                throw cuitRepetido();
            }
            throw e;
        }
        long duenoId;
        try {
            duenoId = usuarios.crearDueno(ferreteria.getId(), dueno.nombre(), dueno.email(), dueno.password());
        } catch (EmailEnUsoException e) {
            throw emailRepetido();
        } catch (DatosInvalidosException e) {
            throw new DatosInvalidosException(prefijarConDueno(e.errors()));
        }
        return admin(ferreteria, new ResumenUsuarios(1, 0, duenoId, dueno.email().trim().toLowerCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public EstadoGeneral listar() {
        Map<Long, ResumenUsuarios> resumen = usuarios.resumenPorFerreteria();
        List<FerreteriaAdmin> items = ferreterias.todasOrdenadas().stream()
                .map(f -> admin(f, resumen.get(f.getId())))
                .toList();
        long activas = items.stream().filter(i -> i.estado() == EstadoFerreteria.ACTIVA).count();
        return new EstadoGeneral(activas, items.size() - activas, items);
    }

    @Transactional
    public FerreteriaAdmin actualizar(long id, String razonSocial, CondicionIva condicionIva) {
        Ferreteria ferreteria = obtener(id);
        ferreteria.actualizarDatos(razonSocial.trim(), condicionIva);
        return admin(ferreteria, usuarios.resumenPorFerreteria().get(id));
    }

    /** El corte de sesiones y logins lo hace SesionVigente; acá no se toca ningún otro dato de la ferretería. */
    @Transactional
    public FerreteriaAdmin cambiarActivo(long id, boolean activo) {
        Ferreteria ferreteria = obtener(id);
        ferreteria.cambiarActivo(activo);
        return admin(ferreteria, usuarios.resumenPorFerreteria().get(id));
    }

    @Transactional
    public void resetearContrasenaDeDueno(long ferreteriaId, long usuarioId, String password) {
        if (!ferreterias.existsById(ferreteriaId)
                || !usuarios.resetearContrasenaDeDueno(ferreteriaId, usuarioId, password)) {
            throw new ResourceNotFoundException(USUARIO_NO_ENCONTRADO);
        }
    }

    private Ferreteria obtener(long id) {
        // findById es valido: la ferreteria es la raiz del tenant, no un repositorio de tenant.
        return ferreterias.findById(id).orElseThrow(() -> new ResourceNotFoundException(FERRETERIA_NO_ENCONTRADA));
    }

    private static String cuitValido(String escrito) {
        String cuit;
        try {
            cuit = Cuit.normalizar(escrito);
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(Map.of("cuit", e.getMessage()));
        }
        if (cuit == null) {
            throw new DatosInvalidosException(Map.of("cuit", CUIT_OBLIGATORIO));
        }
        if (!Cuit.tienePrefijoValido(cuit)) {
            throw new DatosInvalidosException(Map.of("cuit", CUIT_PREFIJO));
        }
        return cuit;
    }

    private static Map<String, String> prefijarConDueno(Map<String, String> errores) {
        Map<String, String> prefijados = new LinkedHashMap<>();
        errores.forEach((campo, mensaje) -> prefijados.put("dueno." + campo, mensaje));
        return prefijados;
    }

    private static ConflictException cuitRepetido() {
        return new ConflictException(CUIT_REPETIDO, Map.of("cuit", CUIT_REPETIDO));
    }

    private static ConflictException emailRepetido() {
        return new ConflictException(EMAIL_EN_USO, Map.of("dueno.email", EMAIL_EN_USO));
    }

    private static FerreteriaAdmin admin(Ferreteria f, ResumenUsuarios r) {
        ResumenUsuarios resumen = r == null ? new ResumenUsuarios(0, 0, null, null) : r;
        return new FerreteriaAdmin(f.getId(), f.getRazonSocial(), f.getCuit(), f.getCondicionIva(), f.getEstado(),
                f.getCreadoEn(), resumen.duenosActivos(), resumen.vendedoresActivos(), resumen.duenoId(),
                resumen.emailDueno());
    }
}
