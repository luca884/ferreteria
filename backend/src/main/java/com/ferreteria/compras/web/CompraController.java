package com.ferreteria.compras.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.compras.service.CompraConsulta;
import com.ferreteria.compras.service.CompraRegistrada;
import com.ferreteria.compras.service.CompraService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Compras al proveedor (CU-13, tramo 1). Solo el Dueno: la compra cambia costos y precios. La ferreteria y el usuario
 * salen solo del JWT.
 */
@RestController
@RequestMapping("/api/compras")
@PreAuthorize("hasRole('DUENO')")
public class CompraController {

    private final CompraService compraService;
    private final CompraConsulta compraConsulta;

    public CompraController(CompraService compraService, CompraConsulta compraConsulta) {
        this.compraService = compraService;
        this.compraConsulta = compraConsulta;
    }

    /** Lo que haria la compra, sin guardar nada. Con las mismas validaciones que registrar, incluido el 409. */
    @PostMapping("/vista-previa")
    public PlanCompraResponse vistaPrevia(@Valid @RequestBody CompraRequest request) {
        return PlanCompraResponse.de(compraService.vistaPrevia(request.aPedido()));
    }

    /** 201 si la compra es nueva; 200 si la clave ya estaba registrada con el mismo contenido. */
    @PostMapping
    public ResponseEntity<CompraResponse> registrar(
            @Valid @RequestBody CompraRequest request, @AuthenticationPrincipal AuthUser authUser) {
        if (request.clave() == null) {
            throw new DatosInvalidosException(Map.of("clave", "Falta la clave de la compra."));
        }
        CompraRegistrada registrada = compraService.registrar(request.aPedido(), authUser.userId());
        return ResponseEntity.status(registrada.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CompraResponse.de(registrada.compra(), registrada.items()));
    }

    /** Las de la ferreteria, mas nuevas primero. {@code desde} y {@code hasta} son dias (aaaa-mm-dd), ambos inclusive. */
    @GetMapping
    public PageResponse<CompraResumenResponse> listar(
            @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(compraConsulta.listar(proveedorId, desde, hasta, page, size),
                CompraResumenResponse::de);
    }

    @GetMapping("/{id}")
    public CompraResponse detalle(@PathVariable Long id) {
        CompraRegistrada detalle = compraConsulta.detalle(id);
        return CompraResponse.de(detalle.compra(), detalle.items());
    }
}
