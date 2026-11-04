package com.ferreteria.cotizacion.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.cotizacion.api.DolarPropuesto;
import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.DesvioDeCotizacion;
import com.ferreteria.cotizacion.model.DolarAConfirmar;
import com.ferreteria.cotizacion.model.FormatoPesos;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Un dolar que se aleja mas del umbral de la ferreteria del vigente ({@link DesvioDeCotizacion}) no se aplica solo: el recalculo diario
 * lo saltea y «Recalcular ahora» da 409 hasta que el Dueno de ESA ferreteria lo confirme. La tabla cotizacion es global
 * y no se toca: la comparacion es contra el vigente de cada ferreteria, asi una nunca confirma por otra.
 */
@Service
public class DolarDePreciosService implements DolarDePrecios {

    static final String COTIZACION_APAGADA = "La cotización está apagada";

    private static final Logger log = LoggerFactory.getLogger(DolarDePreciosService.class);

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
        return fijarUltima(false);
    }

    @Override
    @Transactional
    public BigDecimal fijarUltima(boolean confirmaDesvio) {
        return fijarUltima(confirmaDesvio, null);
    }

    @Override
    @Transactional
    public BigDecimal fijarUltima(boolean confirmaDesvio, BigDecimal dolarVisto) {
        ConfiguracionCotizacion configuracion = configuracionService.obtener();
        BigDecimal valor = ultimoAplicable(configuracion);
        if (dolarVisto != null && dolarVisto.compareTo(valor) != 0) {
            throw new ConflictException("El dólar cambió desde la vista previa (" + FormatoPesos.de(dolarVisto) + " → "
                    + FormatoPesos.de(valor) + "). Revisala de nuevo.");
        }
        Optional<DolarAConfirmar> aConfirmar = aConfirmar(configuracion, valor);
        if (aConfirmar.isPresent() && !confirmaDesvio) {
            throw new ConflictException(aConfirmar.get().mensaje());
        }
        fijar(configuracion, valor);
        return valor;
    }

    @Override
    @Transactional(readOnly = true)
    public DolarPropuesto proponerUltima() {
        ConfiguracionCotizacion configuracion = configuracionService.leer();
        BigDecimal valor = ultimoAplicable(configuracion);
        return new DolarPropuesto(valor, configuracion.getVigente(),
                aConfirmar(configuracion, valor).map(DolarAConfirmar::mensaje).orElse(null));
    }

    /** El ultimo valor guardado del dolar de precios; 409 si la cotizacion esta apagada o no hay ninguno. */
    private BigDecimal ultimoAplicable(ConfiguracionCotizacion configuracion) {
        if (!configuracion.isActiva()) {
            throw new ConflictException(COTIZACION_APAGADA);
        }
        return ultimoGuardado(configuracion).orElseThrow(() -> new ConflictException(SIN_COTIZACION));
    }

    /**
     * El dolar nuevo que el Dueno tiene que confirmar antes de que cambie los precios; vacio si no hay ninguno (dentro
     * del umbral, sin vigente fijado, con otro tipo o lado elegido despues, o con la cotizacion apagada).
     */
    @Transactional(readOnly = true)
    public Optional<DolarAConfirmar> aConfirmar() {
        ConfiguracionCotizacion configuracion = configuracionService.leer();
        if (!configuracion.isActiva()) {
            return Optional.empty();
        }
        return ultimoGuardado(configuracion).flatMap(valor -> aConfirmar(configuracion, valor));
    }

    @Override
    @Transactional
    public Optional<BigDecimal> reclamarRecalculoDelDia(LocalDateTime ahoraBuenosAires) {
        ConfiguracionCotizacion configuracion = configuracionService.obtener();
        Optional<BigDecimal> valor = ultimoGuardado(configuracion);
        if (valor.isEmpty()) {
            return Optional.empty(); // sin ningun valor no se marca el dia: se reintenta cuando llegue uno
        }
        Optional<DolarAConfirmar> aConfirmar = aConfirmar(configuracion, valor.get());
        if (aConfirmar.isPresent()) {
            // Tampoco se marca el dia: si llega un valor normal se aplica solo; si no, espera al Dueno.
            log.warn("Recalculo de la ferreteria {} en espera: {}", configuracion.getFerreteriaId(),
                    aConfirmar.get().mensaje());
            return Optional.empty();
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

    /**
     * Solo compara contra un vigente del MISMO tipo y lado: si el Dueno eligio otro dolar, el salto es decision suya.
     */
    private static Optional<DolarAConfirmar> aConfirmar(ConfiguracionCotizacion configuracion, BigDecimal valor) {
        BigDecimal vigente = configuracion.getVigente();
        boolean mismoDolar = configuracion.getCasaPrecios().equals(configuracion.getVigenteCasa())
                && configuracion.getLadoPrecios() == configuracion.getVigenteLado();
        if (!mismoDolar
                || !DesvioDeCotizacion.hayQueConfirmar(vigente, valor, configuracion.getUmbralDesvioPorcentaje())) {
            return Optional.empty();
        }
        return Optional.of(DolarAConfirmar.de(configuracion.getCasaPrecios(), configuracion.getLadoPrecios(), vigente,
                valor));
    }

    private void fijar(ConfiguracionCotizacion configuracion, BigDecimal valor) {
        configuracion.fijarVigente(valor, configuracion.getCasaPrecios(), configuracion.getLadoPrecios(),
                Instant.now(reloj));
        configuraciones.save(configuracion);
    }
}
