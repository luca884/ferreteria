package com.ferreteria.catalogo.controller;

import com.ferreteria.catalogo.dto.RubroRequest;
import com.ferreteria.catalogo.dto.RubroResponse;
import com.ferreteria.catalogo.service.RubroService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rubros")
public class RubroController {

    private final RubroService rubroService;

    public RubroController(RubroService rubroService) {
        this.rubroService = rubroService;
    }

    @GetMapping
    public List<RubroResponse> listar() {
        return rubroService.listar();
    }

    @GetMapping("/{id}")
    public RubroResponse obtener(@PathVariable Long id) {
        return rubroService.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public RubroResponse crear(@Valid @RequestBody RubroRequest request) {
        return rubroService.crear(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public RubroResponse actualizar(@PathVariable Long id, @Valid @RequestBody RubroRequest request) {
        return rubroService.actualizar(id, request);
    }
}
