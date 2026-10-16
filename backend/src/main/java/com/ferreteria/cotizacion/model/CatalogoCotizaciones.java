package com.ferreteria.cotizacion.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogo fijo de los tipos de cotizacion que el sistema conoce: los siete tipos de dolar, mas el euro y el real
 * (estos dos solo para mostrar). Cada tipo se identifica con un codigo {@code MONEDA:casa}, por ejemplo
 * {@code USD:oficial}. Lo usan los adaptadores de las fuentes (para quedarse solo con lo conocido) y la validacion
 * de la configuracion de cada ferreteria.
 */
public final class CatalogoCotizaciones {

    public static final String USD = "USD";
    public static final String EUR = "EUR";
    public static final String BRL = "BRL";

    /** Un tipo de cotizacion: la moneda y la casa (el tipo dentro de la moneda). */
    public record Tipo(String moneda, String casa) {
        public String codigo() {
            return CatalogoCotizaciones.codigo(moneda, casa);
        }
    }

    /** Codigo a nombre para mostrar, en el orden en que se ofrecen. */
    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();

    static {
        NOMBRES.put("USD:oficial", "Oficial");
        NOMBRES.put("USD:blue", "Blue");
        NOMBRES.put("USD:bolsa", "MEP");
        NOMBRES.put("USD:contadoconliqui", "Contado con liqui");
        NOMBRES.put("USD:mayorista", "Mayorista");
        NOMBRES.put("USD:tarjeta", "Tarjeta");
        NOMBRES.put("USD:cripto", "Cripto");
        NOMBRES.put("EUR:oficial", "Euro");
        NOMBRES.put("BRL:oficial", "Real");
    }

    private CatalogoCotizaciones() {}

    public static String codigo(String moneda, String casa) {
        return moneda + ":" + casa;
    }

    /** Todos los codigos conocidos, en una lista que no se puede modificar. */
    public static List<String> codigos() {
        return List.copyOf(NOMBRES.keySet());
    }

    /** Las casas (tipos) de dolar, sin la moneda. */
    public static List<String> casasDeDolar() {
        return NOMBRES.keySet().stream()
                .filter(c -> c.startsWith(USD + ":"))
                .map(c -> c.substring(USD.length() + 1))
                .toList();
    }

    public static boolean conoce(String codigo) {
        return codigo != null && NOMBRES.containsKey(codigo);
    }

    /** Parte un codigo {@code MONEDA:casa}; vacio si esta mal armado o no esta en el catalogo. */
    public static Optional<Tipo> parsear(String codigo) {
        if (!conoce(codigo)) {
            return Optional.empty();
        }
        int separador = codigo.indexOf(':');
        return Optional.of(new Tipo(codigo.substring(0, separador), codigo.substring(separador + 1)));
    }

    /** Solo los tipos de USD sirven como dolar de los precios; el euro y el real son solo para mostrar. */
    public static boolean sirveComoDolarDePrecios(String codigo) {
        return parsear(codigo).map(t -> USD.equals(t.moneda())).orElse(false);
    }

    /** Nombre para mostrar; para un codigo desconocido, el propio codigo. */
    public static String nombre(String codigo) {
        return NOMBRES.getOrDefault(codigo, codigo);
    }
}
