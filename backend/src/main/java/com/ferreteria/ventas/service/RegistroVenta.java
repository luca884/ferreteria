package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.CantidadVendible;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.model.Pago;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaRechazadaException.Articulo;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Registra una venta en UNA transaccion (C-008). Primero valida todo sin escribir nada (R-007 a R-010) y
 * recien despues escribe, siempre en el mismo orden de locks: numerador, cabecera, articulos por id
 * ascendente, y al final los hijos. El que llama (VentaService) no tiene transaccion: resuelve la carrera de
 * la clave y reintenta los deadlocks.
 */
@Service
public class RegistroVenta {

    static final String MENSAJE_INEXISTENTE = "Hay un artículo del ticket que ya no está disponible";

    private final ArticulosParaVenta articulosParaVenta;
    private final StockService stockService;
    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final NumeradorComprobanteRepository numeradorRepository;

    public RegistroVenta(
            ArticulosParaVenta articulosParaVenta, StockService stockService,
            ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, NumeradorComprobanteRepository numeradorRepository) {
        this.articulosParaVenta = articulosParaVenta;
        this.stockService = stockService;
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.numeradorRepository = numeradorRepository;
    }

    @Transactional
    public VentaRegistrada registrar(ConfirmarVenta pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        validarArticulosDistintos(pedido.renglones());

        Optional<VentaRegistrada> yaRegistrada = repetida(pedido);
        if (yaRegistrada.isPresent()) {
            return yaRegistrada.get();
        }

        Map<Long, ArticuloVendible> articulos = validar(pedido);

        List<RenglonVenta> calculados = new ArrayList<>();
        for (Renglon renglon : pedido.renglones()) {
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            calculados.add(RenglonVenta.de(articulo.precioFinal(), renglon.cantidad(), articulo.porcentajeIva()));
        }
        TotalesVenta totales = TotalesVenta.de(calculados);

        long numero = numeradorRepository.siguienteScoped();
        Comprobante comprobante = comprobanteRepository.save(
                Comprobante.venta(ferreteriaId, numero, pedido.clave(), usuarioId, totales));

        List<SalidaStock> salidas = pedido.renglones().stream()
                .map(r -> new SalidaStock(r.articuloId(), r.cantidad()))
                .toList();
        Map<Long, BigDecimal> stockResultante = stockService.registrarVenta(comprobante.getId(), usuarioId, salidas);

        List<ItemComprobante> items = new ArrayList<>();
        for (int i = 0; i < pedido.renglones().size(); i++) {
            ArticuloVendible articulo = articulos.get(pedido.renglones().get(i).articuloId());
            items.add(ItemComprobante.de(ferreteriaId, comprobante.getId(), i + 1, articulo.id(),
                    articulo.descripcion(), articulo.unidad(), articulo.alicuotaIva(), calculados.get(i)));
        }
        List<ItemComprobante> itemsGuardados = itemRepository.saveAll(items);
        Pago pago = pagoRepository.save(
                Pago.de(ferreteriaId, comprobante.getId(), pedido.medioPago(), totales.total()));

        return new VentaRegistrada(comprobante, itemsGuardados, pago, sinStock(stockResultante, articulos), false);
    }

