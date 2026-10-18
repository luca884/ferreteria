package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ItemRemitoRepository;
import com.ferreteria.ventas.repository.NumeradorRemitoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emite un remito en UNA transaccion (RM-004). Valida el ticket con las mismas reglas y el mismo orden de precedencia
 * que la venta ({@link ValidacionTicket}) y recien despues escribe, siempre en el mismo orden de locks que la venta:
 * numerador, cabecera, articulos por id ascendente (el descuento de stock) y al final los renglones. No pide caja ni
 * forma de pago; descuenta el stock sin exigir que alcance (GDS-34). El que llama ({@link RemitoService}) no tiene
 * transaccion: resuelve la carrera de la clave y reintenta los deadlocks.
 */
@Service
public class RegistroRemito {

    private final ArticulosParaVenta articulosParaVenta;
    private final StockService stockService;
    private final RemitoRepository remitoRepository;
    private final ItemRemitoRepository itemRepository;
    private final NumeradorRemitoRepository numeradorRepository;
    private final ClienteDelDocumento clienteDelDocumento;

    public RegistroRemito(
            ArticulosParaVenta articulosParaVenta, StockService stockService, RemitoRepository remitoRepository,
            ItemRemitoRepository itemRepository, NumeradorRemitoRepository numeradorRepository,
            ClienteDelDocumento clienteDelDocumento) {
        this.clienteDelDocumento = clienteDelDocumento;
        this.articulosParaVenta = articulosParaVenta;
        this.stockService = stockService;
        this.remitoRepository = remitoRepository;
        this.itemRepository = itemRepository;
        this.numeradorRepository = numeradorRepository;
    }

    @Transactional
    public RemitoEmitido emitir(EmitirRemito pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        ValidacionTicket.articulosDistintos(pedido.renglones());

        Optional<RemitoEmitido> yaEmitido = repetido(pedido);
        if (yaEmitido.isPresent()) {
            return yaEmitido.get();
        }

        ClienteDelDocumento.Elegido cliente = clienteDelDocumento.resolver(pedido.clienteId(), pedido.clienteNombre());

        Set<Long> ids = pedido.renglones().stream().map(Renglon::articuloId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ArticuloVendible> articulos = new HashMap<>();
        articulosParaVenta.buscar(ids).forEach(a -> articulos.put(a.id(), a));

        ValidacionTicket.renglones(pedido.renglones(), pedido.descuentoPorcentaje(), articulos);
        List<RenglonVenta> calculados =
                ValidacionTicket.calcular(pedido.renglones(), articulos, pedido.descuentoPorcentaje());
        TotalesVenta totales = TotalesVenta.de(calculados);
        ValidacionTicket.importes(pedido.renglones(), articulos, pedido.descuentoPorcentaje(), totales::total);
        ValidacionTicket.precios(pedido.renglones(), articulos, pedido.descuentoPorcentaje(), pedido.totalMostrado(),
                totales.total());

        long numero = numeradorRepository.siguienteScoped();
        Remito remito = remitoRepository.save(Remito.emitir(ferreteriaId, numero, pedido.clave(), usuarioId, totales,
                pedido.descuentoPorcentaje(), cliente.id(), cliente.nombre(), pedido.observaciones()));

        List<SalidaStock> salidas = pedido.renglones().stream()
                .map(r -> new SalidaStock(r.articuloId(), r.cantidad()))
                .toList();
        Map<Long, BigDecimal> stockResultante = stockService.registrarRemito(remito.getId(), usuarioId, salidas);

        List<ItemRemito> items = new ArrayList<>();
        for (int i = 0; i < pedido.renglones().size(); i++) {
            ArticuloVendible articulo = articulos.get(pedido.renglones().get(i).articuloId());
            items.add(ItemRemito.de(ferreteriaId, remito.getId(), i + 1, articulo.id(), articulo.descripcion(),
                    articulo.unidad(), articulo.alicuotaIva(), calculados.get(i)));
        }
        return new RemitoEmitido(remito, itemRepository.saveAll(items),
                RegistroVenta.sinStock(stockResultante, articulos), false);
    }

    /**
     * El remito que ya tiene esta clave en la ferreteria del request, si lo hay. Con el mismo contenido es el original;
     * con otro contenido, CLAVE_CON_OTRO_CONTENIDO. Solo lee. La usa la fachada despues de perder la carrera de la
     * clave unica.
     */
    @Transactional(readOnly = true)
    public Optional<RemitoEmitido> repetido(EmitirRemito pedido) {
        Optional<Remito> existente = remitoRepository.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        Remito remito = existente.get();
        List<ItemRemito> items = itemRepository.deRemitoScoped(remito.getId());
        if (!mismoContenido(pedido, remito, items)) {
            throw new VentaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO,
                    "Este remito ya se emitió con otros datos", List.of());
        }
        return Optional.of(new RemitoEmitido(remito, items, List.of(), true));
    }

    /**
     * "Contenido": total mostrado, descuento global y los renglones (articulo, cantidad, precio mostrado y descuento)
     * contra lo guardado. Se compara por valor: 10 y 10.00 son lo mismo.
     */
    private static boolean mismoContenido(EmitirRemito pedido, Remito remito, List<ItemRemito> items) {
        if (pedido.totalMostrado().compareTo(remito.getTotal()) != 0
                || pedido.descuentoPorcentaje().compareTo(remito.getDescuentoPorcentaje()) != 0
                || !java.util.Objects.equals(pedido.clienteId(), remito.getClienteId())
                || pedido.renglones().size() != items.size()) {
            return false;
        }
        Map<Long, ItemRemito> guardados = new HashMap<>();
        items.forEach(i -> guardados.put(i.getArticuloId(), i));
        for (Renglon renglon : pedido.renglones()) {
            ItemRemito guardado = guardados.get(renglon.articuloId());
            if (guardado == null || renglon.cantidad().compareTo(guardado.getCantidad()) != 0
                    || renglon.precioUnitario().compareTo(guardado.getPrecioUnitario()) != 0
                    || renglon.descuentoPorcentaje().compareTo(guardado.getDescuentoPorcentaje()) != 0) {
                return false;
            }
        }
        return true;
    }
}
