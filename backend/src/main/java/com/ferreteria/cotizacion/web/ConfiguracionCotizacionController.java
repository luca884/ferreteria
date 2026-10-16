package com.ferreteria.cotizacion.web;

import com.ferreteria.cotizacion.service.ConfiguracionCotizacionService;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionRequest;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuracion de cotizacion de la ferreteria del token. Solo el Dueño: el rol se exige en SecurityConfig (antes de
 * leer el cuerpo) y de nuevo con @PreAuthorize en cada metodo. La ferreteria sale solo del JWT.
 */
@RestController
@RequestMapping("/api/configuracion-cotizacion")
public class ConfiguracionCotizacionController {

    private final ConfiguracionCotizacionService configuracionService;

    public ConfiguracionCotizacionController(ConfiguracionCotizacionService configuracionService) {
        this.configuracionService = configuracionService;
    }

    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionCotizacionResponse obtener() {
        return ConfiguracionCotizacionResponse.de(configuracionService.obtener());
    }

    /** Guardar no recalcula ningun precio: el cambio se aplica en el proximo recalculo. */
    @PutMapping
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionCotizacionResponse actualizar(@RequestBody ConfiguracionCotizacionRequest pedido) {
        return ConfiguracionCotizacionResponse.de(configuracionService.actualizar(pedido));
    }
}
