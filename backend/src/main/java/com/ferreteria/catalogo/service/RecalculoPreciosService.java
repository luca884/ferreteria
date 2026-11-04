package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.PrecioPlaneado;
import com.ferreteria.catalogo.model.RecalculoAutomatico;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final DolarDePrecios dolarDePrecios;

    public RecalculoPreciosService(
            ArticuloRepository articuloRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            RubroRepository rubroRepository,
            DolarDePrecios dolarDePrecios) {
        this.articuloRepository = articuloRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.rubroRepository = rubroRepository;
        this.dolarDePrecios = dolarDePrecios;
    }

    /**
     * Recalcula los articulos ACTIVOS del rubro sin margen propio, con el margen que el rubro tiene
     * ahora, y guarda un historial MASIVO por cada uno cuyo precio final cambie. Debe correr dentro
     * de la transaccion de la edicion del rubro (MANDATORY): si falla, se revierte todo junto.
     * Los articulos ya administrados por JPA se escriben al terminar esa transaccion.
     *
     * <p>Un articulo cuyos ajustes sobre el precio final dejarian el precio en 0 o menos conserva el que tiene, el resto
     * sigue y el articulo vuelve en los avisos devueltos (S7).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> porCambioDeMargenDeRubro(Rubro rubro, Long usuarioId) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        List<Articulo> herederos = articuloRepository.heredanMargenDeRubroScoped(rubro.getId());
        ContextoPrecio ctx = new ContextoPrecio(
                rubro.getMargenPorcentaje(), configuracion.margenDefectoPorcentaje(), configuracion.redondeoMultiplo(),
                dolarVigenteSiHayArticulosEnDolares(herederos));

        List<HistorialPrecio> historial = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Articulo articulo : herederos) {
            RecalculoAutomatico resultado = articulo.recalcularPrecioAutomatico(ctx);
            resultado.cambio().ifPresent(
                    cambio -> historial.add(HistorialPrecio.de(articulo, cambio, OrigenPrecio.MASIVO, usuarioId)));
            resultado.aviso().ifPresent(avisos::add);
        }
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return avisos;
    }

    /**
     * Cuantos articulos cambiaron de precio en un recalculo por cotizacion y los avisos de los que se saltearon porque
     * sus ajustes sobre el precio final los dejarian en 0 o menos (S7).
     */
    public record ResultadoPorCotizacion(int articulosRecalculados, List<String> avisos) {
    }

    /**
     * Recalcula con {@code dolar} el precio de TODOS los articulos en dolares de la ferreteria del request (activos e
     * inactivos, R-032) y guarda un historial COTIZACION por cada uno cuyo precio final cambie. Si el precio no cambia
     * no hay fila ni cuenta (R-030). {@code usuarioId} es null en el recalculo diario automatico («Automatico») y el
     * del Dueno en «Recalcular ahora». Devuelve cuantos articulos cambiaron de precio. Debe correr dentro de la
     * transaccion del recalculo (MANDATORY): si falla, se revierte todo junto, incluido el reclamo del dia. Un articulo
     * cuyos ajustes sobre el precio final lo dejarian en 0 o menos conserva su precio, no cuenta y vuelve en los avisos
     * (S7): el resto del lote sigue.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ResultadoPorCotizacion porCotizacion(BigDecimal dolar, Long usuarioId) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        ContextosDePrecio contextos = ContextosDePrecio.armar(
                rubroRepository.listarScoped(), configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(), dolar);

        List<HistorialPrecio> historial = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Articulo articulo : articuloRepository.enDolaresScoped()) {
            RecalculoAutomatico resultado = articulo.recalcularPrecioAutomatico(contextos.de(articulo.getRubroId()));
            resultado.cambio().ifPresent(
                    cambio -> historial.add(HistorialPrecio.de(articulo, cambio, OrigenPrecio.COTIZACION, usuarioId)));
            resultado.aviso().ifPresent(avisos::add);
        }
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return new ResultadoPorCotizacion(historial.size(), avisos);
    }

    /** Como quedaria un articulo en dolares con el dolar nuevo; si no cambia, nuevo y actual son iguales. */
    public record FilaVistaPrevia(Long articuloId, String codigoInterno, String descripcion, boolean activo,
                                  BigDecimal precioActual, BigDecimal precioNuevo) {
    }

    /** Las filas por codigo interno y los avisos de los articulos que conservarian su precio (S7). */
    public record VistaPreviaPorCotizacion(List<FilaVistaPrevia> filas, List<String> avisos) {
    }

    /**
     * Vista previa de {@link #porCotizacion}: los mismos articulos (todos los en dolares de la ferreteria del request,
     * activos e inactivos) con el precio que tendrian con {@code dolar}. No cambia ningun articulo ni escribe historial:
     * usa el calculo que no modifica la entidad.
     */
    @Transactional(readOnly = true)
    public VistaPreviaPorCotizacion vistaPreviaPorCotizacion(BigDecimal dolar) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        ContextosDePrecio contextos = ContextosDePrecio.armar(
                rubroRepository.listarScoped(), configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(), dolar);

        List<FilaVistaPrevia> filas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Articulo articulo : articuloRepository.enDolaresScoped()) {
            PrecioPlaneado planeado = articulo.precioConCostoAutomatico(articulo.getCosto(),
                    contextos.de(articulo.getRubroId()));
            planeado.aviso().ifPresent(avisos::add);
            filas.add(new FilaVistaPrevia(articulo.getId(), articulo.getCodigoInterno(), articulo.getDescripcion(),
                    articulo.isActivo(), articulo.getPrecioFinal(), planeado.precio()));
        }
        filas.sort(Comparator.comparing(FilaVistaPrevia::codigoInterno));
        return new VistaPreviaPorCotizacion(filas, avisos);
    }

    /**
     * Cuantos articulos cambiaron de precio en un recalculo por configuracion y los avisos de los que se saltearon porque
     * sus ajustes sobre el precio final los dejarian en 0 o menos (S7).
     */
    public record ResultadoRecalculo(int articulosRecalculados, List<String> avisos) {
    }

    /**
     * Recalcula los precios despues de que el Dueño cambia la configuracion de precios de la ferreteria: si cambio el
     * multiplo de redondeo, todos los articulos activos sin precio manual; si solo cambio el margen por defecto, los que
     * lo heredan (sin rubro ni margen propio). Guarda un historial MASIVO por cada precio que cambia. Debe correr dentro
     * de la transaccion del guardado (MANDATORY): si falla, tambien se revierte la configuracion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ResultadoRecalculo porCambioDeConfiguracion(
            ConfiguracionPrecios anterior, ConfiguracionPrecios nueva, Long usuarioId) {
        boolean cambioElMultiplo = cambioElMultiplo(anterior, nueva);
        if (!cambioElMultiplo && !cambioElMargen(anterior, nueva)) {
            return new ResultadoRecalculo(0, List.of());
        }
        List<Articulo> afectados = cambioElMultiplo
                ? articuloRepository.activosConPrecioCalculadoScoped()
                : articuloRepository.heredanMargenPorDefectoScoped();
        // Los herederos del margen por defecto no tienen rubro: no hace falta leer los rubros.
        List<Rubro> rubros = cambioElMultiplo ? rubroRepository.listarScoped() : List.of();
        ContextosDePrecio contextos = ContextosDePrecio.armar(rubros, nueva.margenDefectoPorcentaje(),
                nueva.redondeoMultiplo(), dolarVigenteSiHayArticulosEnDolares(afectados));

        List<HistorialPrecio> historial = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Articulo articulo : afectados) {
            RecalculoAutomatico resultado = articulo.recalcularPrecioAutomatico(contextos.de(articulo.getRubroId()));
            resultado.cambio().ifPresent(
                    cambio -> historial.add(HistorialPrecio.de(articulo, cambio, OrigenPrecio.MASIVO, usuarioId)));
            resultado.aviso().ifPresent(avisos::add);
        }
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return new ResultadoRecalculo(historial.size(), avisos);
    }

    /** Cuantos articulos recalcularia {@link #porCambioDeConfiguracion} con la configuracion nueva (aviso previo). */
    @Transactional(readOnly = true)
    public long contarPorCambioDeConfiguracion(ConfiguracionPrecios anterior, ConfiguracionPrecios nueva) {
        if (cambioElMultiplo(anterior, nueva)) {
            return articuloRepository.contarActivosConPrecioCalculadoScoped();
        }
        return cambioElMargen(anterior, nueva) ? articuloRepository.contarHeredanMargenPorDefectoScoped() : 0;
    }

    // compareTo y no equals: 35 y 35.00 son el mismo valor.
    private static boolean cambioElMultiplo(ConfiguracionPrecios anterior, ConfiguracionPrecios nueva) {
        return anterior.redondeoMultiplo().compareTo(nueva.redondeoMultiplo()) != 0;
    }

    private static boolean cambioElMargen(ConfiguracionPrecios anterior, ConfiguracionPrecios nueva) {
        return anterior.margenDefectoPorcentaje().compareTo(nueva.margenDefectoPorcentaje()) != 0;
    }

    /**
     * El dolar vigente de la ferreteria (no el valor de la API en ese momento), solo si algun heredero esta en USD: con
     * todo en pesos no se consulta. Sin ninguna cotizacion guardada no se puede calcular su precio: 409, y se revierte
     * tambien el cambio del rubro.
     */
    private BigDecimal dolarVigenteSiHayArticulosEnDolares(List<Articulo> articulos) {
        if (articulos.stream().noneMatch(articulo -> articulo.getMoneda() == Moneda.USD)) {
            return null;
        }
        return dolarDePrecios.vigente().orElseThrow(() -> new ConflictException(DolarDePrecios.SIN_COTIZACION));
    }
}
