package com.ferreteria.stock.web;

import com.ferreteria.common.PageResponse;
import com.ferreteria.stock.service.MovimientosArticulo;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Movimientos de stock de un articulo (G29), solo el Dueño, como la consulta de ventas. Vive en el modulo stock aunque
 * la ruta cuelgue del articulo. Un articulo ajeno da 404.
 */
@RestController
@RequestMapping("/api/articulos")
public class MovimientosArticuloController {

    private final MovimientosArticulo movimientosArticulo;

    public MovimientosArticuloController(MovimientosArticulo movimientosArticulo) {
        this.movimientosArticulo = movimientosArticulo;
    }

    @GetMapping("/{id}/movimientos-stock")
    @PreAuthorize("hasRole('DUENO')")
    public PageResponse<MovimientosArticulo.Movimiento> listar(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(movimientosArticulo.listar(id, desde, hasta, page, size), Function.identity());
    }
}
