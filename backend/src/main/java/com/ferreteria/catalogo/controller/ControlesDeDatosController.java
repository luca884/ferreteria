package com.ferreteria.catalogo.controller;

import com.ferreteria.catalogo.model.ArticuloEnControl;
import com.ferreteria.catalogo.model.ControlDeDatos;
import com.ferreteria.catalogo.service.ControlesDeDatosService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Controles de datos del catalogo (GDS-18, G81). Solo el Dueño, como los reportes: muestra el costo. */
@RestController
public class ControlesDeDatosController {

    private final ControlesDeDatosService service;

    public ControlesDeDatosController(ControlesDeDatosService service) {
        this.service = service;
    }

    @GetMapping("/api/articulos/controles")
    @PreAuthorize("hasRole('DUENO')")
    public List<ArticuloEnControl> listar(@RequestParam ControlDeDatos control) {
        return service.listar(control);
    }
}
