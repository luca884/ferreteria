package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.Pago;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.model.RecargoVenta;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.model.TotalesVenta;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.ItemPresupuestoRepository;
import com.ferreteria.ventas.repository.ItemRemitoRepository;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.springframework.security.access.AccessDeniedException;
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
 * recien despues escribe, siempre en el mismo orden de locks:
 * <pre>
 * caja (S, CU-10) → presupuesto origen (X) → remito origen (X) → CLIENTE (X, solo si clienteId != null) → numerador (X)
 *   → comprobante → articulos/stock (X, id asc) → hijos (items, pagos y movimientos de cuenta)
 * </pre>
 * TODA venta con cliente (cobre o no a cuenta corriente) toma al cliente en ese mismo lugar, justo antes del numerador: InnoDB
 * toma un lock compartido sobre la fila del cliente al insertar el comprobante (FK), asi que si solo las ventas a cuenta lo
 * tomaran antes del numerador, una venta con cliente que ya tiene el numerador y espera ese lock compartido cerraria un ciclo con
 * la venta a cuenta que espera el numerador. El numerador ya serializa todas las ventas de la ferreteria: serializar tambien por
 * cliente no cuesta nada. El cupo se verifica antes de escribir (bajo el lock del cliente) y el asiento va al final, cuando ya
 * existen los ids del comprobante y de los pagos. El que llama (VentaService) no tiene transaccion: resuelve la carrera de
 * la clave y reintenta los deadlocks.
 *
 * <p>Compatibilidad con {@link RegistroCobranza} (caja S → CLIENTE X → numerador_recibo X → recibo → ...): comparten la caja
 * en S y el cliente en X antes de cualquier numerador, y los numeradores son tablas distintas, asi que no hay ciclo. Quien
 * agregue un paso a esta venta o a la cobranza mantiene «cliente antes que cualquier numerador». La venta hace la lectura plana
 * de la clave (repetida) antes de la caja y del cliente; es segura porque despues solo lee con lectura actual: si una venta
 * futura leyera algo derivado de filas bajo la foto vieja, debe tomar al cliente antes.
 */
@Service
public class RegistroVenta {

    static final String MENSAJE_SIN_FORMA = "Elegí una forma de pago.";
    static final String MENSAJE_FORMA_INVALIDA = "La forma de pago no es válida.";
    static final String MENSAJE_PRESUPUESTO_Y_REMITO = "Una venta sale de un presupuesto o de un remito, no de los dos.";
    static final String MENSAJE_CLIENTE_PARA_CUENTA = "Elegí el cliente para vender a cuenta corriente.";
    static final String MENSAJE_SOLO_EL_DUENO_AUTORIZA = "Autorizar el exceso es solo del Dueño";

    private final ArticulosParaVenta articulosParaVenta;
    private final StockService stockService;
    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final NumeradorComprobanteRepository numeradorRepository;
    private final CajaService cajaService;
    private final FormaPagoRepository formaPagoRepository;
    private final PresupuestoRepository presupuestoRepository;
    private final RemitoRepository remitoRepository;
    private final ItemRemitoRepository itemRemitoRepository;
    private final ItemPresupuestoRepository itemPresupuestoRepository;
    private final ClienteDelDocumento clienteDelDocumento;
    private final CuentaCorrienteParaVenta cuentaCorriente;
    private final TopeDeDescuento topeDeDescuento;

    public RegistroVenta(
            ArticulosParaVenta articulosParaVenta, StockService stockService,
            ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, NumeradorComprobanteRepository numeradorRepository,
            CajaService cajaService, FormaPagoRepository formaPagoRepository,
            PresupuestoRepository presupuestoRepository, RemitoRepository remitoRepository,
            ItemRemitoRepository itemRemitoRepository, ClienteDelDocumento clienteDelDocumento,
            CuentaCorrienteParaVenta cuentaCorriente, TopeDeDescuento topeDeDescuento,
            ItemPresupuestoRepository itemPresupuestoRepository) {
        this.itemPresupuestoRepository = itemPresupuestoRepository;
        this.topeDeDescuento = topeDeDescuento;
        this.clienteDelDocumento = clienteDelDocumento;
        this.cuentaCorriente = cuentaCorriente;
        this.articulosParaVenta = articulosParaVenta;
        this.stockService = stockService;
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.numeradorRepository = numeradorRepository;
        this.cajaService = cajaService;
        this.formaPagoRepository = formaPagoRepository;
        this.presupuestoRepository = presupuestoRepository;
        this.remitoRepository = remitoRepository;
        this.itemRemitoRepository = itemRemitoRepository;
    }

