package com.ferreteria.clientes.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.TipoDocumento;

/** Un cliente tal como lo ve el mostrador. Lo que no se cargó no viaja. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClienteResponse(Long id, String razonSocial, TipoDocumento tipoDocumento, String numeroDocumento,
        CondicionIvaCliente condicionIva, String domicilio, String telefono, String whatsapp, String email,
        boolean activo) {

    public static ClienteResponse de(Cliente c) {
        return new ClienteResponse(c.getId(), c.getRazonSocial(), c.getTipoDocumento(), c.getNumeroDocumento(),
                c.getCondicionIva(), c.getDomicilio(), c.getTelefono(), c.getWhatsapp(), c.getEmail(), c.isActivo());
    }
}
