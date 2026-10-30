package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.service.DatosFiscalesService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los datos del emisor de la ferreteria del token, solo lectura, para imprimir remitos y presupuestos
 * (cu-07-remito-presupuesto-imprimible, S3). Los leen el Dueño y el Vendedor; el super admin, que no tiene ferreteria,
 * recibe 403. Es la misma lectura de {@code /api/datos-fiscales}, que sigue siendo solo del Dueño y es la unica que edita.
 */
@RestController
@RequestMapping("/api/emisor")
public class EmisorController {

    private final DatosFiscalesService service;

    public EmisorController(DatosFiscalesService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DUENO', 'VENDEDOR')")
    public DatosFiscales actuales() {
        return service.actuales();
    }
}
