package com.ferreteria.clientes.service;

import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.TipoDocumento;

/** Lo que se pide guardar de un cliente, ya sin detalles HTTP. Un texto en blanco es lo mismo que no mandarlo. */
public record DatosCliente(String razonSocial, TipoDocumento tipoDocumento, String numeroDocumento,
        CondicionIvaCliente condicionIva, String domicilio, String telefono, String whatsapp, String email) {}
