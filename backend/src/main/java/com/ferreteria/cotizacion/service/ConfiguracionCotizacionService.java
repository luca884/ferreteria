package com.ferreteria.cotizacion.service;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.repository.ConfiguracionCotizacionRepository;
import com.ferreteria.cotizacion.web.dto.ConfiguracionCotizacionRequest;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Configuracion de cotizacion de la ferreteria del request (siempre del JWT, nunca de un parametro): se crea con los
 * valores por defecto la primera vez que hace falta, y el PUT la valida contra el catalogo fijo. Guardarla NO toca
 * el dolar vigente ni ningun precio: un cambio se aplica en el proximo recalculo.
 */
@Service
public class ConfiguracionCotizacionService {

    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final ConfiguracionCotizacionRepository repositorio;

    public ConfiguracionCotizacionService(ConfiguracionCotizacionRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** La configuracion de la ferreteria; si todavia no tiene, la crea con los valores por defecto. */
    @Transactional
    public ConfiguracionCotizacion obtener() {
        return repositorio.findPropiaScoped()
                .orElseGet(() -> repositorio.save(ConfiguracionCotizacion.porDefecto(TenantContext.requireFerreteriaId())));
    }

    /**
     * Lo mismo que {@link #obtener()} pero sin guardar nada: si la ferreteria no tiene fila devuelve una por defecto
     * sin persistir. Para las lecturas, que pueden correr en una transaccion de solo lectura.
     */
    @Transactional(readOnly = true)
    public ConfiguracionCotizacion leer() {
        return repositorio.findPropiaScoped()
                .orElseGet(() -> ConfiguracionCotizacion.porDefecto(TenantContext.requireFerreteriaId()));
    }

    @Transactional
    public ConfiguracionCotizacion actualizar(ConfiguracionCotizacionRequest pedido) {
        Map<String, String> errores = new LinkedHashMap<>();
        String casa = validarDolarDePrecios(pedido.dolarPrecios(), errores);
        if (pedido.ladoPrecios() == null) {
            errores.put("ladoPrecios", "Elegí si los precios usan la compra o la venta.");
        }
        List<String> visibles = validarVisibles(pedido.visibles(), errores);
        LocalTime hora = validarHora(pedido.horaRecalculo(), errores);
        if (pedido.activa() == null) {
            errores.put("activa", "Indicá si la cotización está activada.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }

        ConfiguracionCotizacion configuracion = obtener();
        configuracion.setActiva(pedido.activa());
        configuracion.setCasaPrecios(casa);
        configuracion.setLadoPrecios(pedido.ladoPrecios());
        configuracion.setVisibles(visibles);
        configuracion.setHoraRecalculo(hora);
        return repositorio.save(configuracion);
    }

    /** Devuelve la casa (por ejemplo "oficial") del dolar elegido, o null si hay error. */
    private static String validarDolarDePrecios(String codigo, Map<String, String> errores) {
        if (codigo == null || codigo.isBlank()) {
            errores.put("dolarPrecios", "Elegí el dólar en el que cotizan los precios.");
            return null;
        }
        if (codigo.contains(",")) {
            errores.put("dolarPrecios", "Elegí un solo dólar para los precios.");
            return null;
        }
        if (!CatalogoCotizaciones.conoce(codigo)) {
            errores.put("dolarPrecios", "El dólar elegido para los precios no existe.");
            return null;
        }
        if (!CatalogoCotizaciones.sirveComoDolarDePrecios(codigo)) {
            errores.put("dolarPrecios",
                    "El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.");
            return null;
        }
        return CatalogoCotizaciones.parsear(codigo).orElseThrow().casa();
    }

    private static List<String> validarVisibles(List<String> visibles, Map<String, String> errores) {
        if (visibles == null || visibles.isEmpty()) {
            errores.put("visibles", "Elegí al menos una cotización para mostrar.");
            return List.of();
        }
        for (String visible : visibles) {
            if (!CatalogoCotizaciones.conoce(visible)) {
                errores.put("visibles", "La cotización «" + visible + "» no existe.");
                return List.of();
            }
        }
        return List.copyOf(new LinkedHashSet<>(visibles));
    }

    private static LocalTime validarHora(String hora, Map<String, String> errores) {
        try {
            if (hora != null) {
                return LocalTime.parse(hora, HORA);
            }
        } catch (DateTimeParseException e) {
            // cae al error de abajo
        }
        errores.put("horaRecalculo", "La hora tiene que tener el formato HH:mm, por ejemplo 08:00.");
        return null;
    }
}
