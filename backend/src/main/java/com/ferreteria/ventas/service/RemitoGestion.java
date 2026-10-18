package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.repository.ItemRemitoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Listar, ver y anular los remitos de la ferreteria del request (RM-002, RM-005). Uno de otra ferreteria es
 * indistinguible de uno que no existe: 404.
 */
@Service
public class RemitoGestion {

    static final int TAMANO_MAXIMO = 100;
    static final String MENSAJE_NO_ENCONTRADO = "Remito no encontrado";
    static final String MENSAJE_FACTURADO = "Ese remito ya se facturó.";
    static final String MENSAJE_ANULADO = "Ese remito fue anulado.";

    private final RemitoRepository remitoRepository;
    private final ItemRemitoRepository itemRepository;
    private final StockService stockService;

    public RemitoGestion(
            RemitoRepository remitoRepository, ItemRemitoRepository itemRepository, StockService stockService) {
        this.remitoRepository = remitoRepository;
        this.itemRepository = itemRepository;
        this.stockService = stockService;
    }

    /** Los mas nuevos primero. {@code desde} y {@code hasta} son dias, ambos inclusive; un filtro nulo no filtra. */
    @Transactional(readOnly = true)
    public Page<Remito> listar(
            EstadoRemito estado, Long numero, LocalDate desde, LocalDate hasta, int page, int size) {
        return remitoRepository.buscarScoped(estado, numero,
                desde == null ? null : desde.atStartOfDay(),
                hasta == null ? null : hasta.plusDays(1).atStartOfDay(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
    }

    @Transactional(readOnly = true)
    public DetalleRemito detalle(Long id) {
        Remito remito = remitoRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADO));
        return new DetalleRemito(remito, itemRepository.deRemitoScoped(id));
    }

    /**
     * Anula un EMITIDO y devuelve al stock lo que salio, en la misma transaccion (RM-005). Con lock exclusivo, para
     * ordenarse con la venta que lo factura. Uno ya FACTURADO es 409; anular uno ANULADO no hace nada y devuelve el remito.
     */
    @Transactional
    public DetalleRemito anular(Long id, Long usuarioId) {
        Remito remito = remitoRepository.paraCambiarScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADO));
        List<ItemRemito> items = itemRepository.deRemitoScoped(id);
        if (remito.getEstado() == EstadoRemito.FACTURADO) {
            throw new ConflictException(MENSAJE_FACTURADO);
        }
        if (remito.getEstado() == EstadoRemito.EMITIDO) {
            stockService.devolverRemito(remito.getId(), usuarioId,
                    items.stream().map(i -> new SalidaStock(i.getArticuloId(), i.getCantidad())).toList());
            remito.cambiarEstado(EstadoRemito.ANULADO);
        }
        return new DetalleRemito(remito, items);
    }
}
