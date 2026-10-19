package com.ferreteria.ventas.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaService;
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
 * Cobrar un ticket (CU-07). Lo pueden usar el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por
 * "autenticado". La ferreteria, el usuario y su rol salen solo del JWT: el rol decide quien puede autorizar un exceso del limite
 * de cuenta corriente (CC-005).
 */
@RestController
@RequestMapping("/api/ventas")
public class VentaController {

    private final VentaService ventaService;

    public VentaController(VentaService ventaService) {
        this.ventaService = ventaService;
    }

    /** 201 si la venta es nueva; 200 si la clave ya estaba registrada con el mismo contenido (R-011). */
    @PostMapping
    public ResponseEntity<VentaResponse> cobrar(
            @Valid @RequestBody ConfirmarVentaRequest request, @AuthenticationPrincipal AuthUser authUser) {
        VentaRegistrada venta = ventaService.cobrar(request.aPedido(), authUser.userId(), authUser.rol() == Rol.DUENO);
        return ResponseEntity.status(venta.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(VentaResponse.de(venta));
    }

    /** Local: le gana al advice global. El estado sale del motivo (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazada(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
