package com.ferreteria.ventas.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.ventas.service.RemitoEmitido;
import com.ferreteria.ventas.service.RemitoService;
import com.ferreteria.ventas.service.VentaRechazadaException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remitos (CU-07, G55). Los pueden emitir el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por "autenticado". La
 * ferreteria y el usuario salen solo del JWT.
 */
@RestController
@RequestMapping("/api/remitos")
public class RemitoController {

    private final RemitoService remitoService;

    public RemitoController(RemitoService remitoService) {
        this.remitoService = remitoService;
    }

    /** 201 si el remito es nuevo; 200 si la clave ya estaba registrada con el mismo contenido. */
    @PostMapping
    public ResponseEntity<RemitoResponse> emitir(
            @Valid @RequestBody EmitirRemitoRequest request, @AuthenticationPrincipal AuthUser authUser) {
        RemitoEmitido emitido = remitoService.emitir(request.aPedido(), authUser.userId());
        return ResponseEntity.status(emitido.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RemitoResponse.de(emitido));
    }

    /** Local: le gana al advice global. Los rechazos del ticket son los de la venta (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazado(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
