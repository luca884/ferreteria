package com.ferreteria.ventas.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.service.DetallePresupuesto;
import com.ferreteria.ventas.service.PresupuestoEmitido;
import com.ferreteria.ventas.service.PresupuestoGestion;
import com.ferreteria.ventas.service.PresupuestoService;
import com.ferreteria.ventas.service.VentaRechazadaException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Presupuestos (CU-07, GDS-13). Los pueden usar el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por
 * "autenticado". La ferreteria y el usuario salen solo del JWT.
 */
@RestController
@RequestMapping("/api/presupuestos")
public class PresupuestoController {

    private final PresupuestoService presupuestoService;
    private final PresupuestoGestion presupuestoGestion;

    public PresupuestoController(PresupuestoService presupuestoService, PresupuestoGestion presupuestoGestion) {
        this.presupuestoService = presupuestoService;
        this.presupuestoGestion = presupuestoGestion;
    }

    /** 201 si el presupuesto es nuevo; 200 si la clave ya estaba registrada con el mismo contenido. */
    @PostMapping
    public ResponseEntity<PresupuestoResponse> emitir(
            @Valid @RequestBody EmitirPresupuestoRequest request, @AuthenticationPrincipal AuthUser authUser) {
        PresupuestoEmitido emitido = presupuestoService.emitir(request.aPedido(), authUser.userId());
        return ResponseEntity.status(emitido.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PresupuestoResponse.de(emitido));
    }

    /** Los de la ferreteria, mas nuevos primero. {@code desde} y {@code hasta} son dias (aaaa-mm-dd), ambos inclusive. */
    @GetMapping
    public PageResponse<PresupuestoResumenResponse> listar(
            @RequestParam(required = false) EstadoPresupuesto estado, @RequestParam(required = false) Long numero,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(presupuestoGestion.listar(estado, numero, desde, hasta, page, size),
                PresupuestoResumenResponse::de);
    }

    @GetMapping("/{id}")
    public PresupuestoResponse detalle(@PathVariable Long id) {
        DetallePresupuesto detalle = presupuestoGestion.detalle(id);
        return PresupuestoResponse.de(detalle.presupuesto(), detalle.items());
    }

    /**
     * Pasa a PENDIENTE, ACEPTADO o RECHAZADO. FACTURADO solo lo pone la venta hecha desde el presupuesto. Sacarlo de
     * RECHAZADO es solo del Dueño.
     */
    @PatchMapping("/{id}/estado")
    public PresupuestoResponse cambiarEstado(@PathVariable Long id, @RequestBody CambiarEstadoPresupuestoRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        DetallePresupuesto detalle = presupuestoGestion.cambiarEstado(id, request.estado(), authUser.rol() == Rol.DUENO);
        return PresupuestoResponse.de(detalle.presupuesto(), detalle.items());
    }

    /** Local: le gana al advice global. Los rechazos del ticket son los de la venta (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazado(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
