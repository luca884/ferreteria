package com.ferreteria.stock.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.stock.model.ModoAjuste;
import com.ferreteria.stock.service.StockService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ajustar el stock de un articulo (CU-09), solo el Dueno. Vive en el modulo stock aunque la ruta cuelgue del
 * articulo: el movimiento es de stock y el catalogo solo se toca por su puerto. Un articulo ajeno da 404.
 */
@RestController
@RequestMapping("/api/articulos")
public class AjusteStockController {

    private final StockService stockService;

    public AjusteStockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/{id}/ajustes-stock")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public AjusteStockResponse ajustar(
            @PathVariable Long id, @RequestBody AjusteStockRequest request, @AuthenticationPrincipal AuthUser authUser) {
        if (request.modo() == ModoAjuste.CANTIDAD) {
            return AjusteStockResponse.de(stockService.ajustarPorCantidad(
                    id, request.cantidad(), request.motivo(), request.detalle(), authUser.userId()));
        }
        return AjusteStockResponse.de(stockService.ajustar(
                id, request.stockReal(), request.motivo(), request.detalle(), authUser.userId()));
    }
}
