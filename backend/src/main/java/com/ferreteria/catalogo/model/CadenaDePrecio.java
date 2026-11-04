package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * El desglose del precio de un articulo, paso a paso. Dominio puro y de solo lectura: no recalcula el precio, el
 * {@code PRECIO_FINAL} es siempre el guardado.
 *
 * <p>Los importes de lista, bonificaciones y ajustes son el producto exacto hasta ese paso, dividido UNA vez a 4
 * decimales HALF_UP solo para mostrarlo: no alimentan ningun calculo. El costo de la lista sale de
 * {@link CostoDeLista}. Despues del costo los pasos parten del costo GUARDADO (con sus recargos, el costo real), con
 * productos exactos como {@link PrecioCalculator}.
 *
 * @param costoDeLista el costo que sale de la lista con los ajustes y las bonificaciones guardadas; null sin lista
 * @param coincide     si el costo guardado es igual por valor al de la lista; null sin lista
 * @param cotizacion   la del desglose, o null si el articulo esta en pesos o no hay ninguna
 * @param cotizacionCongelada true si el articulo esta en dolares y la cotizacion esta apagada (se usa la ultima)
 */
public record CadenaDePrecio(
        List<PasoDePrecio> pasos,
        BigDecimal costoDeLista,
        Boolean coincide,
        BigDecimal margen,
        OrigenMargen origenMargen,
        BigDecimal cotizacion,
        boolean cotizacionCongelada) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final int ESCALA_MOSTRADA = 4;

    public CadenaDePrecio {
        pasos = List.copyOf(pasos);
    }

    public static CadenaDePrecio de(EntradaCadena entrada) {
        return de(entrada, RecargosDeCosto.NINGUNO);
    }

    /**
     * La cadena con los recargos del costo (CU-13): despues del COSTO va un paso RECARGO por cada uno y el COSTO_REAL, y
     * de ese costo parten la cotizacion, el margen y el IVA. Sin recargos no hay pasos nuevos y la cadena es la de siempre.
     */
    public static CadenaDePrecio de(EntradaCadena entrada, RecargosDeCosto recargos) {
        List<PasoDePrecio> pasos = new ArrayList<>();
        BigDecimal costoDeLista = null;
        Boolean coincide = null;
        if (entrada.tieneLista()) {
            pasosDeLista(entrada, pasos);
            costoDeLista = CostoDeLista.costo(entrada.precioLista(), entrada.alicuota(), entrada.listaIncluyeIva(),
                entrada.bonificaciones(), entrada.ajustes());
            coincide = costoDeLista.compareTo(entrada.costoGuardado()) == 0;
        }
        pasos.add(new PasoDePrecio(TipoPaso.COSTO, null, entrada.costoGuardado()));
        BigDecimal costoReal = pasosDeRecargos(entrada.costoGuardado(), recargos, pasos);

        BigDecimal margen = PrecioCalculator.margenEfectivo(
            entrada.margenPropio(), entrada.margenRubro(), entrada.margenDefecto());
        OrigenMargen origen = PrecioCalculator.origenMargen(
            entrada.margenPropio(), entrada.margenRubro(), entrada.margenDefecto());
        BigDecimal cotizacion = entrada.moneda() == Moneda.USD ? entrada.cotizacion() : null;
        // Un articulo en dolares sin ninguna cotizacion no se puede pasar a pesos: la cadena termina en el costo.
        if (entrada.moneda() == Moneda.ARS || cotizacion != null) {
            pasosDePrecio(entrada, costoReal, margen, cotizacion, pasos);
        }
        boolean congelada = entrada.moneda() == Moneda.USD && entrada.cotizacionCongelada();
        return new CadenaDePrecio(pasos, costoDeLista, coincide, margen, origen, cotizacion, congelada);
    }

    /** LISTA, SIN_IVA, una BONIFICACION por cada una no nula y un AJUSTE por cada uno no nulo. */
    private static void pasosDeLista(EntradaCadena entrada, List<PasoDePrecio> pasos) {
        // importe = lista x (100 si incluye IVA) x prod(factores) / ((100 + iva si incluye IVA) x 100^factores)
        BigDecimal numerador = entrada.precioLista();
        BigDecimal denominador = BigDecimal.ONE;
        pasos.add(new PasoDePrecio(TipoPaso.LISTA, null, mostrado(numerador, denominador)));
        if (entrada.listaIncluyeIva()) {
            BigDecimal iva = entrada.alicuota().porcentaje();
            numerador = numerador.multiply(CIEN);
            denominador = CIEN.add(iva);
            pasos.add(new PasoDePrecio(TipoPaso.SIN_IVA, iva, mostrado(numerador, denominador)));
        }
        Bonificaciones bonificaciones = entrada.bonificaciones() == null ? Bonificaciones.NINGUNA : entrada.bonificaciones();
        for (BigDecimal bonificacion : presentes(bonificaciones.b1(), bonificaciones.b2(), bonificaciones.b3())) {
            numerador = numerador.multiply(CIEN.subtract(bonificacion));
            denominador = denominador.multiply(CIEN);
            pasos.add(new PasoDePrecio(TipoPaso.BONIFICACION, bonificacion, mostrado(numerador, denominador)));
        }
        Ajustes ajustes = entrada.ajustes() == null ? Ajustes.NINGUNO : entrada.ajustes();
        for (BigDecimal ajuste : presentes(ajustes.a1(), ajustes.a2(), ajustes.a3())) {
            numerador = numerador.multiply(CIEN.add(ajuste));
            denominador = denominador.multiply(CIEN);
            pasos.add(new PasoDePrecio(TipoPaso.AJUSTE, ajuste, mostrado(numerador, denominador)));
        }
    }

    /**
     * Un RECARGO por cada recargo (costo x % / 100, a 2 decimales HALF_UP, solo para mostrar) y el COSTO_REAL, que es el
     * de {@link RecargosDeCosto#aplicar}. Devuelve el costo real; sin recargos, el costo tal cual y ningun paso.
     */
    private static BigDecimal pasosDeRecargos(BigDecimal costo, RecargosDeCosto recargos, List<PasoDePrecio> pasos) {
        if (recargos.estaVacia()) {
            return costo;
        }
        for (RecargoCosto recargo : recargos.lista()) {
            BigDecimal importe = costo.multiply(recargo.porcentaje()).divide(CIEN, 2, RoundingMode.HALF_UP);
            pasos.add(new PasoDePrecio(TipoPaso.RECARGO, recargo.porcentaje(), importe));
        }
        BigDecimal costoReal = recargos.aplicar(costo);
        pasos.add(new PasoDePrecio(TipoPaso.COSTO_REAL, null, costoReal));
        return costoReal;
    }

    /** COTIZACION (solo USD), MARGEN, IVA, REDONDEO (si hay multiplo) y el PRECIO_FINAL guardado, desde el costo real. */
    private static void pasosDePrecio(EntradaCadena entrada, BigDecimal costoReal, BigDecimal margen,
                                      BigDecimal cotizacion, List<PasoDePrecio> pasos) {
        BigDecimal numerador = costoReal;
        if (cotizacion != null) {
            numerador = numerador.multiply(cotizacion);
            pasos.add(new PasoDePrecio(TipoPaso.COTIZACION, null, mostrado(numerador, BigDecimal.ONE)));
        }
        numerador = numerador.multiply(CIEN.add(margen));
        pasos.add(new PasoDePrecio(TipoPaso.MARGEN, margen, mostrado(numerador, CIEN)));
        BigDecimal iva = entrada.alicuota().porcentaje();
        numerador = numerador.multiply(CIEN.add(iva));
        pasos.add(new PasoDePrecio(TipoPaso.IVA, iva, mostrado(numerador, CIEN.multiply(CIEN))));
        if (entrada.multiplo() != null && entrada.multiplo().signum() > 0) {
            pasos.add(new PasoDePrecio(TipoPaso.REDONDEO, null, entrada.multiplo()));
        }
        pasos.add(new PasoDePrecio(TipoPaso.PRECIO_FINAL, null, entrada.precioGuardado()));
    }

    private static List<BigDecimal> presentes(BigDecimal... valores) {
        List<BigDecimal> presentes = new ArrayList<>();
        for (BigDecimal valor : valores) {
            if (valor != null) {
                presentes.add(valor);
            }
        }
        return presentes;
    }

    /** Una sola division, a 4 decimales HALF_UP, solo para mostrar. */
    private static BigDecimal mostrado(BigDecimal numerador, BigDecimal denominador) {
        return numerador.divide(denominador, ESCALA_MOSTRADA, RoundingMode.HALF_UP);
    }
}
