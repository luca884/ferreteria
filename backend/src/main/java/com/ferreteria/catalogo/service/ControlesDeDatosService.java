package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.ArticuloEnControl;
import com.ferreteria.catalogo.model.ControlDeDatos;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Controles de datos del catalogo (GDS-18), como los de GDS (G81): todos los articulos del control de una vez, porque es
 * un listado para imprimir y salir a corregir. El margen negativo de un articulo en USD usa el dolar de los precios.
 */
@Service
public class ControlesDeDatosService {

    private final ArticuloRepository articuloRepository;
    private final DolarDePrecios dolar;

    public ControlesDeDatosService(ArticuloRepository articuloRepository, DolarDePrecios dolar) {
        this.articuloRepository = articuloRepository;
        this.dolar = dolar;
    }

    @Transactional(readOnly = true)
    public List<ArticuloEnControl> listar(ControlDeDatos control) {
        return articuloRepository.enControlScoped(control, dolar.vigente().orElse(null));
    }
}
