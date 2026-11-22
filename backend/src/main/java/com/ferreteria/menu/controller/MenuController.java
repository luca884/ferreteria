package com.ferreteria.menu.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.menu.dto.Disposicion;
import com.ferreteria.menu.dto.MenuResponse;
import com.ferreteria.menu.service.MenuService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuService menu;

    public MenuController(MenuService menu) {
        this.menu = menu;
    }

    @GetMapping
    public MenuResponse propio(@AuthenticationPrincipal AuthUser usuario) {
        return menu.propio(usuario);
    }

    @GetMapping("/disposiciones/roles/{rol}")
    @PreAuthorize("hasRole('DUENO')")
    public MenuResponse deRol(@PathVariable String rol) {
        return menu.deRol(rol);
    }

    @PutMapping("/disposiciones/roles/{rol}")
    @PreAuthorize("hasRole('DUENO')")
    public MenuResponse guardarRol(@PathVariable String rol, @RequestBody Disposicion pedido) {
        return menu.guardarRol(rol, pedido);
    }

    @DeleteMapping("/disposiciones/roles/{rol}")
    @PreAuthorize("hasRole('DUENO')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void borrarRol(@PathVariable String rol) {
        menu.borrarRol(rol);
    }

    @GetMapping("/disposiciones/usuarios/{usuarioId}")
    @PreAuthorize("hasRole('DUENO')")
    public MenuResponse deUsuario(@PathVariable Long usuarioId) {
        return menu.deUsuario(usuarioId);
    }

    @PutMapping("/disposiciones/usuarios/{usuarioId}")
    @PreAuthorize("hasRole('DUENO')")
    public MenuResponse guardarUsuario(@PathVariable Long usuarioId, @RequestBody Disposicion pedido) {
        return menu.guardarUsuario(usuarioId, pedido);
    }

    @DeleteMapping("/disposiciones/usuarios/{usuarioId}")
    @PreAuthorize("hasRole('DUENO')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void borrarUsuario(@PathVariable Long usuarioId) {
        menu.borrarUsuario(usuarioId);
    }
}
