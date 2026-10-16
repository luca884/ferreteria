package com.ferreteria.cotizacion.cliente;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * dolarapi.com, la fuente principal. {@code /v1/dolares} trae todos los tipos de dolar; {@code /v1/cotizaciones}, el
 * euro y el real (que son solo para mostrar). Si {@code /v1/dolares} anda y {@code /v1/cotizaciones} falla, se
 * devuelven igual los dolares.
 */
@Component
@Order(1)
public class DolarApiFuente implements FuenteDeCotizaciones {

    public static final String NOMBRE = "DOLARAPI";

    private static final Logger log = LoggerFactory.getLogger(DolarApiFuente.class);

    private final ClienteJson cliente;

    @Autowired
    public DolarApiFuente(@Value("${app.cotizacion.dolarapi.url}") String url) {
        this(url, ClienteJson.TIMEOUT_CONEXION, ClienteJson.TIMEOUT_LECTURA);
    }

    DolarApiFuente(String url, Duration conexion, Duration lectura) {
        this.cliente = ClienteJson.de(url, conexion, lectura);
    }

    /** Con un cliente ya armado (los tests lo atan a un MockRestServiceServer). */
    DolarApiFuente(RestClient cliente) {
        this.cliente = new ClienteJson(cliente);
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public List<CotizacionLeida> leer() {
        JsonNode dolares = cliente.leer("/v1/dolares");
        if (!dolares.isArray()) {
            throw new FuenteNoDisponibleException("/v1/dolares no devolvio una lista");
        }
        List<CotizacionLeida> leidas = new ArrayList<>(extraer(dolares, Set.of(CatalogoCotizaciones.USD)));
        try {
            JsonNode otras = cliente.leer("/v1/cotizaciones");
            if (otras.isArray()) {
                leidas.addAll(extraer(otras, Set.of(CatalogoCotizaciones.EUR, CatalogoCotizaciones.BRL)));
            }
        } catch (FuenteNoDisponibleException e) {
            log.warn("DolarApi: no se pudo leer /v1/cotizaciones, se siguen usando los dolares: {}", e.getMessage());
        }
        return leidas;
    }

    private static List<CotizacionLeida> extraer(JsonNode lista, Set<String> monedas) {
        List<CotizacionLeida> leidas = new ArrayList<>();
        for (JsonNode nodo : lista) {
            String moneda = ClienteJson.texto(nodo, "moneda");
            String casa = ClienteJson.texto(nodo, "casa");
            if (moneda == null || casa == null || !monedas.contains(moneda)
                    || !CatalogoCotizaciones.conoce(CatalogoCotizaciones.codigo(moneda, casa))) {
                continue;
            }
            var compra = ClienteJson.importe(nodo.get("compra"));
            var venta = ClienteJson.importe(nodo.get("venta"));
            if (compra == null && venta == null) {
                continue;
            }
            leidas.add(new CotizacionLeida(moneda, casa,
                    CatalogoCotizaciones.nombre(CatalogoCotizaciones.codigo(moneda, casa)), compra, venta,
                    ClienteJson.instante(nodo, "fechaActualizacion")));
        }
        return leidas;
    }
}
