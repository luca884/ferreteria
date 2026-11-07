package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.RubroLectura;
import com.ferreteria.catalogo.dto.RubroRequest;
import com.ferreteria.catalogo.dto.RubroResponse;
import com.ferreteria.catalogo.dto.RubroVendedorResponse;
import com.ferreteria.catalogo.dto.VistaPreviaMargenRubroResponse;
import com.ferreteria.catalogo.service.RubroService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/rubros")
public class RubroController {

    private final RubroService rubroService;

    public RubroController(RubroService rubroService) {
        this.rubroService = rubroService;
    }

    /** El Dueno ve el margen de cada rubro; el Vendedor, solo el nombre (H07). */
    @GetMapping
    public List<RubroLectura> listar(@AuthenticationPrincipal AuthUser authUser) {
        List<RubroResponse> rubros = rubroService.listar();
        return rubros.stream().map(r -> segunRol(r, authUser)).toList();
    }

    @GetMapping("/{id}")
    public RubroLectura obtener(@PathVariable Long id, @AuthenticationPrincipal AuthUser authUser) {
        return segunRol(rubroService.obtener(id), authUser);
    }

    private static RubroLectura segunRol(RubroResponse rubro, AuthUser authUser) {
        return authUser.veCostos() ? rubro : RubroVendedorResponse.from(rubro);
    }

    /**
     * Los articulos que cambiarian de precio si el margen del rubro pasa a {@code margenPorcentaje}, con el precio de hoy
     * y el nuevo. No guarda nada. Solo el Dueno.
     */
    @GetMapping("/{id}/vista-previa-margen")
    @PreAuthorize("hasRole('DUENO')")
    public VistaPreviaMargenRubroResponse vistaPreviaMargen(
            @PathVariable Long id, @RequestParam BigDecimal margenPorcentaje) {
        return rubroService.vistaPreviaMargen(id, margenPorcentaje);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public RubroResponse crear(@Valid @RequestBody RubroRequest request) {
        return rubroService.crear(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public RubroResponse actualizar(
            @PathVariable Long id, @Valid @RequestBody RubroRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return rubroService.actualizar(id, request, authUser.userId());
    }
}
