package com.ferreteria.catalogo.controller;

import com.ferreteria.catalogo.dto.ProveedorRequest;
import com.ferreteria.catalogo.dto.ProveedorResponse;
import com.ferreteria.catalogo.service.ProveedorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lectura para Dueno y Vendedor; las escrituras solo para el Dueno. */
@RestController
@RequestMapping("/api/proveedores")
public class ProveedorController {

    private final ProveedorService proveedorService;

    public ProveedorController(ProveedorService proveedorService) {
        this.proveedorService = proveedorService;
    }

    /** Solo los activos, salvo incluirInactivos (para ver y reactivar los dados de baja). */
    @GetMapping
    public List<ProveedorResponse> listar(@RequestParam(defaultValue = "false") boolean incluirInactivos) {
        return proveedorService.listar(incluirInactivos);
    }

    /** Vuelve a activar un proveedor dado de baja. No toca nombre, CUIT ni la configuracion de la lista. */
    @PostMapping("/{id}/reactivacion")
    @PreAuthorize("hasRole('DUENO')")
    public ProveedorResponse reactivar(@PathVariable Long id) {
        return proveedorService.reactivar(id);
    }

    @GetMapping("/{id}")
    public ProveedorResponse obtener(@PathVariable Long id) {
        return proveedorService.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public ProveedorResponse crear(@Valid @RequestBody ProveedorRequest request) {
        return proveedorService.crear(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public ProveedorResponse actualizar(@PathVariable Long id, @Valid @RequestBody ProveedorRequest request) {
        return proveedorService.actualizar(id, request);
    }

    /** Baja logica: el proveedor queda inactivo, no se borra. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DUENO')")
    public void desactivar(@PathVariable Long id) {
        proveedorService.desactivar(id);
    }
}
