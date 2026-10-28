package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.service.DatosFiscalesService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Datos fiscales de la ferreteria del token (CU-01 paso 1): razon social, CUIT (solo lectura), condicion de IVA,
 * domicilio comercial, inicio de actividades e Ingresos Brutos. Solo el Dueño: el 403 sale antes de leer el cuerpo
 * (SecurityConfig).
 */
@RestController
@RequestMapping("/api/datos-fiscales")
public class DatosFiscalesController {

    private final DatosFiscalesService service;

    public DatosFiscalesController(DatosFiscalesService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public DatosFiscales actuales() {
        return service.actuales();
    }

    @PutMapping
    @PreAuthorize("hasRole('DUENO')")
    public DatosFiscales guardar(@Valid @RequestBody DatosFiscalesRequest request) {
        return service.guardar(request.razonSocial(), request.condicionIva(), request.domicilioComercial(),
                request.inicioActividades(), request.ingresosBrutos());
    }
}
