package com.ferreteria.ventas.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.ventas.service.AnulacionService;
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
 * Cobranza de cuenta corriente (CC-028, CC-029), consulta y anulación del recibo (CC-055): solo el Dueño (el Vendedor recibe 403; el 403 también se
 * exige en {@code SecurityConfig}, antes de leer el cuerpo). La ferretería y el usuario salen solo del JWT.
 */
@RestController
@PreAuthorize("hasRole('DUENO')")
public class CobranzaController {

    private final CobranzaService cobranzaService;
    private final AnulacionService anulacionService;

    public CobranzaController(CobranzaService cobranzaService, AnulacionService anulacionService) {
        this.cobranzaService = cobranzaService;
        this.anulacionService = anulacionService;
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

    /**
     * Anula el cobro de un recibo (CC-055..CC-057): 200 con el recibo ya ANULADO. El cuerpo es opcional a propósito: sin cuerpo el
     * motivo queda ausente y el servicio da el 400 en {@code motivo}. Sin {@code @Transactional}: la fachada abre una transacción
     * por intento y se niega a correr dentro de una ajena (CC-091). Los rechazos (404, 422, 409) y el 503 los resuelven la fachada
     * y los handlers; acá solo se traduce. La ferretería y el usuario salen del JWT.
     */
    @PostMapping("/api/recibos/{id}/anulacion")
    public ReciboResponse anular(@PathVariable Long id, @Valid @RequestBody(required = false) AnulacionRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        String motivo = request == null ? null : request.motivo();
        return ReciboResponse.de(anulacionService.anular(id, motivo, authUser.userId()));
    }

    /** Local: le gana al advice global. El estado sale del motivo (409 o 422). */
    @ExceptionHandler(CobranzaRechazadaException.class)
    public ResponseEntity<CobranzaRechazadaResponse> rechazada(CobranzaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(CobranzaRechazadaResponse.de(e));
    }
}
