package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.ConfiguracionPreciosGuardadaResponse;
import com.ferreteria.catalogo.dto.ConfiguracionPreciosRequest;
import com.ferreteria.catalogo.service.CambioConfiguracionPreciosService;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * El Dueño cambia el margen por defecto y el multiplo de redondeo de la ferreteria del token. El GET de la misma
 * ruta vive en el modulo ferreteria; este controlador esta en el catalogo porque guardar recalcula precios.
 */
@RestController
@RequestMapping("/api/configuracion-precios")
public class CambioConfiguracionPreciosController {

    private final CambioConfiguracionPreciosService cambioConfiguracionPreciosService;

    public CambioConfiguracionPreciosController(CambioConfiguracionPreciosService cambioConfiguracionPreciosService) {
        this.cambioConfiguracionPreciosService = cambioConfiguracionPreciosService;
    }

    /** 200 con la configuracion guardada y cuantos articulos cambiaron; 409 si hay que recalcular en USD sin cotizacion. */
    @PutMapping
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionPreciosGuardadaResponse guardar(
            @Valid @RequestBody ConfiguracionPreciosRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return cambioConfiguracionPreciosService.guardar(request.aConfiguracion(), authUser.userId());
    }

    /** Cuantos articulos se recalcularian si se guardan estos valores. No guarda nada. */
    @GetMapping("/articulos-que-cambian")
    @PreAuthorize("hasRole('DUENO')")
    public Map<String, Long> articulosQueCambian(
            @RequestParam BigDecimal margenDefectoPorcentaje, @RequestParam BigDecimal redondeoMultiplo) {
        return Map.of("cantidad", cambioConfiguracionPreciosService.contarArticulosQueCambian(
                new ConfiguracionPrecios(margenDefectoPorcentaje, redondeoMultiplo)));
    }
}
