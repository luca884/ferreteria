package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.catalogo.dto.VistaPreviaRecalculoResponse;
import com.ferreteria.catalogo.service.RecalculoConReintento;
import com.ferreteria.catalogo.service.RecalculoPorCotizacionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * «Recalcular ahora»: recalcula los precios de los articulos en dolares de la ferreteria del token con su dolar de
 * precios, y su vista previa. Solo el Dueño: el rol se exige en SecurityConfig y de nuevo con @PreAuthorize. La
 * ferreteria sale solo del JWT y no se frena por tickets abiertos.
 */
@RestController
@RequestMapping("/api/cotizacion")
public class RecalculoCotizacionController {

    private final RecalculoConReintento recalculoConReintento;
    private final RecalculoPorCotizacionService recalculoPorCotizacionService;

    public RecalculoCotizacionController(RecalculoConReintento recalculoConReintento,
                                         RecalculoPorCotizacionService recalculoPorCotizacionService) {
        this.recalculoConReintento = recalculoConReintento;
        this.recalculoPorCotizacionService = recalculoPorCotizacionService;
    }

    /**
     * 200 con la cotizacion usada y cuantos articulos cambiaron; 409 si la cotizacion esta apagada, si no hay ningun
     * valor, si el dolar se aleja mas del umbral de la ferreteria del vigente y el Dueno no lo confirmo con
     * {@code ?confirmaDesvio=true}, o si {@code ?dolar=} (el de la vista previa) ya no es el ultimo guardado.
     */
    @PostMapping("/recalculo")
    @PreAuthorize("hasRole('DUENO')")
    public RecalculoCotizacionResponse recalcularAhora(@AuthenticationPrincipal AuthUser authUser,
                                                       @RequestParam(defaultValue = "false") boolean confirmaDesvio,
                                                       @RequestParam(required = false) BigDecimal dolar) {
        return recalculoConReintento.recalcularAhora(authUser.userId(), confirmaDesvio, dolar);
    }

    /**
     * Vista previa de «Recalcular ahora»: el dolar que se fijaria y cada articulo en dolares con su precio actual y el
     * nuevo, sin tocar nada. 409 si la cotizacion esta apagada o no hay ningun valor. Para aplicar lo que se vio, el POST
     * manda {@code ?dolar=} con el valor de la vista previa (y {@code confirmaDesvio=true} si supera el umbral).
     */
    @GetMapping("/recalculo/vista-previa")
    @PreAuthorize("hasRole('DUENO')")
    public VistaPreviaRecalculoResponse vistaPrevia() {
        return recalculoPorCotizacionService.vistaPrevia();
    }
}
