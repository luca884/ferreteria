package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.MargenNegativo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.VariacionFuerte;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ArticulosParaCompraService implements ArticulosParaCompra {

    private final ArticuloRepository articuloRepository;
    private final RubroRepository rubroRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final DolarDePrecios dolarDePrecios;

    public ArticulosParaCompraService(
            ArticuloRepository articuloRepository,
            RubroRepository rubroRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            DolarDePrecios dolarDePrecios) {
        this.articuloRepository = articuloRepository;
        this.rubroRepository = rubroRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.dolarDePrecios = dolarDePrecios;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostoDeCompra> planear(Map<Long, BigDecimal> costos) {
        if (costos.isEmpty()) {
            return List.of();
        }
        List<Articulo> articulos = articuloRepository.porIdsScoped(costos.keySet());
        ContextosDePrecio contextos = contextos(articulos);
        return articulos.stream().map(articulo -> {
            BigDecimal costoNuevo = costos.get(articulo.getId());
            ContextoPrecio ctx = contextos.de(articulo.getRubroId());
            BigDecimal precioNuevo = articulo.precioConCosto(costoNuevo, ctx);
            return costoDeCompra(articulo, articulo.getCosto(), costoNuevo, articulo.getPrecioFinal(), precioNuevo,
                    articulo.margenNegativoCon(costoNuevo, ctx));
        }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, Long usuarioId) {
        if (costos.isEmpty()) {
            return List.of();
        }
        List<Articulo> articulos = articuloRepository.porIdsScoped(costos.keySet());
        if (articulos.size() != costos.size()) {
            throw new IllegalStateException("Hay artículos de la compra que no existen en la ferretería.");
        }
        ContextosDePrecio contextos = contextos(articulos);
        List<CostoDeCompra> aplicados = new ArrayList<>();
        List<HistorialPrecio> historial = new ArrayList<>();
        for (Articulo articulo : articulos) {
            BigDecimal costoAnterior = articulo.getCosto();
            BigDecimal precioAnterior = articulo.getPrecioFinal();
            ContextoPrecio ctx = contextos.de(articulo.getRubroId());
            Optional<CambioPrecio> cambio = articulo.ajustarCosto(costos.get(articulo.getId()), ctx);
            cambio.ifPresent(c -> historial.add(HistorialPrecio.de(articulo, c, OrigenPrecio.COMPRA, usuarioId)));
            aplicados.add(costoDeCompra(
                    articulo, costoAnterior, articulo.getCosto(), precioAnterior, articulo.getPrecioFinal(),
                    articulo.margenNegativoCon(articulo.getCosto(), ctx)));
        }
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return aplicados;
    }

    /** Contextos de la ferreteria; el dolar vigente se pide solo si hay algun articulo en USD (sin cotizacion: 409). */
    private ContextosDePrecio contextos(List<Articulo> articulos) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        BigDecimal dolar = articulos.stream().anyMatch(a -> a.getMoneda() == Moneda.USD)
                ? dolarDePrecios.vigente().orElseThrow(() -> new ConflictException(DolarDePrecios.SIN_COTIZACION))
                : null;
        return ContextosDePrecio.armar(rubroRepository.listarScoped(), configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(), dolar);
    }

    private static CostoDeCompra costoDeCompra(Articulo articulo, BigDecimal costoAnterior, BigDecimal costoNuevo,
                                               BigDecimal precioAnterior, BigDecimal precioNuevo,
                                               Optional<String> margenNegativo) {
        String advertencia = MargenNegativo.junto(
                VariacionFuerte.mensaje(VariacionFuerte.Sujeto.COSTO, costoAnterior, costoNuevo)
                        .or(() -> VariacionFuerte.mensaje(VariacionFuerte.Sujeto.PRECIO, precioAnterior, precioNuevo)),
                margenNegativo);
        return new CostoDeCompra(articulo.getId(), articulo.getCodigoInterno(), articulo.getDescripcion(),
                articulo.getUnidad().name(), articulo.getMoneda().name(), articulo.isActivo(),
                costoAnterior, costoNuevo, precioAnterior, precioNuevo, advertencia);
    }
}
