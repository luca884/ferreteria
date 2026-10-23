package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.ArticuloBajoMinimo;
import com.ferreteria.catalogo.model.OrdenBajoMinimo;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Articulos bajo el stock minimo (CU-09 punto 1), como el «Listado de Artículos bajo Stock Mínimo» de GDS (G72): todos
 * de una vez, porque es un listado para imprimir y salir a reponer.
 */
@Service
public class StockBajoMinimoService {

    private final ArticuloRepository articuloRepository;

    public StockBajoMinimoService(ArticuloRepository articuloRepository) {
        this.articuloRepository = articuloRepository;
    }

    @Transactional(readOnly = true)
    public List<ArticuloBajoMinimo> listar(OrdenBajoMinimo orden) {
        return articuloRepository.bajoMinimoScoped(orden);
    }
}
