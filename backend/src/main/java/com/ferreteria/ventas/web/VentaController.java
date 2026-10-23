package com.ferreteria.ventas.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.service.VentaGestion;
import com.ferreteria.ventas.service.VentaGestion.VentaConsultada;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Cobrar un ticket (CU-07). Lo pueden usar el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por
 * "autenticado". La ferreteria, el usuario y su rol salen solo del JWT: el rol decide quien puede autorizar un exceso del limite
 * de cuenta corriente (CC-005). Consultar las ventas hechas (G60) es solo del Dueno: con el listado el Vendedor podria
 * sumar el efectivo del dia y el cierre de caja dejaria de ser ciego (CU-10).
 */
@RestController
@RequestMapping("/api/ventas")
public class VentaController {

    private final VentaService ventaService;
    private final VentaGestion ventaGestion;

    public VentaController(VentaService ventaService, VentaGestion ventaGestion) {
        this.ventaService = ventaService;
        this.ventaGestion = ventaGestion;
    }

    /** 201 si la venta es nueva; 200 si la clave ya estaba registrada con el mismo contenido (R-011). */
    @PostMapping
    public ResponseEntity<VentaResponse> cobrar(
            @Valid @RequestBody ConfirmarVentaRequest request, @AuthenticationPrincipal AuthUser authUser) {
        VentaRegistrada venta = ventaService.cobrar(request.aPedido(), authUser.userId(), authUser.rol() == Rol.DUENO);
        return ResponseEntity.status(venta.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(VentaResponse.de(venta));
    }

    /**
     * Las de la ferreteria, mas nuevas primero. {@code desde} y {@code hasta} son dias (aaaa-mm-dd), ambos inclusive;
     * {@code cliente} busca el texto dentro del nombre del cliente.
     */
    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public PageResponse<VentaResumenResponse> listar(
            @RequestParam(required = false) Long numero, @RequestParam(required = false) String cliente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(ventaGestion.listar(numero, cliente, desde, hasta, page, size),
                VentaResumenResponse::de);
    }

    /** La venta como al cobrarla (renglones y pagos) y quien la cobro, para verla y reimprimirla. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public VentaResponse detalle(@PathVariable Long id) {
        VentaConsultada consultada = ventaGestion.detalle(id);
        return VentaResponse.de(consultada.venta(), consultada.usuario());
    }

    /** Local: le gana al advice global. El estado sale del motivo (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazada(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
