package com.ferreteria.clientes.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.service.AjusteCuentaService;
import com.ferreteria.clientes.service.AjusteRegistrado;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.ExtractoCuenta;
import com.ferreteria.clientes.service.FiltroExtracto;
import com.ferreteria.common.exception.DatosInvalidosException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cuenta corriente de un cliente (CC-003, CC-007, CC-018, CC-039, GDS-20). La consulta la hacen el Dueño y el Vendedor, con las
 * cifras solo para el Dueño; el límite, los pendientes de cobro y los ajustes (Recargo o Descuento) son solo del Dueño. Un cliente
 * de otra ferretería da 404.
 */
@RestController
@RequestMapping("/api/clientes/{id}/cuenta-corriente")
public class CuentaCorrienteController {

    private final CuentaCorrienteService cuentaCorriente;
    private final ExtractoCuenta extractoCuenta;
    private final AjusteCuentaService ajusteCuenta;

    public CuentaCorrienteController(CuentaCorrienteService cuentaCorriente, ExtractoCuenta extractoCuenta,
            AjusteCuentaService ajusteCuenta) {
        this.cuentaCorriente = cuentaCorriente;
        this.extractoCuenta = extractoCuenta;
        this.ajusteCuenta = ajusteCuenta;
    }

    /** Si hay {@code importe}, dice si alcanza: es un consejo para el cobro, la venta lo vuelve a decidir bajo lock. */
    @GetMapping
    public CuentaClienteResponse consultar(@PathVariable Long id, @RequestParam(required = false) BigDecimal importe,
            @AuthenticationPrincipal AuthUser authUser) {
        return CuentaClienteResponse.de(cuentaCorriente.consultar(id, importe, authUser.rol() == Rol.DUENO));
    }

    /**
     * Los comprobantes con deuda, el saldo y lo que está «a cuenta» (CC-039), solo para el Dueño: es la base de la cobranza. Es un
     * consejo de solo lectura que no toma ningún lock; lo que se puede imputar lo decide el cobro, bajo lock.
     */
    @GetMapping("/pendientes")
    @PreAuthorize("hasRole('DUENO')")
    public PendientesResponse pendientes(@PathVariable Long id) {
        return PendientesResponse.de(cuentaCorriente.pendientes(id));
    }

    /**
     * El extracto de la cuenta (CC-072..CC-080), solo para el Dueño (matcher de {@code SecurityConfig} + {@code @PreAuthorize}: el
     * Vendedor recibe 403 aunque mande parámetros inválidos). Lectura de una sola foto, sin locks. Orden: 403 → 400 (parámetro mal
     * formado o {@code desde > hasta}) → 404. Un cliente desactivado se consulta. {@code size} se acota a [1, 100], no se rechaza.
     */
    @GetMapping("/extracto")
    @PreAuthorize("hasRole('DUENO')")
    public ExtractoResponse extracto(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) TipoMovimientoCuenta tipo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(defaultValue = "false") boolean impresion) {
        return ExtractoResponse.de(extractoCuenta.extracto(id, new FiltroExtracto(desde, hasta, tipo, page, size, impresion)));
    }

    @PutMapping("/limite")
    @PreAuthorize("hasRole('DUENO')")
    public CuentaClienteResponse cambiarLimite(@PathVariable Long id, @Valid @RequestBody LimiteCuentaRequest request) {
        if (!request.presente()) {
            throw new DatosInvalidosException(Map.of("limite",
                    "Falta el límite. Mandalo con un número o con null para deshabilitar la cuenta corriente."));
        }
        return CuentaClienteResponse.de(cuentaCorriente.cambiarLimite(id, request.limite()));
    }

    /**
     * Carga un ajuste manual de la cuenta (GDS-20): un Recargo sube la deuda y un Descuento la baja, con motivo obligatorio y una
     * clave de idempotencia. 201 si es nuevo; 200 si la clave ya estaba registrada con el mismo contenido. Solo el Dueño (matcher
     * de {@code SecurityConfig} + {@code @PreAuthorize}). No usa la caja. Sin {@code @Transactional}: la fachada abre una
     * transacción por intento y se niega a correr dentro de una ajena. La ferretería y el usuario salen del JWT.
     */
    @PostMapping("/ajustes")
    @PreAuthorize("hasRole('DUENO')")
    public ResponseEntity<AjusteCuentaResponse> ajustar(@PathVariable Long id, @RequestBody AjusteCuentaRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        AjusteRegistrado ajuste = ajusteCuenta.ajustar(request.aPedido(id), authUser.userId());
        return ResponseEntity.status(ajuste.repetida() ? HttpStatus.OK : HttpStatus.CREATED).body(AjusteCuentaResponse.de(ajuste));
    }

    /** Local: le gana al advice global. Lo imputado excede la deuda actual de alguna partida: 409 con las deudas actuales. */
    @ExceptionHandler(ImputacionInvalidaException.class)
    public ResponseEntity<ImputacionExcedidaResponse> imputacionExcedida(ImputacionInvalidaException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ImputacionExcedidaResponse.de(e,
                "La deuda de alguna partida cambió. Revisá la imputación y confirmá de nuevo."));
    }
}
