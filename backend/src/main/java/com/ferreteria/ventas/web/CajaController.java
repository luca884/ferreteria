package com.ferreteria.ventas.web;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.EgresoSinEfectivoException;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caja diaria (CU-10). Abrir, cargar ingresos y gastos y cerrar lo pueden el Dueno y el Vendedor; el retiro, el detalle
 * con totales (la caja del dia en vivo) y el historial, solo el Dueno. Una caja de otra ferreteria da 404.
 */
@RestController
@RequestMapping("/api/cajas")
public class CajaController {

    private final CajaService cajaService;

    public CajaController(CajaService cajaService) {
        this.cajaService = cajaService;
    }

    /** La caja abierta (sin totales) y el efectivo propuesto para abrir. Para los dos roles y para el mostrador. */
    @GetMapping("/actual")
    public CajaResponses.Vigente actual() {
        return CajaResponses.Vigente.de(cajaService.vigente());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CajaResponses.CajaAbierta abrir(
            @RequestBody CajaRequests.Apertura request, @AuthenticationPrincipal AuthUser authUser) {
        return CajaResponses.CajaAbierta.de(cajaService.abrir(request.efectivoInicial(), authUser.userId()));
    }

    /** Ingreso o gasto (los dos roles) o retiro (solo el Dueno: el Vendedor recibe 403). */
    @PostMapping("/{id}/movimientos")
    @ResponseStatus(HttpStatus.CREATED)
    public CajaResponses.Movimiento registrarMovimiento(@PathVariable Long id,
            @RequestBody CajaRequests.Movimiento request, @AuthenticationPrincipal AuthUser authUser) {
        return CajaResponses.Movimiento.de(cajaService.registrarMovimiento(id, request.tipo(), request.importe(),
                request.motivo(), Boolean.TRUE.equals(request.confirmaSinEfectivo()), authUser));
    }

    /** Gasto o retiro mayor al efectivo (GDS-36): 422 con el motivo y, solo para el Dueno, las cifras. */
    @ExceptionHandler(EgresoSinEfectivoException.class)
    public ResponseEntity<CajaResponses.EgresoRechazado> egresoSinEfectivo(EgresoSinEfectivoException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(CajaResponses.EgresoRechazado.de(e));
    }

    /** Cierre ciego: manda el contado y recien la respuesta trae los totales, el esperado y la diferencia. */
    @PostMapping("/{id}/cierre")
    public CajaResponses.Detalle cerrar(@PathVariable Long id, @RequestBody CajaRequests.Cierre request,
            @AuthenticationPrincipal AuthUser authUser) {
        return CajaResponses.Detalle.de(cajaService.cerrar(id, request.efectivoContado(), authUser.userId()));
    }

    /** El historial de cajas cerradas, mas nuevas primero. Solo el Dueno. */
    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public PageResponse<CajaResponses.Cerrada> cerradas(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return CajaResponses.Cerrada.pagina(cajaService.cerradas(page, size));
    }

    /** Una caja con sus totales: la del dia en vivo o una del historial. Solo el Dueno. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public CajaResponses.Detalle detalle(@PathVariable Long id) {
        return CajaResponses.Detalle.de(cajaService.detalle(id));
    }
}
