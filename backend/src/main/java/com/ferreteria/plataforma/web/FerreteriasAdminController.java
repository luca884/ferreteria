package com.ferreteria.plataforma.web;

import jakarta.validation.Valid;
import com.ferreteria.plataforma.service.FerreteriasAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ferreterías de la plataforma: las gestiona solo el super admin. El 403 de cualquier otro rol sale antes de leer el cuerpo
 * (SecurityConfig). El super admin no ve ni toca datos de ventas.
 */
@RestController
@RequestMapping("/api/admin/ferreterias")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class FerreteriasAdminController {

    private final FerreteriasAdminService servicio;

    public FerreteriasAdminController(FerreteriasAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public EstadoGeneralResponse estadoGeneral() {
        return EstadoGeneralResponse.de(servicio.listar());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FerreteriaAdminResponse alta(@Valid @RequestBody AltaFerreteriaRequest request) {
        return FerreteriaAdminResponse.de(servicio.alta(request.aDatos()));
    }

    @PutMapping("/{id}")
    public FerreteriaAdminResponse editar(@PathVariable Long id, @Valid @RequestBody EditarFerreteriaRequest request) {
        return FerreteriaAdminResponse.de(servicio.actualizar(id, request.razonSocial(), request.condicionIva()));
    }

    @PatchMapping("/{id}/activo")
    public FerreteriaAdminResponse cambiarActivo(@PathVariable Long id, @Valid @RequestBody CambiarActivoRequest request) {
        return FerreteriaAdminResponse.de(servicio.cambiarActivo(id, request.activo()));
    }

    @PutMapping("/{id}/duenos/{usuarioId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetearContrasenaDelDueno(@PathVariable Long id, @PathVariable Long usuarioId,
            @Valid @RequestBody ResetContrasenaRequest request) {
        servicio.resetearContrasenaDeDueno(id, usuarioId, request.password());
    }
}
