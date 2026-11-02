package com.ferreteria.auth.service;

import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.tenant.UnscopedTenantAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El JWT dura horas: dar de baja a un usuario, suspender su ferreteria o cambiarle el rol tiene que cortar el token
 * ya emitido en el request siguiente. Una consulta indexada por request, sin cache, para que el corte sea inmediato.
 */
@Service
@UnscopedTenantAccess(reason = "Corre en el filtro de seguridad, antes de que el TenantContext tenga la ferreteria.")
public class SesionVigente {

    private final UsuarioRepository usuarioRepository;

    public SesionVigente(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    /** Como queda la sesion del token en este request. */
    public enum Estado {
        /** El usuario fue dado de baja, cambio de rol o su ferreteria esta suspendida: el token ya no vale. */
        CERRADA,
        VIGENTE,
        /** Vale, pero el usuario tiene que cambiar la contraseña que le dieron antes de usar el resto de la API. */
        DEBE_CAMBIAR_CONTRASENA
    }

    @Transactional(readOnly = true)
    public Estado estado(AuthUser user) {
        return usuarioRepository.sesionVigente(user.userId(), user.rol(), user.ferreteriaId(), EstadoFerreteria.ACTIVA)
                .map(debeCambiar -> debeCambiar ? Estado.DEBE_CAMBIAR_CONTRASENA : Estado.VIGENTE)
                .orElse(Estado.CERRADA);
    }
}
