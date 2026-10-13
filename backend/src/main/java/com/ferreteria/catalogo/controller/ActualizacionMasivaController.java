package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.ActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.ActualizacionMasivaResponse;
import com.ferreteria.catalogo.dto.AplicarActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaMasivaResponse;
import com.ferreteria.catalogo.service.ActualizacionMasivaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actualizacion masiva de precios (CU-05). Solo el Dueno. Los cuerpos NO llevan {@code @Valid}: el
 * servicio los valida despues de resolver el proveedor y el rubro, para que un recurso ajeno responda
 * 404 antes que un 400 por datos invalidos.
 */
@RestController
@RequestMapping("/api/actualizacion-masiva")
public class ActualizacionMasivaController {

    private final ActualizacionMasivaService actualizacionMasivaService;

    public ActualizacionMasivaController(ActualizacionMasivaService actualizacionMasivaService) {
        this.actualizacionMasivaService = actualizacionMasivaService;
    }

    /** Lo que pasaria al aplicar la operacion; no escribe nada. */
    @PostMapping("/vista-previa")
    @PreAuthorize("hasRole('DUENO')")
    public VistaPreviaMasivaResponse vistaPrevia(@RequestBody ActualizacionMasivaRequest pedido) {
        return actualizacionMasivaService.vistaPrevia(pedido);
    }

    /** Aplica la operacion en una sola transaccion. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public ActualizacionMasivaResponse aplicar(
            @RequestBody AplicarActualizacionMasivaRequest pedido, @AuthenticationPrincipal AuthUser authUser) {
        return actualizacionMasivaService.aplicar(pedido, authUser.userId());
    }
}
