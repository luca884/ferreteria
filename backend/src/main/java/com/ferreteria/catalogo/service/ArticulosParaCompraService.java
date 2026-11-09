package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.catalogo.api.RecargoDeCompra;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.MargenNegativo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.PrecioPlaneado;
import com.ferreteria.catalogo.model.RecargoCosto;
import com.ferreteria.catalogo.model.RecargosDeCosto;
import com.ferreteria.catalogo.model.Unidad;
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
import java.util.Collection;
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
    public List<RecargoDeCompra> validarRecargos(List<RecargoDeCompra> recargos) {
        return aRecargos(recargos).lista().stream()
                .map(r -> new RecargoDeCompra(r.nombre(), r.porcentaje()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticuloPorCodigo> porCodigoDeProveedor(Long proveedorId, Collection<String> codigos) {
        if (codigos.isEmpty()) {
            return List.of();
        }
        return articuloRepository.activosDeProveedorPorCodigoScoped(proveedorId, codigos).stream()
                .map(a -> new ArticuloPorCodigo(a.getCodigoProveedor(), a.getId(), a.getDescripcion(),
                        a.getUnidad().name(), a.getCodigoInterno(), a.getMoneda().name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostoDeCompra> planear(Map<Long, BigDecimal> costos, List<RecargoDeCompra> pedidos) {
        RecargosDeCosto recargos = aRecargos(pedidos);
        if (costos.isEmpty()) {
            return List.of();
        }
        List<Articulo> articulos = articuloRepository.porIdsScoped(costos.keySet());
        ContextosDePrecio contextos = contextos(articulos);
        return articulos.stream().map(articulo -> {
            BigDecimal costoNuevo = costos.get(articulo.getId());
            ContextoPrecio ctx = contextos.de(articulo.getRubroId());
            PrecioPlaneado planeado = articulo.precioConCostoAutomatico(costoNuevo, recargos, ctx);
            Optional<String> margenNegativo = articulo.isPrecioManual()
                    ? MargenNegativo.mensaje(costoNuevo, recargos, ctx.cotizacionPara(articulo.getMoneda()),
                            articulo.getAlicuotaIva(), articulo.getPrecioFinal())
                    : Optional.empty();
            boolean conRecargos = !recargos.estaVacia() || !articulo.getRecargosCosto().estaVacia();
            return costoDeCompra(articulo, articulo.getCosto(), costoNuevo, articulo.getPrecioFinal(),
                    planeado.precio(), margenNegativo, planeado.aviso(),
                    articulo.costoReal(), recargos.aplicar(costoNuevo), conRecargos);
        }).toList();
    }

    /** Sobrescrito para que la transaccion obligatoria valga tambien sin recargos (un default de la interfaz no la tendria). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, Long usuarioId) {
        return aplicar(costos, List.of(), usuarioId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, List<RecargoDeCompra> pedidos, Long usuarioId) {
        RecargosDeCosto recargos = aRecargos(pedidos);
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
            BigDecimal costoRealAnterior = articulo.costoReal();
            // Antes de pisarlos: los recargos que tenia el articulo tambien hacen que se hable del costo real.
            boolean conRecargos = !recargos.estaVacia() || !articulo.getRecargosCosto().estaVacia();
            ContextoPrecio ctx = contextos.de(articulo.getRubroId());
            // El costo y los recargos de la compra (que pisan a los del articulo; vacios, los borran) en un solo calculo
            // del precio: si los ajustes no entran, queda el precio de antes con el aviso. El historial es un solo cambio
            // entre el estado de antes y el de despues.
            Optional<String> aviso = articulo
                    .ajustarCostoYRecargosAutomatico(costos.get(articulo.getId()), recargos, ctx).aviso();
            CambioPrecio.entre(costoAnterior, articulo.getCosto(), precioAnterior, articulo.getPrecioFinal())
                    .ifPresent(c -> historial.add(HistorialPrecio.de(articulo, c, OrigenPrecio.COMPRA, usuarioId)));
            aplicados.add(costoDeCompra(
                    articulo, costoAnterior, articulo.getCosto(), precioAnterior, articulo.getPrecioFinal(),
                    articulo.margenNegativoCon(articulo.getCosto(), ctx), aviso,
                    costoRealAnterior, articulo.costoReal(), conRecargos));
        }
        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return aplicados;
    }

    private static RecargosDeCosto aRecargos(List<RecargoDeCompra> pedidos) {
        List<RecargoDeCompra> lista = pedidos == null ? List.of() : pedidos;
        return new RecargosDeCosto(lista.stream().map(r -> new RecargoCosto(r.nombre(), r.porcentaje())).toList());
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

    /**
     * La variacion fuerte del costo se mide sobre el costo real, como el contador de la compra: es el que mueve el
     * precio. {@code conRecargos} (la compra o el articulo, antes de la compra, tienen recargos) hace que el aviso diga
     * «El costo real»; sin recargos el costo real es el de la factura y dice «El costo», como siempre. El costo va en la
     * moneda del articulo y el precio, en pesos; los dos por la unidad del articulo.
     */
    private static CostoDeCompra costoDeCompra(Articulo articulo, BigDecimal costoAnterior, BigDecimal costoNuevo,
                                               BigDecimal precioAnterior, BigDecimal precioNuevo,
                                               Optional<String> margenNegativo, Optional<String> precioSalteado,
                                               BigDecimal costoRealAnterior, BigDecimal costoRealNuevo,
                                               boolean conRecargos) {
        VariacionFuerte.Sujeto delCosto = conRecargos ? VariacionFuerte.Sujeto.COSTO_REAL : VariacionFuerte.Sujeto.COSTO;
        Unidad unidad = articulo.getUnidad();
        String conMargen = MargenNegativo.junto(
                VariacionFuerte.mensaje(delCosto, costoRealAnterior, costoRealNuevo, articulo.getMoneda(), unidad)
                        .or(() -> VariacionFuerte.mensaje(VariacionFuerte.Sujeto.PRECIO, precioAnterior, precioNuevo,
                                Moneda.ARS, unidad)),
                margenNegativo);
        // El aviso de que el precio no se cambia va al final, con el mismo patron de "junto".
        String advertencia = MargenNegativo.junto(Optional.ofNullable(conMargen), precioSalteado);
        return new CostoDeCompra(articulo.getId(), articulo.getCodigoInterno(), articulo.getDescripcion(),
                articulo.getUnidad().name(), articulo.getMoneda().name(), articulo.isActivo(),
                costoAnterior, costoNuevo, precioAnterior, precioNuevo, advertencia,
                costoRealAnterior, costoRealNuevo, articulo.getStockActual(), articulo.getAlicuotaIva().porcentaje());
    }
}
