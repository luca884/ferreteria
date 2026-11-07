package com.ferreteria.catalogo.controller;

import com.ferreteria.catalogo.service.ExportacionArticulosService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

/** Exportacion de articulos a CSV (GDS-19). Solo el Dueño, como los reportes: lleva costos y margenes. */
@RestController
public class ExportacionArticulosController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ExportacionArticulosService service;
    private final Clock reloj;

    public ExportacionArticulosController(ExportacionArticulosService service, Clock reloj) {
        this.service = service;
        this.reloj = reloj;
    }

    /** La ruta literal le gana a /api/articulos/{id}. Mismos filtros que el listado. */
    @GetMapping("/api/articulos/exportacion")
    @PreAuthorize("hasRole('DUENO')")
    public ResponseEntity<byte[]> exportar(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean incluirInactivos,
            @RequestParam(defaultValue = "false") boolean enOferta) {
        String archivo = "articulos-" + LocalDate.now(reloj) + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(archivo).build().toString())
                .body(service.exportar(q, incluirInactivos, enOferta));
    }
}
