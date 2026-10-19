package com.ferreteria.clientes.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.clientes.service.CuentaDelCliente;
import java.math.BigDecimal;

/**
 * La cuenta corriente de un cliente por rol (CC-007). Todos ven {@code habilitada} y, si se consultó con un importe,
 * {@code alcanza}. Las cifras ({@code limite}, {@code saldo}, {@code disponible}) las trae solo la vista del Dueño: al
 * Vendedor ni se las arma el servicio, y lo que es null no viaja.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CuentaClienteResponse(boolean habilitada, Boolean alcanza, BigDecimal limite, BigDecimal saldo,
        BigDecimal disponible) {

    public static CuentaClienteResponse de(CuentaDelCliente c) {
        return new CuentaClienteResponse(c.habilitada(), c.alcanza(), c.limite(), c.saldo(), c.disponible());
    }
}
