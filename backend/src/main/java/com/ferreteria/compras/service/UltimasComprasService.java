package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.UltimasCompras;
import com.ferreteria.compras.repository.CompraRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Compras le contesta a catalogo la ultima compra de cada articulo (mejoras-ferreteros-bloque-2, S6). */
@Service
public class UltimasComprasService implements UltimasCompras {

    private final CompraRepository compraRepository;

    public UltimasComprasService(CompraRepository compraRepository) {
        this.compraRepository = compraRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, UltimaCompra> deLosArticulos(Collection<Long> articuloIds) {
        Map<Long, UltimaCompra> ultimas = new HashMap<>();
        if (articuloIds.isEmpty()) return ultimas;
        // Vienen la mas nueva primero por articulo: la primera de cada uno es la ultima.
        for (Object[] fila : compraRepository.comprasDeScoped(articuloIds)) {
            Long articulo = ((Number) fila[0]).longValue();
            ultimas.putIfAbsent(articulo, new UltimaCompra(fecha(fila[1]), (String) fila[2]));
        }
        return ultimas;
    }

    private static LocalDate fecha(Object valor) {
        return valor instanceof Date sql ? sql.toLocalDate() : (LocalDate) valor;
    }
}
