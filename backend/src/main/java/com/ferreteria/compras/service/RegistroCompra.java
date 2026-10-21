package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.catalogo.api.ProveedoresParaCompra;
import com.ferreteria.catalogo.api.ProveedoresParaCompra.ProveedorDeCompra;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.compras.model.Compra;
import com.ferreteria.compras.model.ItemCompra;
import com.ferreteria.compras.repository.CompraRepository;
import com.ferreteria.compras.repository.ItemCompraRepository;
import com.ferreteria.compras.service.PlanCompra.RenglonPlaneado;
import com.ferreteria.compras.service.RegistrarCompra.RenglonCompra;
import com.ferreteria.stock.service.SalidaStock;
import com.ferreteria.stock.service.StockService;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registra una compra (CU-13) en UNA transaccion: valida, guarda la cabecera, suma el stock (articulos por id
 * ascendente), pisa los costos con su historial y al final guarda los renglones. Si algo falla no queda nada. El que
 * llama ({@link CompraService}) no tiene transaccion: resuelve la carrera de las claves unicas y reintenta los choques.
 */
@Service
public class RegistroCompra {

    static final String CLAVE_CON_OTRO_CONTENIDO = "Esta compra ya se registró con otros datos.";

    private final ProveedoresParaCompra proveedores;
    private final ArticulosParaCompra articulosParaCompra;
    private final StockService stockService;
    private final CompraRepository compraRepository;
    private final ItemCompraRepository itemRepository;

    public RegistroCompra(ProveedoresParaCompra proveedores, ArticulosParaCompra articulosParaCompra,
                          StockService stockService, CompraRepository compraRepository,
                          ItemCompraRepository itemRepository) {
        this.proveedores = proveedores;
        this.articulosParaCompra = articulosParaCompra;
        this.stockService = stockService;
        this.compraRepository = compraRepository;
        this.itemRepository = itemRepository;
    }

    /** La vista previa: las mismas validaciones que registrar, incluido el comprobante ya cargado, sin escribir nada. */
    @Transactional(readOnly = true)
    public PlanCompra planear(RegistrarCompra pedido) {
        return validarYPlanear(pedido);
    }

    @Transactional
    public CompraRegistrada registrar(RegistrarCompra pedido, Long usuarioId) {
        ValidacionCompra.forma(pedido, HoraArgentina.hoy());
        Optional<CompraRegistrada> yaRegistrada = repetida(pedido);
        if (yaRegistrada.isPresent()) {
            return yaRegistrada.get();
        }
        PlanCompra plan = validarYPlanear(pedido);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        Compra compra = compraRepository.save(Compra.registrar(ferreteriaId, pedido.proveedorId(),
                plan.proveedorNombre(), pedido.tipo(), pedido.puntoVenta(), pedido.numero(), pedido.fecha(),
                plan.moneda(), plan.neto(), pedido.clave(), usuarioId));

        stockService.registrarCompra(compra.getId(), usuarioId, pedido.renglones().stream()
                .map(r -> new SalidaStock(r.articuloId(), r.cantidad()))
                .toList());

        Map<Long, BigDecimal> costos = new LinkedHashMap<>();
        pedido.renglones().forEach(r -> costos.put(r.articuloId(), r.costoUnitario()));
        Map<Long, CostoDeCompra> aplicados = new HashMap<>();
        articulosParaCompra.aplicar(costos, usuarioId).forEach(c -> aplicados.put(c.articuloId(), c));

        List<ItemCompra> items = new ArrayList<>();
        for (int i = 0; i < pedido.renglones().size(); i++) {
            RenglonCompra renglon = pedido.renglones().get(i);
            items.add(ItemCompra.de(ferreteriaId, compra.getId(), i + 1, renglon.cantidad(),
                    aplicados.get(renglon.articuloId())));
        }
        return new CompraRegistrada(compra, itemRepository.saveAll(items), false);
    }

