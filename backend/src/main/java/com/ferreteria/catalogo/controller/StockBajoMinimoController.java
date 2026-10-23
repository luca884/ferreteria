package com.ferreteria.catalogo.controller;

import com.ferreteria.catalogo.model.ArticuloBajoMinimo;
import com.ferreteria.catalogo.model.OrdenBajoMinimo;
import com.ferreteria.catalogo.service.StockBajoMinimoService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Articulos bajo el stock minimo (CU-09, G72). Solo el Dueño, como los reportes: muestra el costo. */
@RestController
public class StockBajoMinimoController {

    private final StockBajoMinimoService service;

    public StockBajoMinimoController(StockBajoMinimoService service) {
        this.service = service;
    }

    @GetMapping("/api/articulos/bajo-minimo")
    @PreAuthorize("hasRole('DUENO')")
    public List<ArticuloBajoMinimo> listar(
            @RequestParam(defaultValue = "DESCRIPCION") OrdenBajoMinimo orden) {
        return service.listar(orden);
    }
}
