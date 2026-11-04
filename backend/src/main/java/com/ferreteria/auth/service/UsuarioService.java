package com.ferreteria.auth.service;

import com.ferreteria.auth.model.PoliticaDeContrasena;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El Dueño gestiona los usuarios de SU ferretería (CU-02). La ferretería sale siempre del JWT; un usuario de otra
 * ferretería responde 404. Un Dueño no se desactiva ni se cambia el rol a sí mismo, y la ferretería siempre conserva al
 * menos un Dueño activo: esa regla se serializa con el candado de la fila de la ferretería, tomado ANTES de contar.
 */
@Service
public class UsuarioService {

    private static final String NO_ENCONTRADO = "Usuario no encontrado";
    private static final String ROL_INVALIDO = "El rol tiene que ser Dueño o Vendedor.";
    private static final String DESACTIVARSE = "No podés desactivar tu propio usuario.";
    private static final String CAMBIARSE_EL_ROL = "No podés cambiar tu propio rol.";
    private static final String ULTIMO_DUENO = "Tiene que quedar al menos un Dueño activo.";

    private final UsuarioRepository usuarios;
    private final EmailUnico emailUnico;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(UsuarioRepository usuarios, EmailUnico emailUnico, PasswordEncoder passwordEncoder) {
        this.usuarios = usuarios;
        this.emailUnico = emailUnico;
        this.passwordEncoder = passwordEncoder;
    }

    /** Por nombre; los inactivos solo si se piden. */
    @Transactional(readOnly = true)
    public List<Usuario> listar(boolean incluirInactivos) {
        return usuarios.listarScoped(incluirInactivos);
    }

    @Transactional
    public Usuario crear(DatosUsuario datos) {
        exigirRolDeFerreteria(datos.rol());
        PoliticaDeContrasena.validar("password", datos.password());
        if (emailUnico.enUso(datos.email())) {
            throw emailRepetido();
        }
        Usuario usuario = Usuario.nuevo(TenantContext.requireFerreteriaId(), EmailUnico.normalizar(datos.email()),
                passwordEncoder.encode(datos.password()), datos.nombre().trim(), datos.rol());
        usuario.cambiarTopeDescuento(datos.topeDescuento());
        usuario.cambiarTelefono(datos.telefono());
        return guardar(usuario);
    }

    /**
     * READ_COMMITTED y el candado primero: con REPEATABLE READ, el conteo de Dueños posterior a la espera vería una foto
     * vieja y dos Dueños podrían dejarse sin Dueño a la vez.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Usuario actualizar(Long id, DatosEdicion datos, Long actorId) {
        usuarios.bloquearFerreteriaScoped();
        Usuario usuario = obtener(id);
        exigirRolDeFerreteria(datos.rol());
        boolean cambiaElRol = datos.rol() != usuario.getRol();
        if (cambiaElRol && id.equals(actorId)) {
            throw new ReglaDeNegocioException(CAMBIARSE_EL_ROL);
        }
        if (cambiaElRol && usuario.getRol() == Rol.DUENO && usuario.isActivo()) {
            exigirOtroDuenoActivo(id);
        }
        if (emailUnico.enUsoPorOtro(datos.email(), id)) {
            throw emailRepetido();
        }
        usuario.actualizar(datos.nombre().trim(), EmailUnico.normalizar(datos.email()), datos.rol());
        usuario.cambiarTopeDescuento(datos.topeDescuento());
        usuario.cambiarTelefono(datos.telefono());
        return guardar(usuario);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Usuario cambiarActivo(Long id, boolean activo, Long actorId) {
        usuarios.bloquearFerreteriaScoped();
        Usuario usuario = obtener(id);
        if (!activo) {
            if (id.equals(actorId)) {
                throw new ReglaDeNegocioException(DESACTIVARSE);
            }
            if (usuario.getRol() == Rol.DUENO && usuario.isActivo()) {
                exigirOtroDuenoActivo(id);
            }
        }
        usuario.cambiarActivo(activo);
        return guardar(usuario);
    }

    @Transactional
    public void resetearContrasena(Long id, String nueva, Long actorId) {
        Usuario usuario = obtener(id);
        PoliticaDeContrasena.validar("password", nueva);
        if (id.equals(actorId)) {
            // El Dueño que se resetea la propia la eligió él: no hay nada que obligarlo a cambiar.
            usuario.cambiarPropiaContrasena(passwordEncoder.encode(nueva));
        } else {
            usuario.resetearContrasena(passwordEncoder.encode(nueva));
        }
        guardar(usuario);
    }

    private Usuario obtener(Long id) {
        return usuarios.findScoped(id).orElseThrow(() -> new ResourceNotFoundException(NO_ENCONTRADO));
    }

    private void exigirOtroDuenoActivo(Long id) {
        if (usuarios.otrosDuenosActivosScoped(id) == 0) {
            throw new ReglaDeNegocioException(ULTIMO_DUENO);
        }
    }

    /** Un usuario de ferretería es Dueño o Vendedor: el super admin no se crea ni se asigna por la API. */
    private static void exigirRolDeFerreteria(Rol rol) {
        if (rol != Rol.DUENO && rol != Rol.VENDEDOR) {
            throw new DatosInvalidosException(Map.of("rol", ROL_INVALIDO));
        }
    }

    private static ConflictException emailRepetido() {
        return new ConflictException(EmailUnico.MENSAJE, Map.of("email", EmailUnico.MENSAJE));
    }

    /** Si dos pedidos cargan el mismo email a la vez, el segundo pierde contra la unique: también es 409. */
    private Usuario guardar(Usuario usuario) {
        try {
            return usuarios.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            if (ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                throw emailRepetido();
            }
            throw e;
        }
    }
}