    /** Lo que sale de validar: los articulos del catalogo y las formas de pago del pedido, por id. */
    private record Validado(Map<Long, ArticuloVendible> articulos, Map<Long, FormaPago> formas) {}

    /**
     * Registra la venta. {@code usuarioEsDueno} sale del rol del JWT (el pedido no lleva actor): solo importa si la venta a
     * cuenta corriente pasa el limite del cliente (CC-005), porque la autorizacion de un exceso es solo del Dueño.
     */
    @Transactional
    public VentaRegistrada registrar(ConfirmarVenta pedido, Long usuarioId, boolean usuarioEsDueno) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        ValidacionTicket.articulosDistintos(pedido.renglones());
        if (pedido.presupuestoId() != null && pedido.remitoId() != null) {
            throw new ReglaDeNegocioException(MENSAJE_PRESUPUESTO_Y_REMITO);
        }

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
        // RM-006: lo mismo con el remito: se toma antes de mirar el ticket, y una segunda venta del mismo remito (o su
        // anulacion) espera aca y lo ve FACTURADO (o ANULADO).
        Remito remito = remitoParaFacturar(pedido.remitoId());
        // CL-004: el cliente elegido tiene que ser uno activo de la ferreteria; la venta guarda su id y una copia del nombre.
        // CC-010: se lo TOMA aca (lock de escritura) para toda venta con cliente, antes del numerador: ver el orden de locks.
        ClienteDelDocumento.Elegido cliente = clienteDelDocumento.resolverParaVenta(pedido.clienteId());

        Validado validado = validar(pedido, usuarioId, autorizados(presupuesto, remito));
        Map<Long, ArticuloVendible> articulos = validado.articulos();
        Map<Long, FormaPago> formas = validado.formas();

        List<RenglonVenta> sinRecargo = calcular(pedido, articulos);
        List<ConfirmarVenta.PagoPedido> partes = pagosDe(pedido, TotalesVenta.de(sinRecargo).total());
        List<RenglonVenta> calculados = RecargoVenta.repartir(sinRecargo, recargoDe(partes, formas));
        TotalesVenta totales = TotalesVenta.de(calculados);

        // CC-005, CC-006: el cupo se verifica antes de escribir nada, con el saldo vigente bajo el lock del cliente.
        BigDecimal importeCuenta = importeACuenta(partes, formas);
        Long autorizadoPor = importeCuenta.signum() > 0
                ? verificarCupo(cliente, importeCuenta, pedido.autorizaExceso(), usuarioId, usuarioEsDueno)
                : null;

        long numero = numeradorRepository.siguienteScoped();
        Comprobante comprobante = comprobanteRepository.save(
                Comprobante.venta(ferreteriaId, numero, pedido.clave(), usuarioId, totales, cajaId,
                        pedido.descuentoPorcentaje(), pedido.presupuestoId(), pedido.remitoId(), pedido.facturable(),
                        cliente.id(), cliente.nombre()));
        if (presupuesto != null) {
            presupuesto.cambiarEstado(EstadoPresupuesto.FACTURADO);
        }
        if (remito != null) {
            remito.cambiarEstado(EstadoRemito.FACTURADO);
        }

        List<SalidaStock> salidas = pedido.renglones().stream()
                .map(r -> new SalidaStock(r.articuloId(), r.cantidad()))
                .toList();
        Map<Long, BigDecimal> stockResultante = remito == null
                ? stockService.registrarVenta(comprobante.getId(), usuarioId, salidas)
                : facturarRemito(remito, comprobante, usuarioId, salidas);

