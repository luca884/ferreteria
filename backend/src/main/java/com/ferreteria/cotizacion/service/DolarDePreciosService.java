package com.ferreteria.cotizacion.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * El dolar de los precios de la ferreteria del request. Todo calculo de precio usa su dolar VIGENTE (el que fijo su
 * ultimo recalculo), no el valor de la API en ese momento: asi un articulo editado a las 14:00 sale con el mismo dolar
 * que los recalculados a las 08:00. Nunca se pasa solo a otro tipo de dolar: si el tipo elegido no tiene valor, no hay
 * vigente.
 */
@Service
public class DolarDePreciosService implements DolarDePrecios {

    static final String COTIZACION_APAGADA = "La cotización está apagada";

    private final ConfiguracionCotizacionService configuracionService;
    private final ConfiguracionCotizacionRepository configuraciones;
    private final CotizacionRepository cotizaciones;
    private final Clock reloj;

    public DolarDePreciosService(ConfiguracionCotizacionService configuracionService,
                                 ConfiguracionCotizacionRepository configuraciones, CotizacionRepository cotizaciones,
                                 Clock reloj) {
        this.configuracionService = configuracionService;
        this.configuraciones = configuraciones;
        this.cotizaciones = cotizaciones;
        this.reloj = reloj;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean activa() {
        return configuracionService.leer().isActiva();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> vigente() {
        ConfiguracionCotizacion configuracion = configuracionService.leer();
        if (configuracion.getVigente() != null) {
            return Optional.of(configuracion.getVigente());
        }
        return ultimoGuardado(configuracion);
    }

    @Override
    @Transactional
    public BigDecimal fijarUltima() {
        ConfiguracionCotizacion configuracion = configuracionService.obtener();
        if (!configuracion.isActiva()) {
            throw new ConflictException(COTIZACION_APAGADA);
        }
        BigDecimal valor = ultimoGuardado(configuracion).orElseThrow(() -> new ConflictException(SIN_COTIZACION));
        fijar(configuracion, valor);
        return valor;
    }

    @Override
    @Transactional
    public Optional<BigDecimal> reclamarRecalculoDelDia(LocalDateTime ahoraBuenosAires) {
        ConfiguracionCotizacion configuracion = configuracionService.obtener();
        Optional<BigDecimal> valor = ultimoGuardado(configuracion);
        if (valor.isEmpty()) {
            return Optional.empty(); // sin ningun valor no se marca el dia: se reintenta cuando llegue uno
        }
        if (configuraciones.reclamarRecalculoDelDiaScoped(ahoraBuenosAires) == 0) {
            return Optional.empty();
        }
        // El UPDATE limpio el contexto de persistencia: se vuelve a leer para no pisar el dia recien marcado.
        fijar(configuraciones.findPropiaScoped().orElseThrow(), valor.get());
        return valor;
    }

    /** El ultimo valor guardado del tipo y lado del dolar de precios; vacio si no hay o ese tipo no informa ese lado. */
    private Optional<BigDecimal> ultimoGuardado(ConfiguracionCotizacion configuracion) {
        return cotizaciones.findByMonedaAndCasa(CatalogoCotizaciones.USD, configuracion.getCasaPrecios())
                .map(c -> configuracion.getLadoPrecios() == LadoCotizacion.VENTA ? c.getVenta() : c.getCompra());
    }

    private void fijar(ConfiguracionCotizacion configuracion, BigDecimal valor) {
        configuracion.fijarVigente(valor, configuracion.getCasaPrecios(), configuracion.getLadoPrecios(),
                Instant.now(reloj));
        configuraciones.save(configuracion);
    }
}
