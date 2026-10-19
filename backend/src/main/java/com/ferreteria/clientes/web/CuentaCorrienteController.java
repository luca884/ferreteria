package com.ferreteria.clientes.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
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
 * La cuenta corriente de un cliente (CC-003, CC-007, CC-018). La consulta la hacen el Dueño y el Vendedor, con las cifras solo
 * para el Dueño; el límite lo fija solo el Dueño. Un cliente de otra ferretería da 404.
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

    @PutMapping("/limite")
    @PreAuthorize("hasRole('DUENO')")
    public CuentaClienteResponse cambiarLimite(@PathVariable Long id, @Valid @RequestBody LimiteCuentaRequest request) {
        return CuentaClienteResponse.de(cuentaCorriente.cambiarLimite(id, request.limite()));
    }
}
