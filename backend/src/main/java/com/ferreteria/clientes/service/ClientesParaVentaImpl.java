package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.ClienteReferencia;
import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ClientesParaVentaImpl implements ClientesParaVenta {

    private final ClienteRepository repositorio;

    public ClientesParaVentaImpl(ClienteRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClienteReferencia> buscar(Long id) {
        return repositorio.findScoped(id)
                .map(c -> new ClienteReferencia(c.getId(), c.getRazonSocial(), c.isActivo()));
    }

    /** Lee bajo {@code FOR UPDATE} (escalares: razón social y activo salen de la misma lectura actual, no de una foto). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ClienteReferencia> tomarParaVenta(Long id) {
        return repositorio.bloquearCuentaScoped(id)
                .map(c -> new ClienteReferencia(id, c.getRazonSocial(), c.getActivo()));
    }
}
