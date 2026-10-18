package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.ItemPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ItemPresupuestoRepository;
import com.ferreteria.ventas.repository.NumeradorPresupuestoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
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
 * Emite un presupuesto en UNA transaccion (PP-003). Valida el ticket con las mismas reglas y el mismo orden de
 * precedencia que la venta ({@link ValidacionTicket}) y recien despues escribe: numerador, cabecera y renglones. No
 * pide caja, forma de pago ni stock, y no toca el stock. El que llama ({@link PresupuestoService}) no tiene
 * transaccion: resuelve la carrera de la clave y reintenta los deadlocks.
 */
@Service
public class RegistroPresupuesto {

    private final ArticulosParaVenta articulosParaVenta;
    private final PresupuestoRepository presupuestoRepository;
    private final ItemPresupuestoRepository itemRepository;
    private final NumeradorPresupuestoRepository numeradorRepository;
    private final ClienteDelDocumento clienteDelDocumento;

    public RegistroPresupuesto(
            ArticulosParaVenta articulosParaVenta, PresupuestoRepository presupuestoRepository,
            ItemPresupuestoRepository itemRepository, NumeradorPresupuestoRepository numeradorRepository,
            ClienteDelDocumento clienteDelDocumento) {
        this.clienteDelDocumento = clienteDelDocumento;
        this.articulosParaVenta = articulosParaVenta;
        this.presupuestoRepository = presupuestoRepository;
        this.itemRepository = itemRepository;
        this.numeradorRepository = numeradorRepository;
    }

    @Transactional
    public PresupuestoEmitido emitir(EmitirPresupuesto pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        ValidacionTicket.articulosDistintos(pedido.renglones());

        Optional<PresupuestoEmitido> yaEmitido = repetido(pedido);
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
        Presupuesto presupuesto = presupuestoRepository.save(Presupuesto.emitir(ferreteriaId, numero, pedido.clave(),
                usuarioId, totales, pedido.descuentoPorcentaje(), pedido.diasValidez(), cliente.id(), cliente.nombre(),
                pedido.observaciones()));

        List<ItemPresupuesto> items = new ArrayList<>();
        for (int i = 0; i < pedido.renglones().size(); i++) {
            ArticuloVendible articulo = articulos.get(pedido.renglones().get(i).articuloId());
            items.add(ItemPresupuesto.de(ferreteriaId, presupuesto.getId(), i + 1, articulo.id(),
                    articulo.descripcion(), articulo.unidad(), articulo.alicuotaIva(), calculados.get(i)));
        }
        return new PresupuestoEmitido(presupuesto, itemRepository.saveAll(items), false);
    }

    /**
     * El presupuesto que ya tiene esta clave en la ferreteria del request, si lo hay. Con el mismo contenido es el
     * original; con otro contenido, CLAVE_CON_OTRO_CONTENIDO. Solo lee. La usa la fachada despues de perder la
     * carrera de la clave unica.
     */
    @Transactional(readOnly = true)
    public Optional<PresupuestoEmitido> repetido(EmitirPresupuesto pedido) {
        Optional<Presupuesto> existente = presupuestoRepository.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        Presupuesto presupuesto = existente.get();
        List<ItemPresupuesto> items = itemRepository.dePresupuestoScoped(presupuesto.getId());
        if (!mismoContenido(pedido, presupuesto, items)) {
            throw new VentaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO,
                    "Este presupuesto ya se emitió con otros datos", List.of());
        }
        return Optional.of(new PresupuestoEmitido(presupuesto, items, true));
    }

    /**
     * "Contenido": total mostrado, descuento global y los renglones (articulo, cantidad, precio mostrado y descuento)
     * contra lo guardado. Se compara por valor: 10 y 10.00 son lo mismo.
     */
    private static boolean mismoContenido(EmitirPresupuesto pedido, Presupuesto presupuesto, List<ItemPresupuesto> items) {
        if (pedido.totalMostrado().compareTo(presupuesto.getTotal()) != 0
                || pedido.descuentoPorcentaje().compareTo(presupuesto.getDescuentoPorcentaje()) != 0
                || !java.util.Objects.equals(pedido.clienteId(), presupuesto.getClienteId())
                || pedido.renglones().size() != items.size()) {
            return false;
        }
        Map<Long, ItemPresupuesto> guardados = new HashMap<>();
        items.forEach(i -> guardados.put(i.getArticuloId(), i));
        for (Renglon renglon : pedido.renglones()) {
            ItemPresupuesto guardado = guardados.get(renglon.articuloId());
            if (guardado == null || renglon.cantidad().compareTo(guardado.getCantidad()) != 0
                    || renglon.precioUnitario().compareTo(guardado.getPrecioUnitario()) != 0
                    || renglon.descuentoPorcentaje().compareTo(guardado.getDescuentoPorcentaje()) != 0) {
                return false;
            }
        }
        return true;
    }
}
