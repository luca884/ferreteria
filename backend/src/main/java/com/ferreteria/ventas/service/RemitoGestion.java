package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemRemitoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    private final ComprobanteRepository comprobanteRepository;
    private final FacturacionParaVenta facturacion;
    private final CajaRepository cajaRepository;

    public RemitoGestion(RemitoRepository remitoRepository, ItemRemitoRepository itemRepository,
            StockService stockService, ComprobanteRepository comprobanteRepository, FacturacionParaVenta facturacion,
            CajaRepository cajaRepository) {
        this.remitoRepository = remitoRepository;
        this.itemRepository = itemRepository;
        this.stockService = stockService;
        this.comprobanteRepository = comprobanteRepository;
        this.facturacion = facturacion;
        this.cajaRepository = cajaRepository;
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

    /**
     * El remito con sus renglones, quien lo emitio (informacion-y-links, S4) y, si esta FACTURADO, la venta que lo facturo
     * (S2 de cu-07-remito-presupuesto-imprimible).
     */
    @Transactional(readOnly = true)
    public DetalleRemito detalle(Long id) {
        Remito remito = remitoRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADO));
        DetalleRemito.FacturadoPor facturadoPor =
                remito.getEstado() == EstadoRemito.FACTURADO ? facturadoPor(id) : null;
        String usuario = cajaRepository.nombresScoped(List.of(remito.getUsuarioId())).get(remito.getUsuarioId());
        return new DetalleRemito(remito, itemRepository.deRemitoScoped(id), facturadoPor, usuario);
    }

    /**
     * La venta que cobro el remito, de la ferreteria del request (nunca la nota de credito). La factura sale solo de
     * {@code facturacion.resumenes}: un resumen que no es valido como factura (un X, un registro sin numero o una nota) no
     * muestra letra ni numero fiscal.
     */
    private DetalleRemito.FacturadoPor facturadoPor(Long remitoId) {
        List<Comprobante> ventas = comprobanteRepository.ventasDelRemitoScoped(remitoId);
        if (ventas.isEmpty()) {
            return null;
        }
        Comprobante venta = ventas.get(0);
        Map<Long, ResumenFiscal> resumenes = facturacion.resumenes(List.of(venta.getId()));
        ResumenFiscal fiscal = resumenes.get(venta.getId());
        String factura = fiscal != null && fiscal.validoComoFactura() && fiscal.numero() != null
                && "FACTURA".equals(fiscal.clase())
                ? String.format("Factura %s %05d-%08d", fiscal.letra(), fiscal.puntoVenta(), fiscal.numero())
                : null;
        return new DetalleRemito.FacturadoPor(venta.getId(), venta.getNumero(), factura);
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
