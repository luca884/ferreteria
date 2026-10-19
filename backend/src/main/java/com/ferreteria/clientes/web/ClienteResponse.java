package com.ferreteria.clientes.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.TipoDocumento;
import java.math.BigDecimal;

/**
 * Un cliente tal como lo ve el mostrador. Lo que no se cargó no viaja. Todos los roles ven si tiene la cuenta corriente
 * habilitada; el saldo y el límite (CC-007, CC-019) los ve solo el Dueño: para el Vendedor no se arman y no viajan. Un límite
 * NULL (sin cuenta habilitada) tampoco viaja: lo dice {@code cuentaCorrienteHabilitada}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClienteResponse(Long id, String razonSocial, TipoDocumento tipoDocumento, String numeroDocumento,
        CondicionIvaCliente condicionIva, String domicilio, String telefono, String whatsapp, String email,
        boolean activo, boolean cuentaCorrienteHabilitada, BigDecimal limiteCuentaCorriente, BigDecimal saldo) {

    /** {@code conCifras}: el rol puede ver el saldo y el límite (el Dueño). */
    public static ClienteResponse de(Cliente c, boolean conCifras) {
        return new ClienteResponse(c.getId(), c.getRazonSocial(), c.getTipoDocumento(), c.getNumeroDocumento(),
                c.getCondicionIva(), c.getDomicilio(), c.getTelefono(), c.getWhatsapp(), c.getEmail(), c.isActivo(),
                c.getLimiteCuentaCorriente() != null, conCifras ? c.getLimiteCuentaCorriente() : null,
                conCifras ? c.getSaldo() : null);
    }
}
