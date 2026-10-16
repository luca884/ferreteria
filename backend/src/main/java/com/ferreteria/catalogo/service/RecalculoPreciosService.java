package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final RubroRepository rubroRepository;

    public RecalculoPreciosService(
            ArticuloRepository articuloRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            RubroRepository rubroRepository) {
        this.articuloRepository = articuloRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.rubroRepository = rubroRepository;
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

    /**
     * Recalcula con {@code dolar} el precio de TODOS los articulos en dolares de la ferreteria del request (activos e
     * inactivos, R-032) y guarda un historial COTIZACION por cada uno cuyo precio final cambie. Si el precio no cambia
     * no hay fila ni cuenta (R-030). {@code usuarioId} es null en el recalculo diario automatico («Automatico») y el
     * del Dueno en «Recalcular ahora». Devuelve cuantos articulos cambiaron de precio. Debe correr dentro de la
     * transaccion del recalculo (MANDATORY): si falla, se revierte todo junto, incluido el reclamo del dia.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int porCotizacion(BigDecimal dolar, Long usuarioId) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        ContextosDePrecio contextos = ContextosDePrecio.armar(
                rubroRepository.listarScoped(), configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(), dolar);

        List<HistorialPrecio> historial = articuloRepository.enDolaresScoped().stream()
                .flatMap(articulo -> articulo.recalcularPrecio(contextos.de(articulo.getRubroId())).stream()
                        .map(cambio -> HistorialPrecio.de(articulo, cambio, OrigenPrecio.COTIZACION, usuarioId)))
                .toList();
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return historial.size();
    }

    private Stream<HistorialPrecio> recalcular(Articulo articulo, ContextoPrecio ctx, Long usuarioId) {
        return articulo.recalcularPrecio(ctx).stream()
                .map(cambio -> HistorialPrecio.de(articulo, cambio, OrigenPrecio.MASIVO, usuarioId));
    }
}
