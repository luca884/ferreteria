package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
public class ArticulosParaVentaService implements ArticulosParaVenta {

    private final ArticuloRepository articuloRepository;
    private final DolarDePrecios dolarDePrecios;

    public ArticulosParaVentaService(ArticuloRepository articuloRepository, DolarDePrecios dolarDePrecios) {
        this.articuloRepository = articuloRepository;
        this.dolarDePrecios = dolarDePrecios;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticuloVendible> buscar(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Articulo> articulos = articuloRepository.porIdsScoped(ids);
        // El dolar se lee una sola vez, y solo si hay algun articulo en USD.
        BigDecimal dolar = articulos.stream().anyMatch(a -> a.getMoneda() == Moneda.USD)
                ? dolarDePrecios.vigente().orElse(null)
                : null;
        return articulos.stream().map(a -> vendible(a, dolar)).toList();
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

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> stockActual(Long id) {
        return Optional.ofNullable(articuloRepository.stockDeScoped(List.of(id)).get(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> rubros(Collection<Long> ids) {
        return articuloRepository.rubrosDeScoped(ids);
    }

    private static ArticuloVendible vendible(Articulo articulo, BigDecimal dolar) {
        return new ArticuloVendible(
                articulo.getId(),
                articulo.getDescripcion(),
                articulo.getUnidad().name(),
                articulo.getUnidad() != Unidad.UNIDAD,
                // Ofertas (S2): se vende al precio de oferta vigente hoy; si no hay, al normal.
                articulo.precioVigenteEl(HoraArgentina.hoy()),
                articulo.getAlicuotaIva().name(),
                articulo.getAlicuotaIva().porcentaje(),
                articulo.isActivo(),
                costoEnPesos(articulo, dolar));
    }

    /** El costo sin IVA en pesos, a 4 decimales; null si el articulo esta en USD y no hay dolar. */
    private static BigDecimal costoEnPesos(Articulo articulo, BigDecimal dolar) {
        if (articulo.getMoneda() != Moneda.USD) {
            return articulo.getCosto().setScale(4, RoundingMode.HALF_UP);
        }
        return dolar == null ? null : articulo.getCosto().multiply(dolar).setScale(4, RoundingMode.HALF_UP);
    }
}