    /**
     * La venta que ya tiene esta clave en la ferreteria del request, si la hay (R-011). Con el mismo
     * contenido es la venta original; con otro contenido, CLAVE_CON_OTRO_CONTENIDO. Solo lee. La usa la
     * fachada despues de perder la carrera de la clave unica.
     */
    @Transactional(readOnly = true)
    public Optional<VentaRegistrada> repetida(ConfirmarVenta pedido) {
        Optional<Comprobante> existente = comprobanteRepository.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        Comprobante comprobante = existente.get();
        List<ItemComprobante> items = itemRepository.deComprobanteScoped(comprobante.getId());
        List<Pago> pagos = pagoRepository.deComprobanteScoped(comprobante.getId());
        if (!mismoContenido(pedido, comprobante, items, pagos)) {
            throw new VentaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO,
                    "Esta venta ya se registró con otros datos", List.of());
        }
        return Optional.of(new VentaRegistrada(comprobante, items, pagos.get(0), List.of(), true));
    }

    /**
     * "Contenido" (R-011): medio de pago, total mostrado y los renglones (articulo, cantidad y precio mostrado)
     * contra lo guardado. Se compara por valor: 10 y 10.00 son lo mismo.
     */
    private static boolean mismoContenido(
            ConfirmarVenta pedido, Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos) {
        if (pagos.size() != 1 || pagos.get(0).getMedio() != pedido.medioPago()
                || pedido.totalMostrado().compareTo(comprobante.getTotal()) != 0
                || pedido.renglones().size() != items.size()) {
            return false;
        }
        Map<Long, ItemComprobante> guardados = new HashMap<>();
        items.forEach(i -> guardados.put(i.getArticuloId(), i));
        for (Renglon renglon : pedido.renglones()) {
            ItemComprobante guardado = guardados.get(renglon.articuloId());
            if (guardado == null || renglon.cantidad().compareTo(guardado.getCantidad()) != 0
                    || renglon.precioUnitario().compareTo(guardado.getPrecioUnitario()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** R-016: los articulos cuyo stock resultante quedo menor que 0 (justo en 0 no avisa), por id. */
    private static List<VentaRegistrada.ArticuloSinStock> sinStock(
            Map<Long, BigDecimal> stockResultante, Map<Long, ArticuloVendible> articulos) {
        return new TreeMap<>(stockResultante).entrySet().stream()
                .filter(e -> e.getValue().signum() < 0)
                .map(e -> new VentaRegistrada.ArticuloSinStock(
                        e.getKey(), articulos.get(e.getKey()).descripcion(), e.getValue()))
                .toList();
    }

    /** Pasos 3 y 4 de C-008: busca los articulos y valida en orden de precedencia, sin escribir. */
    private Map<Long, ArticuloVendible> validar(ConfirmarVenta pedido) {
        Set<Long> ids = pedido.renglones().stream().map(Renglon::articuloId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ArticuloVendible> articulos = new HashMap<>();
        articulosParaVenta.buscar(ids).forEach(a -> articulos.put(a.id(), a));

        validarQueExistan(pedido.renglones(), articulos);
        validarQueEstenActivos(pedido.renglones(), articulos);
        validarQueTenganPrecio(pedido.renglones(), articulos);
        validarCantidades(pedido.renglones(), articulos);
        validarImportes(pedido.renglones(), articulos);
        validarPrecios(pedido, articulos);
        validarMedioDePago(pedido);
        return articulos;
    }

    /** Un id repetido es un error del cliente que no depende de la base: se corta antes de consultar. */
    private static void validarArticulosDistintos(List<Renglon> renglones) {
        Set<Long> vistos = new HashSet<>();
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            if (!vistos.add(renglones.get(i).articuloId())) {
                errores.put("renglones[" + i + "].articuloId",
                        "Un artículo no puede repetirse en el ticket: sumá la cantidad en un solo renglón.");
            }
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** R-010: inexistente o ajeno, la misma respuesta; solo vuelven los ids que mando el cliente. */
    private static void validarQueExistan(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<Articulo> faltantes = renglones.stream()
                .filter(r -> !articulos.containsKey(r.articuloId()))
                .map(r -> Articulo.soloId(r.articuloId()))
                .toList();
        if (!faltantes.isEmpty()) {
            throw new VentaRechazadaException(Motivo.ARTICULO_INEXISTENTE, MENSAJE_INEXISTENTE, faltantes);
        }
    }

    /** R-009: si alguno esta dado de baja se rechaza TODA la venta. */
    private static void validarQueEstenActivos(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<ArticuloVendible> dadosDeBaja = renglones.stream()
                .map(r -> articulos.get(r.articuloId()))
                .filter(a -> !a.activo())
                .toList();
        if (dadosDeBaja.isEmpty()) {
            return;
        }
        String mensaje = dadosDeBaja.size() == 1
                ? "\"" + dadosDeBaja.get(0).descripcion() + "\" fue dado de baja. Sacalo del ticket para seguir."
                : "Hay artículos dados de baja: "
                        + dadosDeBaja.stream().map(a -> "\"" + a.descripcion() + "\"")
                                .collect(Collectors.joining(", "))
                        + ". Sacalos del ticket para seguir.";
        throw new VentaRechazadaException(Motivo.ARTICULO_DADO_DE_BAJA, mensaje,
                dadosDeBaja.stream().map(a -> Articulo.conDescripcion(a.id(), a.descripcion())).toList());
    }

    /**
     * Un articulo con precio final $0 (costo sin cargar) no se vende: se rechaza TODA la venta, como con los dados
     * de baja. Mira el precio del servidor, no el que mando el cliente.
     */
    private static void validarQueTenganPrecio(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<ArticuloVendible> sinPrecio = renglones.stream()
                .map(r -> articulos.get(r.articuloId()))
                .filter(a -> a.precioFinal().signum() <= 0)
                .toList();
        if (sinPrecio.isEmpty()) {
            return;
        }
        String mensaje = sinPrecio.size() == 1
                ? "\"" + sinPrecio.get(0).descripcion() + "\" no tiene precio. Sacalo del ticket y pedile al dueño "
                        + "que le cargue el costo."
                : "Hay artículos sin precio: "
                        + sinPrecio.stream().map(a -> "\"" + a.descripcion() + "\"")
                                .collect(Collectors.joining(", "))
                        + ". Sacalos del ticket y pedile al dueño que les cargue el costo.";
        throw new VentaRechazadaException(Motivo.ARTICULO_SIN_PRECIO, mensaje,
                sinPrecio.stream().map(a -> Articulo.conDescripcion(a.id(), a.descripcion())).toList());
    }

    /** R-007: la unidad la lee el servidor; cada renglon invalido queda con su mensaje. */
    private static void validarCantidades(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            int indice = i;
            CantidadVendible.validar(articulo.descripcion(), renglon.cantidad(), articulo.admiteDecimales())
                    .ifPresent(mensaje -> errores.put("renglones[" + indice + "].cantidad", mensaje));
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /**
     * Tope de DECIMAL(15,2): cada dato entra solo (lo frena el request) pero cantidad x precio del
     * servidor, o la suma de los renglones, puede no entrar. Se corta con un 400 antes de escribir.
     */
    private static void validarImportes(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        Map<String, String> errores = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            RenglonVenta calculado = RenglonVenta.de(articulo.precioFinal(), renglon.cantidad(), articulo.porcentajeIva());
            if (calculado.total().compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0) {
                errores.put("renglones[" + i + "].cantidad", "El importe de \"" + articulo.descripcion()
                        + "\" es demasiado grande: el máximo por renglón es $ 9.999.999.999.999,99.");
            }
            total = total.add(calculado.total());
        }
        if (errores.isEmpty() && total.compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0) {
            errores.put("totalMostrado", "El total de la venta es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /**
     * R-008: recalcula con el precio actual. Si algun precio mostrado difiere, o el total mostrado difiere del
     * recalculado, 409 con los renglones que cambiaron y el total actual.
     */
    private static void validarPrecios(ConfirmarVenta pedido, Map<Long, ArticuloVendible> articulos) {
        List<RenglonVenta> actuales = new ArrayList<>();
        List<Articulo> cambiados = new ArrayList<>();
        for (Renglon renglon : pedido.renglones()) {
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            RenglonVenta actual = RenglonVenta.de(articulo.precioFinal(), renglon.cantidad(), articulo.porcentajeIva());
            actuales.add(actual);
            if (renglon.precioUnitario().compareTo(articulo.precioFinal()) != 0) {
                cambiados.add(new Articulo(articulo.id(), articulo.descripcion(), renglon.precioUnitario(),
                        articulo.precioFinal(), actual.total()));
            }
        }
        BigDecimal totalActual = TotalesVenta.de(actuales).total();
        if (cambiados.isEmpty() && pedido.totalMostrado().compareTo(totalActual) == 0) {
            return;
        }
        String mensaje = switch (cambiados.size()) {
            case 0 -> "El total cambió. Revisá el total y confirmá de nuevo.";
            case 1 -> "Cambió el precio de 1 artículo. Revisá el total y confirmá de nuevo.";
            default -> "Cambiaron los precios de " + cambiados.size()
                    + " artículos. Revisá el total y confirmá de nuevo.";
        };
        throw new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, mensaje, cambiados, totalActual);
    }

    /** Un solo medio por ahora (corte 1); el monto es siempre el total, asi que solo puede faltar el medio. */
    private static void validarMedioDePago(ConfirmarVenta pedido) {
        if (pedido.medioPago() == null) {
            throw new DatosInvalidosException(Map.of("medioPago",
                    "Elegí un medio de pago: EFECTIVO, DEBITO, CREDITO o TRANSFERENCIA."));
        }
    }
}
