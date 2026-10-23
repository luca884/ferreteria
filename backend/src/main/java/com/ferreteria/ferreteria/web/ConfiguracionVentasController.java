package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.ConfiguracionVentas;
import com.ferreteria.ferreteria.service.ConfiguracionVentasService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tope de venta a Consumidor Final sin identificar de la ferreteria del token (GDS-32, G88). Solo el Dueño. */
@RestController
@RequestMapping("/api/configuracion-ventas")
public class ConfiguracionVentasController {

    private final ConfiguracionVentasService service;

    public ConfiguracionVentasController(ConfiguracionVentasService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionVentas actual() {
        return service.actual();
    }

    @PutMapping
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionVentas guardar(@Valid @RequestBody ConfiguracionVentasRequest request) {
        return service.guardar(request.aConfiguracion());
    }
}
