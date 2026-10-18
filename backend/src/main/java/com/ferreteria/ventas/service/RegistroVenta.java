package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.Pago;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.model.RecargoVenta;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
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
 * recien despues escribe, siempre en el mismo orden de locks: caja abierta (compartido, CU-10), numerador, cabecera, articulos por id
 * ascendente, y al final los hijos. El que llama (VentaService) no tiene transaccion: resuelve la carrera de
 * la clave y reintenta los deadlocks.
 */
@Service
public class RegistroVenta {

    static final String MENSAJE_SIN_FORMA = "Elegí una forma de pago.";
    static final String MENSAJE_FORMA_INVALIDA = "La forma de pago no es válida.";

    private final ArticulosParaVenta articulosParaVenta;
    private final StockService stockService;
    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final NumeradorComprobanteRepository numeradorRepository;
    private final CajaService cajaService;
    private final FormaPagoRepository formaPagoRepository;
    private final PresupuestoRepository presupuestoRepository;

    public RegistroVenta(
            ArticulosParaVenta articulosParaVenta, StockService stockService,
            ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, NumeradorComprobanteRepository numeradorRepository,
            CajaService cajaService, FormaPagoRepository formaPagoRepository,
            PresupuestoRepository presupuestoRepository) {
        this.articulosParaVenta = articulosParaVenta;
        this.stockService = stockService;
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.numeradorRepository = numeradorRepository;
        this.cajaService = cajaService;
        this.formaPagoRepository = formaPagoRepository;
        this.presupuestoRepository = presupuestoRepository;
    }

    /** Lo que sale de validar: los articulos del catalogo y las formas de pago del pedido, por id. */
    private record Validado(Map<Long, ArticuloVendible> articulos, Map<Long, FormaPago> formas) {}

    @Transactional
    public VentaRegistrada registrar(ConfirmarVenta pedido, Long usuarioId) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        ValidacionTicket.articulosDistintos(pedido.renglones());

        Optional<VentaRegistrada> yaRegistrada = repetida(pedido);
        if (yaRegistrada.isPresent()) {
            return yaRegistrada.get();
        }

        // CU-10: sin caja abierta del dia no se vende. Va despues de la repetida (la venta ya hecha se devuelve igual
        // aunque la caja se haya cerrado) y antes de mirar los articulos. Toma la caja compartida antes del numerador.
        Long cajaId = cajaService.cajaParaVender();

        // PP-006: el presupuesto se toma con lock exclusivo antes de mirar el ticket: si esta rechazado o ya facturado no
        // hay nada mas que validar, y una segunda venta del mismo presupuesto espera aca y lo ve FACTURADO.
        Presupuesto presupuesto = presupuestoParaFacturar(pedido.presupuestoId());

        Validado validado = validar(pedido);
        Map<Long, ArticuloVendible> articulos = validado.articulos();
        Map<Long, FormaPago> formas = validado.formas();

        List<RenglonVenta> sinRecargo = calcular(pedido, articulos);
        List<ConfirmarVenta.PagoPedido> partes = pagosDe(pedido, TotalesVenta.de(sinRecargo).total());
        List<RenglonVenta> calculados = RecargoVenta.repartir(sinRecargo, recargoDe(partes, formas));
        TotalesVenta totales = TotalesVenta.de(calculados);

        long numero = numeradorRepository.siguienteScoped();
        Comprobante comprobante = comprobanteRepository.save(
                Comprobante.venta(ferreteriaId, numero, pedido.clave(), usuarioId, totales, cajaId,
                        pedido.descuentoPorcentaje(), pedido.presupuestoId()));
        if (presupuesto != null) {
            presupuesto.cambiarEstado(EstadoPresupuesto.FACTURADO);
        }

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
        List<Pago> pagos = new ArrayList<>();
        for (ConfirmarVenta.PagoPedido parte : partes) {
            FormaPago forma = formas.get(parte.formaPagoId());
            pagos.add(pagoRepository.save(Pago.de(ferreteriaId, comprobante.getId(), forma, parte.monto(),
                    RecargoVenta.deCobro(parte.monto(), forma.getRecargoPorcentaje()))));
        }

