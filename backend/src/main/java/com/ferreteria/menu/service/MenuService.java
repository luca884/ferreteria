package com.ferreteria.menu.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.menu.dto.Disposicion;
import com.ferreteria.menu.dto.MenuResponse;
import com.ferreteria.menu.dto.MenuResponse.Origen;
import com.ferreteria.menu.model.MenuDisposicion;
import com.ferreteria.menu.repository.MenuDisposicionRepository;
import com.ferreteria.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final MenuDisposicionRepository disposiciones;
    private final UsuarioRepository usuarios;
    private final ObjectMapper json;

    public MenuService(MenuDisposicionRepository disposiciones, UsuarioRepository usuarios, ObjectMapper json) {
        this.disposiciones = disposiciones;
        this.usuarios = usuarios;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public MenuResponse propio(AuthUser usuario) {
        return resolver(usuario.userId(), usuario.rol());
    }

    @Transactional(readOnly = true)
    public MenuResponse deRol(String rol) {
        return base(rolDeFerreteria(rol));
    }

    @Transactional(readOnly = true)
    public MenuResponse deUsuario(Long usuarioId) {
        return resolver(usuarioId, rolDelUsuario(usuarioId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MenuResponse guardarRol(String nombreRol, Disposicion pedido) {
        Rol rol = rolDeFerreteria(nombreRol);
        Disposicion validada = DisposicionValidador.validar(pedido, rol);
        String contenido = serializar(validada);
        bloquearEdiciones();
        Optional<MenuDisposicion> actual = disposiciones.deRolScoped(rol);
        if (actual.isPresent()) {
            actual.get().reemplazarContenido(contenido);
        } else {
            disposiciones.save(MenuDisposicion.paraRol(TenantContext.requireFerreteriaId(), rol, contenido));
        }
        return new MenuResponse(Origen.ROL, validada);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MenuResponse guardarUsuario(Long usuarioId, Disposicion pedido) {
        bloquearEdiciones();
        Rol rol = rolDelUsuario(usuarioId);
        Disposicion validada = DisposicionValidador.validar(pedido, rol);
        String contenido = serializar(validada);
        Optional<MenuDisposicion> actual = disposiciones.deUsuarioScoped(usuarioId);
        if (actual.isPresent()) {
            actual.get().reemplazarContenido(contenido);
        } else {
            disposiciones.save(MenuDisposicion.paraUsuario(TenantContext.requireFerreteriaId(), usuarioId, contenido));
        }
        return new MenuResponse(Origen.USUARIO, validada);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void borrarRol(String nombreRol) {
        Rol rol = rolDeFerreteria(nombreRol);
        bloquearEdiciones();
        disposiciones.borrarRolScoped(rol);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void borrarUsuario(Long usuarioId) {
        bloquearEdiciones();
        rolDelUsuario(usuarioId);
        disposiciones.borrarUsuarioScoped(usuarioId);
    }

    private void bloquearEdiciones() {
        // El mismo candado que usa UsuarioService al cambiar un rol; además evita altas simultáneas del mismo ajuste.
        usuarios.bloquearFerreteriaScoped();
    }

    private MenuResponse resolver(Long usuarioId, Rol rol) {
        return disposiciones.deUsuarioScoped(usuarioId)
                .map(disposicion -> leer(disposicion, Origen.USUARIO, rol))
                .orElseGet(() -> base(rol));
    }

    private MenuResponse base(Rol rol) {
        return disposiciones.deRolScoped(rol)
                .map(disposicion -> leer(disposicion, Origen.ROL, rol))
                .orElseGet(MenuResponse::original);
    }

    private MenuResponse leer(MenuDisposicion fila, Origen origen, Rol rol) {
        try {
            Disposicion disposicion = json.readValue(fila.getContenido(), Disposicion.class);
            return new MenuResponse(origen, DisposicionValidador.paraLectura(disposicion, rol));
        } catch (JsonProcessingException | BadRequestException ilegible) {
            log.warn("No se pudo leer la disposición de menú {}. Se devuelve el menú original.", fila.getId());
            return MenuResponse.original();
        }
    }

    private String serializar(Disposicion validada) {
        try {
            return json.writeValueAsString(validada);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la disposición de menú validada", e);
        }
    }

    private Rol rolDelUsuario(Long usuarioId) {
        return usuarios.findScoped(usuarioId).map(Usuario::getRol)
                .filter(rol -> rol == Rol.DUENO || rol == Rol.VENDEDOR)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    private static Rol rolDeFerreteria(String rol) {
        return switch (rol) {
            case "DUENO" -> Rol.DUENO;
            case "VENDEDOR" -> Rol.VENDEDOR;
            default -> throw new ResourceNotFoundException("Rol no encontrado");
        };
    }
}
