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
                .map(c -> new ClienteReferencia(c.getId(), c.getRazonSocial(), c.isActivo(),
                        condicion(c.getCondicionIva().name()),
                        c.getTipoDocumento() == null ? null : tipoDocumento(c.getTipoDocumento().name()),
                        c.getNumeroDocumento(), c.getDomicilio()));
    }

    /**
     * Lee bajo {@code FOR UPDATE} (escalares: razón social, activo y los datos fiscales salen de la misma lectura actual, no
     * de una foto). Es la ÚNICA vía de tomar al cliente: el primer paso de toda operación que mueve su cuenta, la venta y la
     * cobranza.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ClienteReferencia> tomarParaVenta(Long id) {
        return repositorio.bloquearCuentaScoped(id)
                .map(c -> new ClienteReferencia(id, c.getRazonSocial(), c.getActivo(), condicion(c.getCondicionIva()),
                        c.getTipoDocumento() == null ? null : tipoDocumento(c.getTipoDocumento()), c.getNumeroDocumento(),
                        c.getDomicilio()));
    }

    /** Las condiciones del modelo y las del puerto se llaman igual (lo prueba ClientesParaVentaImplTest). */
    private static ClienteReferencia.CondicionIva condicion(String nombre) {
        return ClienteReferencia.CondicionIva.valueOf(nombre);
    }

    /** Los tipos de documento del modelo y los del puerto se llaman igual (lo prueba ClientesParaVentaImplTest). */
    private static ClienteReferencia.TipoDocumento tipoDocumento(String nombre) {
        return ClienteReferencia.TipoDocumento.valueOf(nombre);
    }
}
