package com.ferreteria.ventas.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.CobroRegistrado;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cobranza de cuenta corriente (CC-028, CC-029) y consulta del recibo: solo el Dueño (el Vendedor recibe 403; el 403 también se
 * exige en {@code SecurityConfig}, antes de leer el cuerpo). La ferretería y el usuario salen solo del JWT.
 */
@RestController
@PreAuthorize("hasRole('DUENO')")
public class CobranzaController {

    private final CobranzaService cobranzaService;

    public CobranzaController(CobranzaService cobranzaService) {
        this.cobranzaService = cobranzaService;
    }

    /** 201 si el cobro es nuevo; 200 si la clave ya estaba registrada con el mismo contenido (CC-038). */
    @PostMapping("/api/cobranzas")
    public ResponseEntity<ReciboResponse> cobrar(
            @Valid @RequestBody CobranzaRequest request, @AuthenticationPrincipal AuthUser authUser) {
        CobroRegistrado cobro = cobranzaService.cobrar(request.aPedido(), authUser.userId());
        return ResponseEntity.status(cobro.repetida() ? HttpStatus.OK : HttpStatus.CREATED).body(ReciboResponse.de(cobro));
    }

    /** El recibo para verlo o reimprimirlo: dice lo mismo que la cobranza que lo creó. De otra ferretería, 404. */
    @GetMapping("/api/recibos/{id}")
    public ReciboResponse recibo(@PathVariable Long id) {
        return ReciboResponse.de(cobranzaService.recibo(id));
    }

    /** Local: le gana al advice global. El estado sale del motivo (409 o 422). */
    @ExceptionHandler(CobranzaRechazadaException.class)
    public ResponseEntity<CobranzaRechazadaResponse> rechazada(CobranzaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(CobranzaRechazadaResponse.de(e));
    }
}
