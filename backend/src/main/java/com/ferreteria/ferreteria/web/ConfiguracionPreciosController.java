package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Margen por defecto y multiplo de redondeo de la ferreteria del token (Dueno y Vendedor). El formulario de
 * articulo los usa para mostrar el precio final en vivo, con la misma cuenta que el backend.
 */
@RestController
@RequestMapping("/api/configuracion-precios")
public class ConfiguracionPreciosController {

    private final ConfiguracionPreciosService configuracionPreciosService;

    public ConfiguracionPreciosController(ConfiguracionPreciosService configuracionPreciosService) {
        this.configuracionPreciosService = configuracionPreciosService;
    }

    @GetMapping
    public ConfiguracionPrecios actual() {
        return configuracionPreciosService.actual();
    }
}
