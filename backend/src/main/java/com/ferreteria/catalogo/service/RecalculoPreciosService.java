package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

/**
 * Cascada de precios cuando cambia el margen de un rubro. Vive aparte de ArticuloService para que
 * otros casos de uso (CU-05, actualizacion masiva) puedan reutilizarla.
 */
@Service
public class RecalculoPreciosService {

    private final ArticuloRepository articuloRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;

    public RecalculoPreciosService(
            ArticuloRepository articuloRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService) {
        this.articuloRepository = articuloRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
    }

    /**
     * Recalcula los articulos ACTIVOS del rubro sin margen propio, con el margen que el rubro tiene
     * ahora, y guarda un historial MASIVO por cada uno cuyo precio final cambie. Debe correr dentro
     * de la transaccion de la edicion del rubro (MANDATORY): si falla, se revierte todo junto.
     * Los articulos ya administrados por JPA se escriben al terminar esa transaccion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void porCambioDeMargenDeRubro(Rubro rubro, Long usuarioId) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        ContextoPrecio ctx = new ContextoPrecio(
                rubro.getMargenPorcentaje(), configuracion.margenDefectoPorcentaje(), configuracion.redondeoMultiplo());

        List<HistorialPrecio> historial = articuloRepository.heredanMargenDeRubroScoped(rubro.getId()).stream()
                .flatMap(articulo -> recalcular(articulo, ctx, usuarioId))
                .toList();
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
    }

    private Stream<HistorialPrecio> recalcular(Articulo articulo, ContextoPrecio ctx, Long usuarioId) {
        return articulo.recalcularPrecio(ctx).stream()
                .map(cambio -> HistorialPrecio.de(articulo, cambio, OrigenPrecio.MASIVO, usuarioId));
    }
}
