package com.ferreteria.stock.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.model.AjusteStock;
import com.ferreteria.stock.model.MotivoAjuste;
import com.ferreteria.stock.model.MovimientoStock;
import com.ferreteria.stock.repository.MovimientoStockRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Toda variacion de stock deja un movimiento (spec, reglas de negocio). Este servicio registra la salida por
 * venta: pide a catalogo el ajuste atomico del cache (por el puerto, sin tocar catalogo.model) y guarda un
 * movimiento por articulo. Corre dentro de la transaccion de la venta. La fecha de los movimientos de la venta es la de
 * la venta (la pasa quien llama); la del resto (remitos, compras y ajustes) sale del {@code Clock}, en hora de Buenos Aires.
 */
@Service
public class StockService {

    private final ArticulosParaVenta articulosParaVenta;
    private final MovimientoStockRepository movimientoRepository;
    private final Clock reloj;

    public StockService(ArticulosParaVenta articulosParaVenta, MovimientoStockRepository movimientoRepository,
            Clock reloj) {
        this.articulosParaVenta = articulosParaVenta;
        this.movimientoRepository = movimientoRepository;
        this.reloj = reloj;
    }

    /**
     * Descuenta cada articulo de la venta y guarda un movimiento VENTA (cantidad negativa) por articulo, con la fecha de
     * la venta. Si un articulo aparece en mas de una salida se suman. Primero el UPDATE del stock y despues los
     * INSERT: asi los locks de los articulos se toman antes que los S-locks de las FK de los movimientos.
     * Devuelve el stock resultante de cada articulo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> registrarVenta(Long comprobanteId, Long usuarioId, LocalDateTime fecha,
            List<SalidaStock> salidas) {
        if (salidas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> vendidoPorArticulo = new TreeMap<>();
        for (SalidaStock salida : salidas) {
            vendidoPorArticulo.merge(salida.articuloId(), salida.cantidad(), BigDecimal::add);
        }

        Map<Long, BigDecimal> deltas = new TreeMap<>();
        vendidoPorArticulo.forEach((articuloId, vendido) -> deltas.put(articuloId, vendido.negate()));
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(deltas);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        List<MovimientoStock> movimientos = vendidoPorArticulo.entrySet().stream()
                .map(e -> MovimientoStock.venta(ferreteriaId, e.getKey(), e.getValue(), comprobanteId, usuarioId, fecha))
                .toList();
        movimientoRepository.saveAll(movimientos);
        return stockResultante;
    }

    /**
     * Salida por remito (RM-003): igual que {@link #registrarVenta} pero los movimientos son REMITO y llevan el id del
     * remito, no el de un comprobante. Devuelve el stock resultante de cada articulo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> registrarRemito(Long remitoId, Long usuarioId, List<SalidaStock> salidas) {
        if (salidas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> entregadoPorArticulo = sumarPorArticulo(salidas);
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        entregadoPorArticulo.forEach((articuloId, entregado) -> deltas.put(articuloId, entregado.negate()));
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(deltas);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        LocalDateTime fecha = ahora();
        movimientoRepository.saveAll(entregadoPorArticulo.entrySet().stream()
                .map(e -> MovimientoStock.remito(ferreteriaId, e.getKey(), e.getValue(), remitoId, usuarioId, fecha))
                .toList());
        return stockResultante;
    }

    /**
     * Anular un remito (RM-005): devuelve al stock lo que salio, con un movimiento ANULACION_REMITO por articulo.
     * Devuelve el stock resultante de cada articulo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> devolverRemito(Long remitoId, Long usuarioId, List<SalidaStock> devueltas) {
        if (devueltas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> devueltoPorArticulo = sumarPorArticulo(devueltas);
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(devueltoPorArticulo);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        LocalDateTime fecha = ahora();
        movimientoRepository.saveAll(devueltoPorArticulo.entrySet().stream()
                .map(e -> MovimientoStock.anulacionRemito(ferreteriaId, e.getKey(), e.getValue(), remitoId, usuarioId,
                        fecha))
                .toList());
        return stockResultante;
    }

    /**
     * Entrada por compra (CU-13): suma cada articulo con un movimiento COMPRA que lleva la compra. Primero el UPDATE del
     * stock y despues los INSERT, como la venta. Devuelve el stock resultante de cada articulo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> registrarCompra(Long compraId, Long usuarioId, List<SalidaStock> entradas) {
        if (entradas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> compradoPorArticulo = sumarPorArticulo(entradas);
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(compradoPorArticulo);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        LocalDateTime fecha = ahora();
        movimientoRepository.saveAll(compradoPorArticulo.entrySet().stream()
                .map(e -> MovimientoStock.compra(ferreteriaId, e.getKey(), e.getValue(), compraId, usuarioId, fecha))
                .toList());
        return stockResultante;
    }

    /**
     * Factura un remito (RM-006): el remito ya descontó lo que entregó, asi que la venta mueve solo la diferencia por
     * articulo (vendido - entregado). Sale lo que se vendio de mas y vuelve lo que sobra o no se vendio; si es igual no
     * mueve nada. Cada diferencia deja un movimiento VENTA con el comprobante, el remito, el motivo y la fecha de la venta.
     * Devuelve el stock resultante solo de los articulos que salen (los unicos que pueden quedar sin stock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> facturarRemito(Long comprobanteId, Long remitoId, Long numeroRemito, Long usuarioId,
            LocalDateTime fecha, List<SalidaStock> vendidas, List<SalidaStock> entregadas) {
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        sumarPorArticulo(entregadas).forEach(deltas::put);
        sumarPorArticulo(vendidas).forEach((articuloId, vendido) -> deltas.merge(articuloId, vendido.negate(), BigDecimal::add));
        deltas.values().removeIf(delta -> delta.signum() == 0);
        if (deltas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(deltas);

        Long ferreteriaId = TenantContext.requireFerreteriaId();
        String motivo = "Diferencia con el remito N.º " + numeroRemito;
        movimientoRepository.saveAll(deltas.entrySet().stream()
                .map(e -> MovimientoStock.diferenciaConRemito(
                        ferreteriaId, e.getKey(), e.getValue(), comprobanteId, remitoId, motivo, usuarioId, fecha))
                .toList());

        Map<Long, BigDecimal> resultanteDeLosQueSalen = new TreeMap<>();
        deltas.forEach((articuloId, delta) -> {
            if (delta.signum() < 0 && stockResultante.containsKey(articuloId)) {
                resultanteDeLosQueSalen.put(articuloId, stockResultante.get(articuloId));
            }
        });
        return resultanteDeLosQueSalen;
    }

    /**
     * La nota de credito o la anulacion interna de una venta (CU-08, S5): vuelve todo lo vendido, con un movimiento
     * NOTA_CREDITO por articulo (cantidad positiva) con la nota y su fecha. {@code devueltas} son los renglones de la venta:
     * en una venta que salio de un remito suman lo que entrego el remito mas la diferencia de la venta
     * ({@link #facturarRemito}), asi el stock vuelve a lo que habia antes del remito. Los articulos se toman por id
     * ascendente (el orden de los locks) y el UPDATE va antes que los INSERT, como la venta. Devuelve el stock resultante.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> registrarNotaDeCredito(Long notaId, Long usuarioId, LocalDateTime fecha,
            List<SalidaStock> devueltas) {
        if (devueltas.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> devueltoPorArticulo = sumarPorArticulo(devueltas);
        Map<Long, BigDecimal> stockResultante = articulosParaVenta.ajustarStock(devueltoPorArticulo);
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        movimientoRepository.saveAll(devueltoPorArticulo.entrySet().stream()
                .map(e -> MovimientoStock.notaDeCredito(ferreteriaId, e.getKey(), e.getValue(), notaId, usuarioId, fecha))
                .toList());
        return stockResultante;
    }

    /** Suma las cantidades de cada articulo, en orden de id (el orden de los locks). */
    private static Map<Long, BigDecimal> sumarPorArticulo(List<SalidaStock> salidas) {
        Map<Long, BigDecimal> porArticulo = new TreeMap<>();
        for (SalidaStock salida : salidas) {
            porArticulo.merge(salida.articuloId(), salida.cantidad(), BigDecimal::add);
        }
        return porArticulo;
    }

