package com.ferreteria.compras.service;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.compras.model.Compra;
import com.ferreteria.compras.repository.CompraRepository;
import com.ferreteria.compras.repository.ItemCompraRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/** Listado y detalle de las compras de la ferreteria del request (CU-13). */
@Service
public class CompraConsulta {

    private static final int TAMANO_MAXIMO = 100;

    private final CompraRepository compraRepository;
    private final ItemCompraRepository itemRepository;

    public CompraConsulta(CompraRepository compraRepository, ItemCompraRepository itemRepository) {
        this.compraRepository = compraRepository;
        this.itemRepository = itemRepository;
    }

    /** Una fila del listado: la compra y cuantos renglones tiene. */
    public record ResumenCompra(Compra compra, long renglones) {
    }

    /** Mas nuevas primero. {@code desde} y {@code hasta} son dias, ambos inclusive; cada filtro nulo no filtra. */
    @Transactional(readOnly = true)
    public Page<ResumenCompra> listar(Long proveedorId, LocalDate desde, LocalDate hasta, int pagina, int tamano) {
        Page<Compra> compras = compraRepository.buscarScoped(proveedorId, desde, hasta,
                PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamano, 1), TAMANO_MAXIMO)));
        Map<Long, Long> renglones = itemRepository.renglonesPorCompraScoped(
                compras.getContent().stream().map(Compra::getId).toList());
        return compras.map(c -> new ResumenCompra(c, renglones.getOrDefault(c.getId(), 0L)));
    }

    /** La compra con sus renglones; la de otra ferreteria es 404, igual que una inexistente. */
    @Transactional(readOnly = true)
    public CompraRegistrada detalle(Long id) {
        Compra compra = compraRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compra no encontrada"));
        return new CompraRegistrada(compra, itemRepository.deCompraScoped(compra.getId()), false);
    }
}
