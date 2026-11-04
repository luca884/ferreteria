package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Un dolar nuevo que se aleja mas del umbral del vigente de la ferreteria ({@link DesvioDeCotizacion}): los precios
 * siguen con {@code vigente} hasta que el Dueno lo confirme. {@code mensaje} es el aviso tal cual se le muestra.
 */
public record DolarAConfirmar(String casa, LadoCotizacion lado, BigDecimal vigente, BigDecimal nuevo,
                              BigDecimal desvioPorcentaje, String mensaje) {

    public static DolarAConfirmar de(String casa, LadoCotizacion lado, BigDecimal vigente, BigDecimal nuevo) {
        BigDecimal desvio = DesvioDeCotizacion.porcentaje(vigente, nuevo);
        String mensaje = "El dólar " + nombreDelTipo(casa) + " " + lado.name().toLowerCase(Locale.ROOT)
                + " vino en " + FormatoPesos.de(nuevo) + ", un " + FormatoPesos.numero(desvio, "#,##0.##")
                + " % distinto del anterior (" + FormatoPesos.de(vigente) + "). Los precios siguen con "
                + FormatoPesos.de(vigente) + " hasta que lo confirmes.";
        return new DolarAConfirmar(casa, lado, vigente, nuevo, desvio, mensaje);
    }

    /** «oficial», «blue», «MEP»: el nombre del catalogo, en minuscula salvo las siglas. */
    private static String nombreDelTipo(String casa) {
        String codigo = CatalogoCotizaciones.codigo(CatalogoCotizaciones.USD, casa);
        if (!CatalogoCotizaciones.conoce(codigo)) {
            return casa;
        }
        String nombre = CatalogoCotizaciones.nombre(codigo);
        return nombre.equals(nombre.toUpperCase(Locale.ROOT)) ? nombre : nombre.toLowerCase(Locale.ROOT);
    }
}
