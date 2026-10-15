package com.ferreteria.stock.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Toda variacion de stock deja un movimiento (spec, reglas de negocio). Este servicio registra la salida por
 * venta: pide a catalogo el ajuste atomico del cache (por el puerto, sin tocar catalogo.model) y guarda un
 * movimiento por articulo. Corre dentro de la transaccion de la venta.
 */
@Service
public class StockService {

    private final ArticulosParaVenta articulosParaVenta;
    private final MovimientoStockRepository movimientoRepository;

    public StockService(ArticulosParaVenta articulosParaVenta, MovimientoStockRepository movimientoRepository) {
        this.articulosParaVenta = articulosParaVenta;
        this.movimientoRepository = movimientoRepository;
    }

    /**
     * Descuenta cada articulo de la venta y guarda un movimiento VENTA (cantidad negativa) por articulo.
     * Si un articulo aparece en mas de una salida se suman. Primero el UPDATE del stock y despues los
     * INSERT: asi los locks de los articulos se toman antes que los S-locks de las FK de los movimientos.
     * Devuelve el stock resultante de cada articulo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> registrarVenta(Long comprobanteId, Long usuarioId, List<SalidaStock> salidas) {
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
                .map(e -> MovimientoStock.venta(ferreteriaId, e.getKey(), e.getValue(), comprobanteId, usuarioId))
                .toList();
        movimientoRepository.saveAll(movimientos);
        return stockResultante;
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
        ArticuloVendible articulo = articulosParaVenta.buscar(List.of(articuloId)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));

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
                TenantContext.requireFerreteriaId(), articuloId, diferencia, textoMotivo, usuarioId));
        return new AjusteRegistrado(articuloId, stockAnterior, diferencia, stockActual);
    }
}
