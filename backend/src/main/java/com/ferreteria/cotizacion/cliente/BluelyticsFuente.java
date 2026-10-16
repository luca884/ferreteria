package com.ferreteria.cotizacion.cliente;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteDeCotizaciones;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bluelytics, el respaldo. Solo trae el oficial, el blue y el euro oficial; no tiene MEP ni los otros tipos, asi que
 * esos conservan su ultimo valor guardado cuando DolarApi esta caida. {@code blue_euro} no esta en el catalogo y se
 * ignora.
 */
@Component
@Order(2)
public class BluelyticsFuente implements FuenteDeCotizaciones {

    public static final String NOMBRE = "BLUELYTICS";

    /** Clave de Bluelytics a codigo MONEDA:casa del catalogo. */
    private static final Map<String, String> CLAVES = new LinkedHashMap<>();

    static {
        CLAVES.put("oficial", "USD:oficial");
        CLAVES.put("blue", "USD:blue");
        CLAVES.put("oficial_euro", "EUR:oficial");
    }

    private final ClienteJson cliente;

    @Autowired
    public BluelyticsFuente(@Value("${app.cotizacion.bluelytics.url}") String url) {
        this(url, ClienteJson.TIMEOUT_CONEXION, ClienteJson.TIMEOUT_LECTURA);
    }

    BluelyticsFuente(String url, Duration conexion, Duration lectura) {
        this.cliente = ClienteJson.de(url, conexion, lectura);
    }

    /** Con un cliente ya armado (los tests lo atan a un MockRestServiceServer). */
    BluelyticsFuente(RestClient cliente) {
        this.cliente = new ClienteJson(cliente);
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public List<CotizacionLeida> leer() {
        JsonNode raiz = cliente.leer("/v2/latest");
        if (!raiz.isObject()) {
            throw new FuenteNoDisponibleException("/v2/latest no devolvio un objeto");
        }
        Instant actualizadaEn = ClienteJson.instante(raiz, "last_update");
        List<CotizacionLeida> leidas = new ArrayList<>();
        CLAVES.forEach((clave, codigo) -> {
            JsonNode nodo = raiz.get(clave);
            if (nodo == null) {
                return;
            }
            var compra = ClienteJson.importe(nodo.get("value_buy"));
            var venta = ClienteJson.importe(nodo.get("value_sell"));
            if (compra == null && venta == null) {
                return;
            }
            var tipo = CatalogoCotizaciones.parsear(codigo).orElseThrow();
            leidas.add(new CotizacionLeida(tipo.moneda(), tipo.casa(), CatalogoCotizaciones.nombre(codigo), compra,
                    venta, actualizadaEn));
        });
        return leidas;
    }
}
