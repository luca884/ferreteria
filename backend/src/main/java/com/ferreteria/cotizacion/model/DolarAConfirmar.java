package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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
                + " vino en " + pesos(nuevo) + ", un " + formato(desvio, "#,##0.##") + " % distinto del anterior ("
                + pesos(vigente) + "). Los precios siguen con " + pesos(vigente) + " hasta que lo confirmes.";
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

    private static String pesos(BigDecimal valor) {
        return "$ " + formato(valor, "#,##0.00");
    }

    /** Punto de miles y coma decimal ("1.234,56"). DecimalFormat no es thread-safe: uno por llamada. */
    private static String formato(BigDecimal valor, String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setDecimalSeparator(',');
        simbolos.setGroupingSeparator('.');
        DecimalFormat formato = new DecimalFormat(patron, simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(valor);
    }
}