        List<ItemComprobante> items = new ArrayList<>();
        for (int i = 0; i < pedido.renglones().size(); i++) {
            ArticuloVendible articulo = articulos.get(pedido.renglones().get(i).articuloId());
            items.add(ItemComprobante.de(ferreteriaId, comprobante.getId(), i + 1, articulo.id(),
                    articulo.descripcion(), articulo.unidad(), articulo.alicuotaIva(), articulo.costoUnitario(),
                    calculados.get(i)));
        }
        List<ItemComprobante> itemsGuardados = itemRepository.saveAll(items);
        List<Pago> pagos = new ArrayList<>();
        for (ConfirmarVenta.PagoPedido parte : partes) {
            FormaPago forma = formas.get(parte.formaPagoId());
            pagos.add(pagoRepository.save(Pago.de(ferreteriaId, comprobante.getId(), forma, parte.monto(),
                    RecargoVenta.deCobro(parte.monto(), forma.getRecargoPorcentaje()))));
        }

        if (importeCuenta.signum() > 0) {
            List<CuentaCorrienteParaVenta.CargoPago> cargos = pagos.stream()
                    .filter(p -> p.getMedio() == MedioPago.CUENTA_CORRIENTE)
                    .map(p -> new CuentaCorrienteParaVenta.CargoPago(p.getId(), p.getMonto()))
                    .toList();
            cuentaCorriente.asentarVenta(new CuentaCorrienteParaVenta.AsientoVenta(
                    cliente.id(), comprobante.getId(), comprobante.getNumero(), cargos, usuarioId, autorizadoPor));
        }