    /**
     * La compra que ya tiene esta clave en la ferreteria del request, si la hay. Con el mismo contenido es la original;
     * con otro contenido, 409. Solo lee. La usa tambien la fachada despues de perder la carrera de la clave unica.
     */
    @Transactional(readOnly = true)
    public Optional<CompraRegistrada> repetida(RegistrarCompra pedido) {
        Optional<Compra> existente = compraRepository.porClaveScoped(pedido.clave());
        if (existente.isEmpty()) {
            return Optional.empty();
        }
        Compra compra = existente.get();
        List<ItemCompra> items = itemRepository.deCompraScoped(compra.getId());
        if (!mismoContenido(pedido, compra, items)) {
            throw new ConflictException(CLAVE_CON_OTRO_CONTENIDO);
        }
        return Optional.of(new CompraRegistrada(compra, items, true));
    }

    /** 409 si el proveedor ya tiene cargado ese comprobante en la ferreteria del request. */
    @Transactional(readOnly = true)
    public void comprobanteLibre(RegistrarCompra pedido) {
        compraRepository.porComprobanteScoped(pedido.proveedorId(), pedido.tipo(), pedido.puntoVenta(), pedido.numero())
                .ifPresent(c -> {
                    throw new ConflictException(
                            "Esa compra ya está cargada: " + c.comprobante() + " de " + c.getProveedorNombre() + ".");
                });
    }

    private PlanCompra validarYPlanear(RegistrarCompra pedido) {
        ValidacionCompra.forma(pedido, HoraArgentina.hoy());
        ProveedorDeCompra proveedor = proveedores.buscar(pedido.proveedorId())
                .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));

        Map<Long, BigDecimal> costos = new LinkedHashMap<>();
        pedido.renglones().forEach(r -> costos.put(r.articuloId(), r.costoUnitario()));
        List<CostoDeCompra> planeados = articulosParaCompra.planear(costos);
        ValidacionCompra.articulos(pedido, planeados);
        if (!proveedor.activo()) {
            throw new DatosInvalidosException(Map.of("proveedorId", "El proveedor está dado de baja."));
        }
        comprobanteLibre(pedido);
        return plan(pedido, proveedor, planeados);
    }

    private static PlanCompra plan(RegistrarCompra pedido, ProveedorDeCompra proveedor, List<CostoDeCompra> planeados) {
        Map<Long, CostoDeCompra> porId = new HashMap<>();
        planeados.forEach(c -> porId.put(c.articuloId(), c));
        List<RenglonPlaneado> renglones = new ArrayList<>();
        BigDecimal neto = BigDecimal.ZERO;
        int suben = 0;
        int bajan = 0;
        int iguales = 0;
        for (RenglonCompra renglon : pedido.renglones()) {
            CostoDeCompra costo = porId.get(renglon.articuloId());
            BigDecimal subtotal = ItemCompra.subtotal(renglon.cantidad(), renglon.costoUnitario());
            renglones.add(new RenglonPlaneado(costo, renglon.cantidad(), subtotal));
            neto = neto.add(subtotal);
            int comparacion = costo.costoNuevo().compareTo(costo.costoAnterior());
            if (comparacion > 0) {
                suben++;
            } else if (comparacion < 0) {
                bajan++;
            } else {
                iguales++;
            }
        }
        return new PlanCompra(proveedor.nombre(), planeados.get(0).moneda(), renglones, neto, suben, bajan, iguales);
    }

    /** Proveedor, comprobante, fecha y renglones (articulo, cantidad y costo), por valor: 10 y 10.00 son lo mismo. */
    private static boolean mismoContenido(RegistrarCompra pedido, Compra compra, List<ItemCompra> items) {
        if (!pedido.proveedorId().equals(compra.getProveedorId()) || pedido.tipo() != compra.getTipo()
                || pedido.puntoVenta() != compra.getPuntoVenta() || pedido.numero() != compra.getNumero()
                || !pedido.fecha().equals(compra.getFecha()) || pedido.renglones().size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            RenglonCompra renglon = pedido.renglones().get(i);
            ItemCompra guardado = items.get(i);
            if (!renglon.articuloId().equals(guardado.getArticuloId())
                    || renglon.cantidad().compareTo(guardado.getCantidad()) != 0
                    || renglon.costoUnitario().compareTo(guardado.getCostoUnitario()) != 0) {
                return false;
            }
        }
        return true;
    }
}
