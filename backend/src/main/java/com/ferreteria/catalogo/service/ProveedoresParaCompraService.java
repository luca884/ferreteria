package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ProveedoresParaCompra;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ProveedoresParaCompraService implements ProveedoresParaCompra {

    private final ProveedorRepository proveedorRepository;

    public ProveedoresParaCompraService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProveedorDeCompra> buscar(Long id) {
        return proveedorRepository.findScoped(id)
                .map(p -> new ProveedorDeCompra(p.getId(), p.getNombre(), p.isActivo()));
    }
}
