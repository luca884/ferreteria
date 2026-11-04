package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ProveedoresParaCompra;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.ferreteria.service.DatosFiscalesService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProveedoresParaCompraService implements ProveedoresParaCompra {

    private final ProveedorRepository proveedorRepository;
    private final DatosFiscalesService datosFiscalesService;

    public ProveedoresParaCompraService(ProveedorRepository proveedorRepository,
                                        DatosFiscalesService datosFiscalesService) {
        this.proveedorRepository = proveedorRepository;
        this.datosFiscalesService = datosFiscalesService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProveedorDeCompra> buscar(Long id) {
        return proveedorRepository.findScoped(id).map(ProveedoresParaCompraService::deCompra);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProveedorDeCompra> buscarPorCuit(List<String> cuits) {
        String propio = soloDigitos(datosFiscalesService.actuales().cuit());
        for (String cuit : cuits) {
            if (cuit.length() != 11 || cuit.equals(propio)) {
                continue;
            }
            Optional<Proveedor> proveedor = proveedorRepository.buscarActivoPorCuitScoped(
                    cuit.substring(0, 2) + "-" + cuit.substring(2, 10) + "-" + cuit.substring(10));
            if (proveedor.isPresent()) {
                return proveedor.map(ProveedoresParaCompraService::deCompra);
            }
        }
        return Optional.empty();
    }

    private static String soloDigitos(String cuit) {
        return cuit == null ? "" : cuit.replaceAll("\\D", "");
    }

    private static ProveedorDeCompra deCompra(Proveedor p) {
        return new ProveedorDeCompra(p.getId(), p.getNombre(), p.isActivo());
    }
}
