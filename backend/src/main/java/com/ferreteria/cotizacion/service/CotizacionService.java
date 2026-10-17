package com.ferreteria.cotizacion.service;

import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.Cotizacion;
import com.ferreteria.cotizacion.model.DolarAConfirmar;
import com.ferreteria.cotizacion.repository.CotizacionRepository;
import com.ferreteria.cotizacion.web.dto.CotizacionResponse;
import com.ferreteria.cotizacion.web.dto.PreciosResponse;
import com.ferreteria.cotizacion.web.dto.TipoCotizacionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Arma lo que ve la ferreteria del request: los tipos que eligio mostrar, cual es el dolar de sus precios y si la
 * cotizacion esta desactualizada. Solo lee la base; nunca llama a una fuente externa.
 *
 * <p>Desactualizada = mas de 1 hora sin una respuesta exitosa de alguna API. Se mide con {@code obtenida_en} (cuando
 * respondio la API), no con la fecha del mercado: un fin de semana con las APIs respondiendo no da un aviso falso.
 */
@Service
public class CotizacionService {

    static final Duration UMBRAL_DESACTUALIZADA = Duration.ofHours(1);

    private final ConfiguracionCotizacionService configuracionService;
    private final CotizacionRepository cotizaciones;
    private final DolarDePreciosService dolar;
    private final Clock reloj;

    public CotizacionService(ConfiguracionCotizacionService configuracionService, CotizacionRepository cotizaciones,
                             DolarDePreciosService dolar, Clock reloj) {
        this.configuracionService = configuracionService;
        this.cotizaciones = cotizaciones;
        this.dolar = dolar;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public CotizacionResponse obtener() {
        ConfiguracionCotizacion configuracion = configuracionService.leer();
        Instant ahora = Instant.now(reloj);
        Instant ultimaRespuesta = cotizaciones.ultimaObtenidaEn().orElse(null);
        String codigoDePrecios = CatalogoCotizaciones.codigo(CatalogoCotizaciones.USD, configuracion.getCasaPrecios());

        List<TipoCotizacionResponse> visibles = configuracion.getVisibles().stream()
                .map(codigo -> tipo(codigo, codigoDePrecios, ahora))
                .toList();
        PreciosResponse precios = new PreciosResponse(codigoDePrecios, configuracion.getLadoPrecios(),
                dolar.vigente().orElse(null), configuracion.getVigenteDesde(),
                dolar.aConfirmar().map(DolarAConfirmar::nuevo).orElse(null));

        return new CotizacionResponse(configuracion.isActiva(), desactualizada(ultimaRespuesta, ahora),
                ultimaRespuesta, visibles, precios);
    }

    private TipoCotizacionResponse tipo(String codigo, String codigoDePrecios, Instant ahora) {
        boolean esDePrecios = codigo.equals(codigoDePrecios);
        Optional<Cotizacion> guardada = buscar(codigo);
        if (guardada.isEmpty()) {
            return new TipoCotizacionResponse(codigo, CatalogoCotizaciones.nombre(codigo), null, null, null, null, null,
                    false, esDePrecios);
        }
        Cotizacion c = guardada.get();
        return new TipoCotizacionResponse(codigo, c.getNombre(), c.getCompra(), c.getVenta(), c.getFuente(),
                c.getActualizadaEn(), c.getObtenidaEn(), desactualizada(c.getObtenidaEn(), ahora), esDePrecios);
    }

    private Optional<Cotizacion> buscar(String codigo) {
        int separador = codigo.indexOf(':');
        if (separador <= 0 || separador == codigo.length() - 1) {
            return Optional.empty();
        }
        return cotizaciones.findByMonedaAndCasa(codigo.substring(0, separador), codigo.substring(separador + 1));
    }

    /** Mas de 1 hora desde la ultima respuesta exitosa; sin ninguna respuesta todavia no hay nada que avisar. */
    private static boolean desactualizada(Instant obtenidaEn, Instant ahora) {
        return obtenidaEn != null && Duration.between(obtenidaEn, ahora).compareTo(UMBRAL_DESACTUALIZADA) > 0;
    }
}
