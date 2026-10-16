package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.catalogo.service.RecalculoPorCotizacionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Recalcular ahora»: recalcula los precios de los articulos en dolares de la ferreteria del token con su dolar de
 * precios. Solo el Dueño: el rol se exige en SecurityConfig y de nuevo con @PreAuthorize. No recibe ningun dato (la
 * ferreteria sale solo del JWT) y no se frena por tickets abiertos.
 */
@RestController
@RequestMapping("/api/cotizacion")
public class RecalculoCotizacionController {

    private final RecalculoPorCotizacionService recalculoPorCotizacionService;

    public RecalculoCotizacionController(RecalculoPorCotizacionService recalculoPorCotizacionService) {
        this.recalculoPorCotizacionService = recalculoPorCotizacionService;
    }

    /** 200 con la cotizacion usada y cuantos articulos cambiaron; 409 si la cotizacion esta apagada o no hay ningun valor. */
    @PostMapping("/recalculo")
    @PreAuthorize("hasRole('DUENO')")
    public RecalculoCotizacionResponse recalcularAhora(@AuthenticationPrincipal AuthUser authUser) {
        return recalculoPorCotizacionService.recalcularAhora(authUser.userId());
    }
}
