package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.ImportacionResponse;
import com.ferreteria.catalogo.dto.MuestraResponse;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaResponse;
import com.ferreteria.catalogo.model.ArchivoLista;
import com.ferreteria.catalogo.service.ImportacionService;
import com.ferreteria.common.exception.BadRequestException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

/**
 * Importacion de listas de precios de un proveedor (CU-04). Solo el Dueno. Los tres pedidos que suben un archivo son
 * multipart: la parte "archivo" y, salvo en la muestra, la parte "datos" (JSON).
 */
@RestController
@RequestMapping("/api/importaciones")
public class ImportacionController {

    private final ImportacionService importacionService;

    public ImportacionController(ImportacionService importacionService) {
        this.importacionService = importacionService;
    }

    /** Todas las hojas del archivo con sus filas crudas y la propuesta de mapeo de columnas. */
    @PostMapping(path = "/muestra", consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('DUENO')")
    public MuestraResponse muestra(@RequestPart("archivo") MultipartFile archivo) {
        return importacionService.muestra(aArchivoLista(archivo));
    }

    /** Lo que pasaria al aplicar la lista; no escribe nada. */
    @PostMapping(path = "/vista-previa", consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('DUENO')")
    public VistaPreviaResponse vistaPrevia(
            @RequestPart("archivo") MultipartFile archivo, @Valid @RequestPart("datos") VistaPreviaRequest datos) {
        return importacionService.vistaPrevia(aArchivoLista(archivo), datos);
    }

    /** Aplica la lista en una sola transaccion. */
    @PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public ImportacionResponse aplicar(
            @RequestPart("archivo") MultipartFile archivo,
            @Valid @RequestPart("datos") AplicarRequest datos,
            @AuthenticationPrincipal AuthUser authUser) {
        return importacionService.aplicar(aArchivoLista(archivo), datos, authUser.userId());
    }

    /** El historial de importaciones de la ferreteria, la mas reciente primero; con {@code proveedorId}, las de ese proveedor. */
    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public List<ImportacionResponse> listar(@RequestParam(required = false) Long proveedorId) {
        return importacionService.listar(proveedorId);
    }

    private static ArchivoLista aArchivoLista(MultipartFile archivo) {
        try {
            return new ArchivoLista(archivo.getOriginalFilename(), archivo.getBytes());
        } catch (IOException e) {
            throw new BadRequestException("No se pudo leer el archivo. Verificá que no esté dañado.");
        }
    }
}