    /**
     * Ajuste manual (CU-09): el Dueno carga el stock real contado y aca se calcula la diferencia contra el
     * stock confirmado en la base, leido con lock para que una venta simultanea no se pierda. Suma la
     * diferencia con el UPDATE atomico del catalogo y guarda un movimiento AJUSTE con el motivo. Un articulo
     * de otra ferreteria da 404 antes que cualquier 400; los errores de los datos salen todos juntos.
     */
    @Transactional
    public AjusteRegistrado ajustar(
            Long articuloId, BigDecimal stockReal, MotivoAjuste motivo, String detalle, Long usuarioId) {
        ArticuloVendible articulo = articuloAjustable(articuloId);

        Map<String, String> errores = new LinkedHashMap<>();
        try {
            AjusteStock.validarStockReal(stockReal, articulo.admiteDecimales());
        } catch (IllegalArgumentException e) {
            errores.put("stockReal", e.getMessage());
        }
        String textoMotivo = null;
        if (motivo == null) {
            errores.put("motivo", "Elegí el motivo del ajuste.");
        } else {
            try {
                textoMotivo = motivo.describir(detalle);
            } catch (IllegalArgumentException e) {
                errores.put("detalle", e.getMessage());
            }
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }

        BigDecimal stockAnterior = articulosParaVenta.stockVigente(articuloId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        BigDecimal diferencia;
        try {
            diferencia = AjusteStock.diferencia(stockAnterior, stockReal, articulo.admiteDecimales());
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(Map.of("stockReal", e.getMessage()));
        }

        BigDecimal stockActual = articulosParaVenta.ajustarStock(Map.of(articuloId, diferencia)).get(articuloId);
        movimientoRepository.save(MovimientoStock.ajuste(
                TenantContext.requireFerreteriaId(), articuloId, diferencia, textoMotivo, usuarioId, ahora()));
        return new AjusteRegistrado(articuloId, stockAnterior, diferencia, stockActual);
    }

    /**
     * Ajuste manual por cantidad (CU-09): suma (+) o resta (-) N al stock vigente EN EL MOMENTO de confirmar, asi
     * una venta hecha mientras el Dueno tenia el dialogo abierto no se pisa. El stock se lee con lock solo para
     * validar que no quede negativo y para informar el anterior; el cambio es el UPDATE relativo del catalogo.
     */
    @Transactional
    public AjusteRegistrado ajustarPorCantidad(
            Long articuloId, BigDecimal cantidad, MotivoAjuste motivo, String detalle, Long usuarioId) {
        ArticuloVendible articulo = articuloAjustable(articuloId);

        Map<String, String> errores = new LinkedHashMap<>();
        try {
            AjusteStock.validarCantidad(cantidad, articulo.admiteDecimales());
        } catch (IllegalArgumentException e) {
            errores.put("cantidad", e.getMessage());
        }
        String textoMotivo = null;
        if (motivo == null) {
            errores.put("motivo", "Elegí el motivo del ajuste.");
        } else {
            try {
                textoMotivo = motivo.describir(detalle);
            } catch (IllegalArgumentException e) {
                errores.put("detalle", e.getMessage());
            }
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }

        BigDecimal stockAnterior = articulosParaVenta.stockVigente(articuloId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        try {
            AjusteStock.stockResultante(stockAnterior, cantidad, articulo.unidad());
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(Map.of("cantidad", e.getMessage()));
        }

        BigDecimal stockActual = articulosParaVenta.ajustarStock(Map.of(articuloId, cantidad)).get(articuloId);
        movimientoRepository.save(MovimientoStock.ajuste(
                TenantContext.requireFerreteriaId(), articuloId, cantidad, textoMotivo, usuarioId, ahora()));
        return new AjusteRegistrado(articuloId, stockAnterior, cantidad.setScale(3), stockActual);
    }

    /** La hora de Buenos Aires del reloj, la de los movimientos que no son de una venta. */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), HoraArgentina.ZONA);
    }

    /** El articulo de la ferreteria del request: 404 si es ajeno o no existe, 422 si esta dado de baja. */
    private ArticuloVendible articuloAjustable(Long articuloId) {
        ArticuloVendible articulo = articulosParaVenta.buscar(List.of(articuloId)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        if (!articulo.activo()) {
            throw new ReglaDeNegocioException(
                    "\"" + articulo.descripcion() + "\" está dado de baja. Reactivalo para ajustar el stock.");
        }
        return articulo;
    }
}
