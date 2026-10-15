package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
public class ArticulosParaVentaService implements ArticulosParaVenta {

    private final ArticuloRepository articuloRepository;

    public ArticulosParaVentaService(ArticuloRepository articuloRepository) {
        this.articuloRepository = articuloRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticuloVendible> buscar(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return articuloRepository.porIdsScoped(ids).stream().map(ArticulosParaVentaService::vendible).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> ajustarStock(Map<Long, BigDecimal> deltas) {
        // TreeMap: los UPDATE salen siempre por id ascendente, asi dos ventas con los mismos articulos en
        // distinto orden no se esperan en circulo (deadlock).
        Map<Long, BigDecimal> ordenados = new TreeMap<>(deltas);
        ordenados.forEach((id, delta) -> {
            int filas = articuloRepository.sumarStockScoped(id, delta);
            if (filas != 1) {
                throw new IllegalStateException(
                        "No se pudo ajustar el stock del artículo " + id + ": filas afectadas " + filas + ".");
            }
        });
        return articuloRepository.stockDeScoped(ordenados.keySet());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> stockVigente(Long id) {
        return articuloRepository.stockVigenteScoped(id);
    }

    private static ArticuloVendible vendible(Articulo articulo) {
        return new ArticuloVendible(
                articulo.getId(),
                articulo.getDescripcion(),
                articulo.getUnidad().name(),
                articulo.getUnidad() != Unidad.UNIDAD,
                articulo.getPrecioFinal(),
                articulo.getAlicuotaIva().name(),
                articulo.getAlicuotaIva().porcentaje(),
                articulo.isActivo());
    }
}