        return new VentaRegistrada(comprobante, itemsGuardados, pagos, sinStock(stockResultante, articulos), false);
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
        return Optional.of(new VentaRegistrada(comprobante, items, pagos, List.of(), true));
    }

    /**
     * "Contenido" (R-011): medio de pago, total mostrado y los renglones (articulo, cantidad y precio mostrado)
     * contra lo guardado. Se compara por valor: 10 y 10.00 son lo mismo.
     */
    private static boolean mismoContenido(
            ConfirmarVenta pedido, Comprobante comprobante, List<ItemComprobante> items, List<Pago> pagos) {
        if (!mismosPagos(pedido, comprobante, pagos)
                || pedido.totalMostrado().compareTo(comprobante.getTotal()) != 0
                || pedido.descuentoPorcentaje().compareTo(comprobante.getDescuentoPorcentaje()) != 0
                || !java.util.Objects.equals(pedido.presupuestoId(), comprobante.getPresupuestoId())
                || pedido.renglones().size() != items.size()) {
            return false;
        }
        Map<Long, ItemComprobante> guardados = new HashMap<>();
        items.forEach(i -> guardados.put(i.getArticuloId(), i));
        for (Renglon renglon : pedido.renglones()) {
            ItemComprobante guardado = guardados.get(renglon.articuloId());
            if (guardado == null || renglon.cantidad().compareTo(guardado.getCantidad()) != 0
                    || renglon.precioUnitario().compareTo(guardado.getPrecioUnitario()) != 0
                    || renglon.descuentoPorcentaje().compareTo(guardado.getDescuentoPorcentaje()) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Los pagos del pedido contra los guardados: misma forma y misma parte del total (la sin recargo), sin importar el
     * orden. La forma se compara por id: aunque despues se renombre, cambie su recargo o se desactive, la venta es la misma.
     */
    private static boolean mismosPagos(ConfirmarVenta pedido, Comprobante comprobante, List<Pago> guardados) {
        List<ConfirmarVenta.PagoPedido> pedidos = pagosDe(pedido, comprobante.getTotal().subtract(comprobante.getRecargo()));
        if (pedidos.size() != guardados.size()) {
            return false;
        }
        Map<Long, BigDecimal> porForma = new HashMap<>();
        guardados.forEach(p -> porForma.put(p.getFormaPagoId(), p.getParte()));
        for (ConfirmarVenta.PagoPedido pago : pedidos) {
            BigDecimal parte = porForma.get(pago.formaPagoId());
            if (parte == null || pago.monto() == null || parte.compareTo(pago.monto()) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Los pagos del pedido tal como llegaron: la lista, o la forma unica por todo el total sin recargo. Todavia puede
     * traer formas o montos invalidos: {@link #validarPagos} los rechaza.
     */
    private static List<ConfirmarVenta.PagoPedido> pagosDe(ConfirmarVenta pedido, BigDecimal totalSinRecargo) {
        if (!pedido.pagos().isEmpty()) {
            return pedido.pagos();
        }
        if (pedido.formaPagoId() == null) {
            return List.of();
        }
        return List.of(new ConfirmarVenta.PagoPedido(pedido.formaPagoId(), totalSinRecargo));
    }

    /**
     * El recargo de esos pagos: la suma del de cada uno (su parte por el % de su forma). Un pago sin forma conocida o sin
     * un monto positivo no suma nada: ese error lo da {@link #validarPagos}, despues del 409.
     */
    private static BigDecimal recargoDe(List<ConfirmarVenta.PagoPedido> pagos, Map<Long, FormaPago> formas) {
        BigDecimal recargo = BigDecimal.ZERO;
        for (ConfirmarVenta.PagoPedido pago : pagos) {
            FormaPago forma = pago.formaPagoId() == null ? null : formas.get(pago.formaPagoId());
            if (forma != null && pago.monto() != null && pago.monto().signum() > 0) {
                recargo = recargo.add(RecargoVenta.deCobro(pago.monto(), forma.getRecargoPorcentaje()));
            }
        }
        return recargo;
    }

    /** El total que se cobra con el recargo de los pagos tal como llegaron (sin repartirlo: la suma es la misma). */
    private static BigDecimal totalConRecargo(ConfirmarVenta pedido, Map<Long, ArticuloVendible> articulos,
            Map<Long, FormaPago> formas) {
        BigDecimal totalSinRecargo = TotalesVenta.de(calcular(pedido, articulos)).total();
        if (totalSinRecargo.signum() == 0) {
            return totalSinRecargo;
        }
        return totalSinRecargo.add(recargoDe(pagosDe(pedido, totalSinRecargo), formas));
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

    /**
     * El presupuesto del que sale la venta, con lock exclusivo, o null si la venta no sale de uno. Uno de otra ferreteria
     * o que no existe es 404; uno rechazado o ya facturado, 409. Pendiente, aceptado y vencido se cobran (PP-006).
     */
    private Presupuesto presupuestoParaFacturar(Long presupuestoId) {
        if (presupuestoId == null) {
            return null;
        }
        Presupuesto presupuesto = presupuestoRepository.paraCambiarScoped(presupuestoId)
                .orElseThrow(() -> new ResourceNotFoundException(PresupuestoGestion.MENSAJE_NO_ENCONTRADO));
        if (presupuesto.getEstado() == EstadoPresupuesto.RECHAZADO) {
            throw new ConflictException("Ese presupuesto fue rechazado.");
        }
        if (presupuesto.getEstado() == EstadoPresupuesto.FACTURADO) {
            throw new ConflictException(PresupuestoGestion.MENSAJE_FACTURADO);
        }
        return presupuesto;
    }

    /** Pasos 3 y 4 de C-008: busca los articulos y las formas de pago y valida en orden de precedencia, sin escribir. */
    private Validado validar(ConfirmarVenta pedido) {
        Set<Long> ids = pedido.renglones().stream().map(Renglon::articuloId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ArticuloVendible> articulos = new HashMap<>();
        articulosParaVenta.buscar(ids).forEach(a -> articulos.put(a.id(), a));

        ValidacionTicket.renglones(pedido.renglones(), pedido.descuentoPorcentaje(), articulos);
        Map<Long, FormaPago> formas = formasDe(pedido);
        ValidacionTicket.importes(pedido.renglones(), articulos, pedido.descuentoPorcentaje(),
                () -> totalConRecargo(pedido, articulos, formas));
        ValidacionTicket.precios(pedido.renglones(), articulos, pedido.descuentoPorcentaje(), pedido.totalMostrado(),
                totalConRecargo(pedido, articulos, formas));
        validarPagos(pedido, formas, TotalesVenta.de(calcular(pedido, articulos)).total());
        return new Validado(articulos, formas);
    }

    /** Las formas de pago que nombra el pedido y existen en esta ferreteria, por id (activas o no). */
    private Map<Long, FormaPago> formasDe(ConfirmarVenta pedido) {
        Set<Long> ids = new LinkedHashSet<>();
        if (pedido.formaPagoId() != null) {
            ids.add(pedido.formaPagoId());
        }
        pedido.pagos().stream().map(ConfirmarVenta.PagoPedido::formaPagoId).filter(java.util.Objects::nonNull)
                .forEach(ids::add);
        Map<Long, FormaPago> formas = new HashMap<>();
        if (!ids.isEmpty()) {
            formaPagoRepository.porIdsScoped(ids).forEach(f -> formas.put(f.getId(), f));
        }
        return formas;
    }

    /** Los renglones del pedido calculados con el precio del servidor y sus descuentos (GDS-12), sin recargo. */
    private static List<RenglonVenta> calcular(ConfirmarVenta pedido, Map<Long, ArticuloVendible> articulos) {
        return ValidacionTicket.calcular(pedido.renglones(), articulos, pedido.descuentoPorcentaje());
    }

    /**
     * Como se paga: una forma unica por el total o una lista de pagos (GDS-07) cuyas partes (sin recargo) suman
     * exactamente el total sin recargo. Cada forma tiene que existir en la ferreteria y estar activa, va una sola vez y
     * cada monto es mayor que cero. Corre despues del 409 de precios: el total mostrado ya es el actual.
     */
    private static void validarPagos(ConfirmarVenta pedido, Map<Long, FormaPago> formas, BigDecimal totalSinRecargo) {
        if (pedido.pagos().isEmpty()) {
            if (pedido.formaPagoId() == null) {
                throw new DatosInvalidosException(Map.of("formaPagoId", MENSAJE_SIN_FORMA));
            }
            if (!esUtilizable(formas.get(pedido.formaPagoId()))) {
                throw new DatosInvalidosException(Map.of("formaPagoId", MENSAJE_FORMA_INVALIDA));
            }
            return;
        }
        if (pedido.formaPagoId() != null) {
            throw new DatosInvalidosException(
                    Map.of("pagos", "Mandá una sola forma de pago o la lista de pagos, no las dos."));
        }
        Map<String, String> errores = new LinkedHashMap<>();
        Set<Long> vistas = new HashSet<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (int i = 0; i < pedido.pagos().size(); i++) {
            ConfirmarVenta.PagoPedido pago = pedido.pagos().get(i);
            if (pago.formaPagoId() == null) {
                errores.put("pagos[" + i + "].formaPagoId", MENSAJE_SIN_FORMA);
            } else if (!esUtilizable(formas.get(pago.formaPagoId()))) {
                errores.put("pagos[" + i + "].formaPagoId", MENSAJE_FORMA_INVALIDA);
            } else if (!vistas.add(pago.formaPagoId())) {
                errores.put("pagos[" + i + "].formaPagoId", "Cada forma de pago va una sola vez.");
            }
            if (pago.monto() == null || pago.monto().signum() <= 0) {
                errores.put("pagos[" + i + "].monto", "El monto del pago tiene que ser mayor a cero.");
            } else {
                suma = suma.add(pago.monto());
            }
        }
        if (errores.isEmpty() && suma.compareTo(totalSinRecargo) != 0) {
            errores.put("pagos", "Los pagos tienen que sumar el total de la venta.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** Una forma que no existe en la ferreteria (o es de otra) llega como null: es lo mismo que una inactiva. */
    private static boolean esUtilizable(FormaPago forma) {
        return forma != null && forma.isActiva();
    }
}