        return new VentaRegistrada(comprobante, itemsGuardados, pagos, sinStock(stockResultante, articulos), false);
    }

    /** Lo que la venta carga a la cuenta corriente: la suma de lo cobrado (parte mas recargo) con las formas de medio CUENTA_CORRIENTE. */
    private static BigDecimal importeACuenta(List<ConfirmarVenta.PagoPedido> partes, Map<Long, FormaPago> formas) {
        BigDecimal importe = BigDecimal.ZERO;
        for (ConfirmarVenta.PagoPedido parte : partes) {
            FormaPago forma = formas.get(parte.formaPagoId());
            if (forma.getMedio() == MedioPago.CUENTA_CORRIENTE) {
                importe = importe.add(parte.monto()).add(RecargoVenta.deCobro(parte.monto(), forma.getRecargoPorcentaje()));
            }
        }
        return importe;
    }

    /**
     * Decide si la venta a cuenta corriente entra: sin cuenta habilitada se rechaza siempre (CC-006); si pasa el limite, solo
     * entra con la autorizacion del Dueño (CC-005). Devuelve el Dueño que autorizo un exceso, o null si no hubo exceso (una
     * autorizacion sin exceso se ignora). Un Vendedor que manda la autorizacion ante un exceso es 403: pide algo que no puede.
     */
    private Long verificarCupo(ClienteDelDocumento.Elegido cliente, BigDecimal importe, boolean autorizaExceso,
            Long usuarioId, boolean usuarioEsDueno) {
        CupoCuenta cupo = cuentaCorriente.verificarCupo(cliente.id(), importe);
        if (!cupo.habilitada()) {
            throw VentaRechazadaException.cuentaNoHabilitada(cliente.nombre());
        }
        if (!cupo.excede()) {
            return null;
        }
        if (!autorizaExceso) {
            throw VentaRechazadaException.limiteCuentaCorriente(usuarioEsDueno
                    ? new VentaRechazadaException.ExcesoLimite(true, cupo.saldo(), cupo.limite(), cupo.disponible(), importe,
                            cupo.exceso())
                    : VentaRechazadaException.ExcesoLimite.sinCifras());
        }
        if (!usuarioEsDueno) {
            throw new AccessDeniedException(MENSAJE_SOLO_EL_DUENO_AUTORIZA);
        }
        return usuarioId;
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
                || !java.util.Objects.equals(pedido.remitoId(), comprobante.getRemitoId())
                || !java.util.Objects.equals(pedido.clienteId(), comprobante.getClienteId())
                || pedido.facturable() != (comprobante.getEstado() != EstadoComprobante.NO_FACTURABLE)
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
    static List<VentaRegistrada.ArticuloSinStock> sinStock(
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

    /**
     * El remito del que sale la venta, con lock exclusivo, o null si la venta no sale de uno. Uno de otra ferreteria o
     * que no existe es 404; uno anulado o ya facturado, 409. Solo un EMITIDO se cobra (RM-006).
     */
    private Remito remitoParaFacturar(Long remitoId) {
        if (remitoId == null) {
            return null;
        }
        Remito remito = remitoRepository.paraCambiarScoped(remitoId)
                .orElseThrow(() -> new ResourceNotFoundException(RemitoGestion.MENSAJE_NO_ENCONTRADO));
        if (remito.getEstado() == EstadoRemito.ANULADO) {
            throw new ConflictException(RemitoGestion.MENSAJE_ANULADO);
        }
        if (remito.getEstado() == EstadoRemito.FACTURADO) {
            throw new ConflictException(RemitoGestion.MENSAJE_FACTURADO);
        }
        return remito;
    }

    /**
     * El stock de una venta que sale de un remito: el remito ya descontó lo que entregó, asi que solo se mueve la
     * diferencia (RM-006). Devuelve el stock resultante de los articulos que salen de mas.
     */
    private Map<Long, BigDecimal> facturarRemito(
            Remito remito, Comprobante comprobante, Long usuarioId, List<SalidaStock> vendidas) {
        List<SalidaStock> entregadas = itemRemitoRepository.deRemitoScoped(remito.getId()).stream()
                .map(i -> new SalidaStock(i.getArticuloId(), i.getCantidad()))
                .toList();
        return stockService.facturarRemito(comprobante.getId(), remito.getId(), remito.getNumero(), usuarioId,
                vendidas, entregadas);
    }

    /** Pasos 3 y 4 de C-008: busca los articulos y las formas de pago y valida en orden de precedencia, sin escribir. */
    /**
     * CU-07: los descuentos del presupuesto o remito que se pasa a venta ya los autorizo quien lo emitio; el tope del
     * usuario solo frena lo que se agregue o se suba (Luca, 2026-10-22).
     */
    private TopeDeDescuento.Autorizados autorizados(Presupuesto presupuesto, Remito remito) {
        if (presupuesto != null) {
            Map<Long, BigDecimal> renglones = new HashMap<>();
            itemPresupuestoRepository.dePresupuestoScoped(presupuesto.getId())
                    .forEach(i -> renglones.put(i.getArticuloId(), i.getDescuentoPorcentaje()));
            return new TopeDeDescuento.Autorizados(presupuesto.getDescuentoPorcentaje(), renglones);
        }
        if (remito != null) {
            Map<Long, BigDecimal> renglones = new HashMap<>();
            itemRemitoRepository.deRemitoScoped(remito.getId())
                    .forEach(i -> renglones.put(i.getArticuloId(), i.getDescuentoPorcentaje()));
            return new TopeDeDescuento.Autorizados(remito.getDescuentoPorcentaje(), renglones);
        }
        return TopeDeDescuento.Autorizados.NINGUNO;
    }

    private Validado validar(ConfirmarVenta pedido, Long usuarioId, TopeDeDescuento.Autorizados autorizados) {
        Set<Long> ids = pedido.renglones().stream().map(Renglon::articuloId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ArticuloVendible> articulos = new HashMap<>();
        articulosParaVenta.buscar(ids).forEach(a -> articulos.put(a.id(), a));

        ValidacionTicket.renglones(pedido.renglones(), pedido.descuentoPorcentaje(), articulos);
        topeDeDescuento.verificar(pedido.renglones(), pedido.descuentoPorcentaje(), usuarioId, autorizados);
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
            if (pedido.clienteId() == null && esCuentaCorriente(formas.get(pedido.formaPagoId()))) {
                throw new DatosInvalidosException(Map.of("clienteId", MENSAJE_CLIENTE_PARA_CUENTA));
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
        // CC-012: vender a cuenta corriente exige elegir al cliente. Va con los errores de los pagos (despues del 409 de precios).
        if (pedido.clienteId() == null && pedido.pagos().stream()
                .anyMatch(p -> p.formaPagoId() != null && esCuentaCorriente(formas.get(p.formaPagoId())))) {
            errores.put("clienteId", MENSAJE_CLIENTE_PARA_CUENTA);
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    private static boolean esCuentaCorriente(FormaPago forma) {
        return forma != null && forma.isActiva() && forma.getMedio() == MedioPago.CUENTA_CORRIENTE;
    }

    /** Una forma que no existe en la ferreteria (o es de otra) llega como null: es lo mismo que una inactiva. */
    private static boolean esUtilizable(FormaPago forma) {
        return forma != null && forma.isActiva();
    }
}
