package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.DesglosePrecioResponse;
import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloSinListaException;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.CadenaDePrecio;
import com.ferreteria.catalogo.model.EntradaCadena;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Lectura del desglose del precio de un articulo (corte 3). No lee proveedores: las bonificaciones y si la lista
 * incluye IVA son las GUARDADAS en el articulo, asi un proveedor ajeno no se puede filtrar. El articulo y el rubro se
 * buscan con findScoped. No calcula ni escribe nada: el precio final es el guardado.
 */
@Service
public class DesglosePrecioService {

    private final ArticuloRepository articuloRepository;
    private final RubroRepository rubroRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final DolarDePrecios dolarDePrecios;

    public DesglosePrecioService(
            ArticuloRepository articuloRepository,
            RubroRepository rubroRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            DolarDePrecios dolarDePrecios) {
        this.articuloRepository = articuloRepository;
        this.rubroRepository = rubroRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.dolarDePrecios = dolarDePrecios;
    }

    /** Un articulo de otra ferreteria da 404, igual que uno que no existe. */
    @Transactional(readOnly = true)
    public DesglosePrecioResponse obtener(Long id) {
        Articulo articulo = articuloRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        BigDecimal margenRubro = articulo.getRubroId() == null ? null : rubroRepository.findScoped(articulo.getRubroId())
                .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"))
                .getMargenPorcentaje();
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();

        // El dolar vigente (el del ultimo recalculo), no el de la API en este momento.
        boolean enDolares = articulo.getMoneda() == Moneda.USD;
        Optional<BigDecimal> dolar = enDolares ? dolarDePrecios.vigente() : Optional.empty();
        boolean congelada = enDolares && !dolarDePrecios.activa();

        boolean tieneLista = articulo.tieneLista();
        EntradaCadena entrada = new EntradaCadena(
                tieneLista,
                tieneLista ? articulo.getPrecioListaProveedor() : null,
                tieneLista && articulo.getListaIncluyeIva(),
                articulo.getAlicuotaIva(),
                tieneLista ? articulo.bonificacionesGuardadas() : null,
                tieneLista ? articulo.getAjustes() : null,
                articulo.getCosto(),
                articulo.getMoneda(),
                dolar.orElse(null),
                congelada,
                articulo.getMargenPorcentaje(),
                margenRubro,
                configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(),
                articulo.getPrecioFinal());
        CadenaDePrecio cadena = CadenaDePrecio.de(entrada);

        Bonificaciones bonificaciones = tieneLista ? articulo.bonificacionesGuardadas() : Bonificaciones.NINGUNA;
        Ajustes ajustes = tieneLista ? articulo.getAjustes() : Ajustes.NINGUNO;
        return new DesglosePrecioResponse(
                articulo.getId(),
                tieneLista,
                tieneLista ? null : ArticuloSinListaException.MENSAJE,
                articulo.getMoneda(),
                entrada.precioLista(),
                tieneLista ? articulo.getListaIncluyeIva() : null,
                articulo.getAlicuotaIva(),
                bonificaciones.b1(), bonificaciones.b2(), bonificaciones.b3(),
                ajustes.a1(), ajustes.a2(), ajustes.a3(),
                cadena.pasos(),
                cadena.costoDeLista(),
                articulo.getCosto(),
                cadena.coincide(),
                cadena.margen(),
                cadena.origenMargen(),
                cadena.cotizacion(),
                cadena.cotizacionCongelada(),
                configuracion.redondeoMultiplo(),
                articulo.getPrecioFinal());
    }
}
