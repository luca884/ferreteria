package com.ferreteria.ventas.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.service.DetalleRemito;
import com.ferreteria.ventas.service.RemitoEmitido;
import com.ferreteria.ventas.service.RemitoGestion;
import com.ferreteria.ventas.service.RemitoService;
import com.ferreteria.ventas.service.VentaRechazadaException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Remitos (CU-07, G55). Los pueden emitir, listar y ver el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por
 * "autenticado". Anular devuelve stock y es solo del Dueno. La ferreteria y el usuario salen solo del JWT.
 */
@RestController
@RequestMapping("/api/remitos")
public class RemitoController {

    private final RemitoService remitoService;
    private final RemitoGestion remitoGestion;

    public RemitoController(RemitoService remitoService, RemitoGestion remitoGestion) {
        this.remitoService = remitoService;
        this.remitoGestion = remitoGestion;
    }

    /** 201 si el remito es nuevo; 200 si la clave ya estaba registrada con el mismo contenido. */
    @PostMapping
    public ResponseEntity<RemitoResponse> emitir(
            @Valid @RequestBody EmitirRemitoRequest request, @AuthenticationPrincipal AuthUser authUser) {
        RemitoEmitido emitido = remitoService.emitir(request.aPedido(), authUser.userId());
        return ResponseEntity.status(emitido.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RemitoResponse.de(emitido));
    }

    /** Los de la ferreteria, mas nuevos primero. {@code desde} y {@code hasta} son dias (aaaa-mm-dd), ambos inclusive. */
    @GetMapping
    public PageResponse<RemitoResumenResponse> listar(
            @RequestParam(required = false) EstadoRemito estado, @RequestParam(required = false) Long numero,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(remitoGestion.listar(estado, numero, desde, hasta, page, size),
                RemitoResumenResponse::de);
    }

    @GetMapping("/{id}")
    public RemitoResponse detalle(@PathVariable Long id) {
        DetalleRemito detalle = remitoGestion.detalle(id);
        return RemitoResponse.de(detalle.remito(), detalle.items());
    }

    /** Anula un EMITIDO y devuelve el stock. Solo el Dueno: la devolucion de mercaderia no la hace cualquier vendedor. */
    @PatchMapping("/{id}/anular")
    @PreAuthorize("hasRole('DUENO')")
    public RemitoResponse anular(@PathVariable Long id, @AuthenticationPrincipal AuthUser authUser) {
        DetalleRemito detalle = remitoGestion.anular(id, authUser.userId());
        return RemitoResponse.de(detalle.remito(), detalle.items());
    }

    /** Local: le gana al advice global. Los rechazos del ticket son los de la venta (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazado(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
