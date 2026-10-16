package com.ferreteria.cotizacion.web;

import com.ferreteria.cotizacion.service.CotizacionService;
import com.ferreteria.cotizacion.web.dto.CotizacionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cotizacion para el menu lateral: cualquier usuario autenticado (Dueño y Vendedor) la lee. Solo lee la base: las
 * llamadas a las APIs externas las hace unicamente el job de cada 30 minutos.
 */
@RestController
@RequestMapping("/api/cotizacion")
public class CotizacionController {

    private final CotizacionService cotizacionService;

    public CotizacionController(CotizacionService cotizacionService) {
        this.cotizacionService = cotizacionService;
    }

    @GetMapping
    public CotizacionResponse obtener() {
        return cotizacionService.obtener();
    }
}
