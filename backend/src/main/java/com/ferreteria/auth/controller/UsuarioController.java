package com.ferreteria.auth.controller;

import com.ferreteria.auth.dto.CambiarActivoUsuarioRequest;
import com.ferreteria.auth.dto.EdicionUsuarioRequest;
import com.ferreteria.auth.dto.ResetContrasenaRequest;
import com.ferreteria.auth.dto.UsuarioRequest;
import com.ferreteria.auth.dto.UsuarioResponse;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.auth.service.UsuarioService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuarios de la ferretería (CU-02): los gestiona solo el Dueño. La ferretería sale del JWT; un usuario de otra
 * ferretería da 404. El 403 del Vendedor y del super admin sale antes de leer el cuerpo (SecurityConfig).
 */
@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("hasRole('DUENO')")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    public List<UsuarioResponse> listar(@RequestParam(defaultValue = "false") boolean incluirInactivos) {
        return usuarioService.listar(incluirInactivos).stream().map(UsuarioResponse::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponse crear(@Valid @RequestBody UsuarioRequest request) {
        return UsuarioResponse.de(usuarioService.crear(request.aDatos()));
    }

    @PutMapping("/{id}")
    public UsuarioResponse actualizar(@PathVariable Long id, @Valid @RequestBody EdicionUsuarioRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        return UsuarioResponse.de(usuarioService.actualizar(id, request.aDatos(), authUser.userId()));
    }

    @PatchMapping("/{id}/activo")
    public UsuarioResponse cambiarActivo(@PathVariable Long id, @Valid @RequestBody CambiarActivoUsuarioRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        return UsuarioResponse.de(usuarioService.cambiarActivo(id, request.activo(), authUser.userId()));
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetearContrasena(@PathVariable Long id, @Valid @RequestBody ResetContrasenaRequest request) {
        usuarioService.resetearContrasena(id, request.password());
    }
}
