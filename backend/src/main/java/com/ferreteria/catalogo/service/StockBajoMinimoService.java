package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.UltimasCompras;
import com.ferreteria.catalogo.api.UltimasCompras.UltimaCompra;
import com.ferreteria.catalogo.model.ArticuloBajoMinimo;
import com.ferreteria.catalogo.model.OrdenBajoMinimo;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Articulos bajo el stock minimo (CU-09 punto 1), como el «Listado de Artículos bajo Stock Mínimo» de GDS (G72): todos
 * de una vez, porque es un listado para imprimir y salir a reponer. Cada fila lleva la ultima compra del articulo
 * (mejoras-ferreteros-bloque-2, S6), que contesta compras por el puerto {@link UltimasCompras}.
 */
@Service
public class StockBajoMinimoService {

    private final ArticuloRepository articuloRepository;
    private final UltimasCompras ultimasCompras;

    public StockBajoMinimoService(ArticuloRepository articuloRepository, UltimasCompras ultimasCompras) {
        this.articuloRepository = articuloRepository;
        this.ultimasCompras = ultimasCompras;
    }

    @Transactional(readOnly = true)
    public List<ArticuloBajoMinimo> listar(OrdenBajoMinimo orden) {
        List<ArticuloBajoMinimo> filas = articuloRepository.bajoMinimoScoped(orden);
        Map<Long, UltimaCompra> compras =
                ultimasCompras.deLosArticulos(filas.stream().map(ArticuloBajoMinimo::articuloId).toList());
        return filas.stream().map(fila -> fila.conUltimaCompra(compras.get(fila.articuloId()))).toList();
    }
}
