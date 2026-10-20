package com.ferreteria.clientes.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.common.exception.DatosInvalidosException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cuenta corriente de un cliente (CC-003, CC-007, CC-018, CC-039). La consulta la hacen el Dueño y el Vendedor, con las
 * cifras solo para el Dueño; el límite y los pendientes de cobro son solo del Dueño. Un cliente de otra ferretería da 404.
 */
@RestController
@RequestMapping("/api/clientes/{id}/cuenta-corriente")
public class CuentaCorrienteController {

    private final CuentaCorrienteService cuentaCorriente;

    public CuentaCorrienteController(CuentaCorrienteService cuentaCorriente) {
        this.cuentaCorriente = cuentaCorriente;
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

    @PutMapping("/limite")
    @PreAuthorize("hasRole('DUENO')")
    public CuentaClienteResponse cambiarLimite(@PathVariable Long id, @Valid @RequestBody LimiteCuentaRequest request) {
        if (!request.presente()) {
            throw new DatosInvalidosException(Map.of("limite",
                    "Falta el límite. Mandalo con un número o con null para deshabilitar la cuenta corriente."));
        }
        return CuentaClienteResponse.de(cuentaCorriente.cambiarLimite(id, request.limite()));
    }
}
