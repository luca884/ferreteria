package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.ArticuloEnControl;
import com.ferreteria.catalogo.model.ControlDeDatos;
import com.ferreteria.catalogo.model.MargenNegativo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.RecargosDeCosto;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controles de datos del catalogo (GDS-18), como los de GDS (G81): todos los articulos del control de una vez, porque es
 * un listado para imprimir y salir a corregir. Cada fila trae el costo real si el articulo tiene recargos (CU-13). El
 * margen negativo es la misma regla que el aviso del precio manual ({@link MargenNegativo}, con el costo real); un
 * articulo en USD usa el dolar de los precios y, sin dolar, no sale.
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
        BigDecimal cotizacion = dolar.vigente().orElse(null);
        List<ArticuloEnControl> filas = articuloRepository.enControlScoped(control, cotizacion);
        Map<Long, RecargosDeCosto> recargos = articuloRepository.recargosDeScoped(
                filas.stream().map(ArticuloEnControl::articuloId).toList());
        List<ArticuloEnControl> resultado = new ArrayList<>();
        for (ArticuloEnControl fila : filas) {
            RecargosDeCosto suyos = recargos.getOrDefault(fila.articuloId(), RecargosDeCosto.NINGUNO);
            if (control == ControlDeDatos.MARGEN_NEGATIVO && !margenNegativo(fila, suyos, cotizacion)) {
                continue;
            }
            resultado.add(fila.conRecargos(suyos));
        }
        return resultado;
    }

    private static boolean margenNegativo(ArticuloEnControl fila, RecargosDeCosto recargos, BigDecimal cotizacion) {
        BigDecimal cotizacionDeLaFila = Moneda.valueOf(fila.moneda()) == Moneda.USD ? cotizacion : BigDecimal.ONE;
        if (cotizacionDeLaFila == null) {
            return false;
        }
        return MargenNegativo.mensaje(fila.costo(), recargos, cotizacionDeLaFila, AlicuotaIva.valueOf(fila.alicuotaIva()),
                fila.precioFinal()).isPresent();
    }
}
