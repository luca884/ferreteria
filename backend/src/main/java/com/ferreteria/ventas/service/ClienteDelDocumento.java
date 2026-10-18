package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * El cliente que lleva una venta, un presupuesto o un remito (CL-004). Con {@code clienteId} el cliente tiene que ser
 * uno activo de la ferretería: el nombre del documento sale de su razón social, y el texto libre se ignora. Sin
 * {@code clienteId} vale el texto libre (o nada: Consumidor Final). El documento guarda el id y una copia del nombre.
 */
@Component
public class ClienteDelDocumento {

    /** El cliente tal como queda en el documento: el id (nulo si no se eligió uno) y el nombre a copiar. */
    public record Elegido(Long id, String nombre) {}

    private final ClientesParaVenta clientes;

    public ClienteDelDocumento(ClientesParaVenta clientes) {
        this.clientes = clientes;
    }

    public Elegido resolver(Long clienteId, String nombreLibre) {
        if (clienteId == null) {
            return new Elegido(null, nombreLibre);
        }
        ClienteReferencia cliente = clientes.buscar(clienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
        if (!cliente.activo()) {
            throw new ReglaDeNegocioException("El cliente está desactivado.");
        }
        return new Elegido(cliente.id(), cliente.razonSocial());
    }
}
