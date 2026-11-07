package com.ferreteria.catalogo.model;

import com.ferreteria.catalogo.model.VariacionFuerte.Sujeto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Planifica una actualizacion masiva de precios (CU-05) sin tocar nada. Dominio puro y determinista:
 * el mismo input da siempre el mismo plan, y la verificacion de deriva del aplicar depende de eso. Los
 * articulos se procesan por id ascendente sin importar el orden en que llegan.
 *
 * <p>El precio nuevo se calcula con {@link PrecioCalculator}, igual que {@link Articulo#ajustarCosto} y
 * {@link Articulo#fijarMargen}; el servicio verifica que coincidan al aplicar. La variacion fuerte se
 * mide sobre el costo en los dos modos de costo y sobre el precio final en {@code MARGEN} y {@code CAMBIAR_RUBRO}.
 */
public final class PlanificadorMasivo {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final String MOTIVO_COSTO_CERO = "El costo nuevo da 0. Revisá el porcentaje.";
    private static final String MOTIVO_COSTO_CERO_O_MENOS = "El costo nuevo da 0 o menos. Revisá el monto.";
    private static final String MOTIVO_DEMASIADO_GRANDE = "El costo o el precio nuevo es demasiado grande.";
    private static final String MOTIVO_AJUSTES_EN_CERO = "Con los ajustes del precio final, el precio queda en 0 o menos.";
    private static final String MOTIVO_PRECIO_CERO_O_MENOS_PORCENTAJE = "El precio nuevo da 0 o menos. Revisá el porcentaje.";
    private static final String MOTIVO_PRECIO_CERO_O_MENOS_MONTO = "El precio nuevo da 0 o menos. Revisá el monto.";

    private PlanificadorMasivo() {
    }

    public static PlanMasivo planificar(List<ArticuloVigente> articulos, FiltroArticulos filtro,
                                        OperacionMasiva operacion, ContextosDePrecio contextos) {
        List<ArticuloVigente> ordenados = articulos.stream()
            .sorted(Comparator.comparing(ArticuloVigente::id))
            .toList();

        HuellaMasiva huella = new HuellaMasiva(operacion, filtro);
        List<CambioMasivo> cambios = new ArrayList<>();
        List<ErrorMasivo> errores = new ArrayList<>();
        int suben = 0;
        int bajan = 0;
        int sinCambioDePrecio = 0;
        int yaEnElDestino = 0;
        int pasanAMargenPropio = 0;
        int conAdvertencia = 0;
        int enPesos = 0;
        int enDolares = 0;
        for (ArticuloVigente articulo : ordenados) {
            if (articulo.moneda() == Moneda.USD) {
                enDolares++;
            } else {
                enPesos++;
            }
            ContextoPrecio contexto = contextos.de(articulo.rubroId());
            Fila fila = operacion.modo() == ModoMasivo.CAMBIAR_RUBRO
                ? evaluarCambioDeRubro(articulo, operacion.rubroDestinoId(), contexto,
                    contextos.de(operacion.rubroDestinoId()))
                : evaluar(articulo, operacion, contexto);
            if (fila.motivoDeError() != null) {
                errores.add(new ErrorMasivo(
                    articulo.id(), articulo.codigoInterno(), articulo.descripcion(), fila.motivoDeError()));
                huella.agregarError(articulo);
                continue;
            }
            huella.agregar(articulo, fila.costoNuevo(), fila.margenPropioNuevo(), fila.precioNuevo());

            int comparacion = fila.precioNuevo().compareTo(articulo.precioFinal());
            if (comparacion > 0) {
                suben++;
            } else if (comparacion < 0) {
                bajan++;
            } else {
                sinCambioDePrecio++;
            }
            if (operacion.modo() == ModoMasivo.CAMBIAR_RUBRO && operacion.rubroDestinoId().equals(articulo.rubroId())) {
                yaEnElDestino++;
            }
            if (operacion.modo() == ModoMasivo.MARGEN && articulo.margenPorcentaje() == null) {
                pasanAMargenPropio++;
            }
            if (fila.advertencia() != null) {
                conAdvertencia++;
            }
            if (fila.modificado()) {
                boolean conRecargos = !articulo.recargosCosto().estaVacia();
                cambios.add(new CambioMasivo(articulo.id(), articulo.codigoInterno(), articulo.descripcion(),
                    articulo.costo(), fila.costoNuevo(), fila.margenAnterior(), fila.margenNuevo(),
                    articulo.precioFinal(), fila.precioNuevo(), fila.margenPropioNuevo() == null, fila.advertencia(),
                    articulo.moneda(), articulo.alicuotaIva().porcentaje(), articulo.rubroId(),
                    operacion.rubroDespues(articulo.rubroId()),
                    conRecargos ? articulo.recargosCosto().porcentajeTotal() : null,
                    conRecargos ? articulo.costoReal(articulo.costo()) : null,
                    conRecargos ? articulo.costoReal(fila.costoNuevo()) : null,
                    fila.precioSinAjustes()));
            }
        }

        ConteosMasivos conteos = new ConteosMasivos(ordenados.size(), suben, bajan, sinCambioDePrecio,
            errores.size(), pasanAMargenPropio, conAdvertencia, cambios.size(), yaEnElDestino);
        return new PlanMasivo(conteos, cambios, errores, huella.calcular(), enPesos, enDolares,
            advertenciaMezcla(operacion, enPesos, enDolares));
    }

    /** El monto se suma tal cual en cada moneda: con las dos presentes se avisa, sin bloquear. */
    private static String advertenciaMezcla(OperacionMasiva operacion, int enPesos, int enDolares) {
        if (operacion.modo() != ModoMasivo.COSTO_MONTO || enPesos == 0 || enDolares == 0) {
            return null;
        }
        return "La selección mezcla artículos en pesos y en dólares. El monto de "
            + ImporteArgentino.montoCompacto(operacion.valor()) + " se suma tal cual a cada costo: "
            + ImporteArgentino.formatoCompacto(operacion.valor(), Moneda.ARS) + " en los de pesos y "
            + ImporteArgentino.formatoCompacto(operacion.valor(), Moneda.USD) + " en los de dólares.";
    }

    /**
     * Lo que la operacion hace con un articulo: o un error, o los valores nuevos. {@code precioSinAjustes} es el precio
     * calculado nuevo antes de los ajustes, o null si no tiene ajustes o queda con precio manual.
     */
    private record Fila(String motivoDeError, BigDecimal costoNuevo, BigDecimal margenAnterior,
                        BigDecimal margenNuevo, BigDecimal margenPropioNuevo, BigDecimal precioNuevo,
                        String advertencia, boolean modificado, BigDecimal precioSinAjustes) {

        static Fila conError(String motivo) {
            return new Fila(motivo, null, null, null, null, null, null, false, null);
        }
    }

    /** El precio calculado si los ajustes cuentan (sin precio manual y con ajustes); si no, null. */
    private static BigDecimal sinAjustes(ArticuloVigente articulo, BigDecimal calculado) {
        return articulo.precioManual() || articulo.ajustesPrecio().estaVacia() ? null : calculado;
    }

    private static Fila evaluar(ArticuloVigente articulo, OperacionMasiva operacion, ContextoPrecio contexto) {
        BigDecimal margenAnterior = PrecioCalculator.margenEfectivo(
            articulo.margenPorcentaje(), contexto.margenRubro(), contexto.margenDefecto());
        if (operacion.modo().esDePrecioFinal()) {
            return evaluarPrecioFinal(articulo, operacion, contexto, margenAnterior);
        }
        boolean enMargen = operacion.modo() == ModoMasivo.MARGEN;

        BigDecimal costoNuevo = costoNuevo(articulo.costo(), operacion);
        // Antes del precio: PrecioCalculator lanza con un costo negativo, y un monto puede dejarlo asi.
        if (operacion.modo() == ModoMasivo.COSTO_PORCENTAJE && costoNuevo.signum() == 0) {
            return Fila.conError(MOTIVO_COSTO_CERO);
        }
        if (operacion.modo() == ModoMasivo.COSTO_MONTO && costoNuevo.signum() <= 0) {
            return Fila.conError(MOTIVO_COSTO_CERO_O_MENOS);
        }
        BigDecimal margenPropioNuevo = enMargen ? operacion.valor() : articulo.margenPorcentaje();
        BigDecimal margenNuevo = enMargen ? operacion.valor() : margenAnterior;
        // Misma regla que Articulo: con precio manual queda el de hoy; si no, el calculado mas los ajustes (CU-14).
        BigDecimal precioNuevo = articulo.precioCon(
            costoNuevo, margenNuevo, articulo.alicuotaIva(), contexto.redondeoMultiplo(),
            contexto.cotizacionPara(articulo.moneda()));
        if (!TopeDeImporte.entra(costoNuevo) || !TopeDeImporte.entra(precioNuevo)) {
            return Fila.conError(MOTIVO_DEMASIADO_GRANDE);
        }
        // Misma regla que Articulo: con costo 0 el calculado es 0 y un ajuste que lo deja en 0 no es error.
        BigDecimal calculado = PrecioCalculator.precioFinal(articulo.costoReal(costoNuevo), margenNuevo, articulo.alicuotaIva(),
            contexto.redondeoMultiplo(), contexto.cotizacionPara(articulo.moneda()));
        if (!articulo.precioManual() && AjustesDePrecio.dejanElPrecioEnCeroOMenos(calculado, precioNuevo)) {
            return Fila.conError(MOTIVO_AJUSTES_EN_CERO);
        }

        Optional<String> advertencia = enMargen
            ? VariacionFuerte.mensaje(Sujeto.PRECIO, articulo.precioFinal(), precioNuevo)
            : VariacionFuerte.mensaje(Sujeto.COSTO, articulo.costo(), costoNuevo);
        boolean modificado = distintos(articulo.costo(), costoNuevo)
            || distintos(articulo.precioFinal(), precioNuevo)
            || distintos(articulo.margenPorcentaje(), margenPropioNuevo);
        return new Fila(null, costoNuevo, margenAnterior, margenNuevo, margenPropioNuevo, precioNuevo,
            MargenNegativo.junto(advertencia, articulo.margenNegativo(
                costoNuevo, articulo.alicuotaIva(), contexto.cotizacionPara(articulo.moneda()))),
            modificado, sinAjustes(articulo, calculado));
    }

    /**
     * Precio final en % o en $ (CU-14 punto 4): sobre el precio de hoy (manual o no), hacia arriba al multiplo como todo
     * precio, sin ajustes (queda manual). El costo y el margen no cambian. La variacion fuerte se mide sobre el precio y
     * el margen negativo se avisa siempre, porque el articulo queda con precio manual.
     */
    private static Fila evaluarPrecioFinal(ArticuloVigente articulo, OperacionMasiva operacion, ContextoPrecio contexto,
                                           BigDecimal margenAnterior) {
        BigDecimal bruto = operacion.modo() == ModoMasivo.PRECIO_PORCENTAJE
            ? articulo.precioFinal().multiply(CIEN.add(operacion.valor())).movePointLeft(2)
            : articulo.precioFinal().add(operacion.valor());
        if (bruto.signum() <= 0) {
            return Fila.conError(operacion.modo() == ModoMasivo.PRECIO_PORCENTAJE
                ? MOTIVO_PRECIO_CERO_O_MENOS_PORCENTAJE : MOTIVO_PRECIO_CERO_O_MENOS_MONTO);
        }
        BigDecimal precioNuevo = PrecioCalculator.redondear(bruto, contexto.redondeoMultiplo());
        if (!TopeDeImporte.entra(precioNuevo)) {
            return Fila.conError(MOTIVO_DEMASIADO_GRANDE);
        }
        String advertencia = MargenNegativo.junto(
            VariacionFuerte.mensaje(Sujeto.PRECIO, articulo.precioFinal(), precioNuevo),
            MargenNegativo.mensaje(articulo.costo(), articulo.recargosCosto(),
                contexto.cotizacionPara(articulo.moneda()), articulo.alicuotaIva(), precioNuevo));
        boolean modificado = distintos(articulo.precioFinal(), precioNuevo) || !articulo.precioManual();
        return new Fila(null, articulo.costo(), margenAnterior, margenAnterior, articulo.margenPorcentaje(),
            precioNuevo, advertencia, modificado, null);
    }

    /**
     * Cambiar el rubro (CU-05): el articulo pasa al destino y su precio sale de la regla de siempre con el contexto del
     * destino (margen propio, si no el del destino, si no el de la ferreteria; con precio manual queda el de hoy). El
     * costo y el margen propio no cambian. Uno que ya esta en el destino queda sin cambio y no se escribe. La variacion
     * fuerte se mide sobre el precio, como en {@code MARGEN}.
     */
    private static Fila evaluarCambioDeRubro(ArticuloVigente articulo, Long destinoId, ContextoPrecio actual,
                                             ContextoPrecio destino) {
        BigDecimal margenAnterior = PrecioCalculator.margenEfectivo(
            articulo.margenPorcentaje(), actual.margenRubro(), actual.margenDefecto());
        if (destinoId.equals(articulo.rubroId())) {
            return new Fila(null, articulo.costo(), margenAnterior, margenAnterior, articulo.margenPorcentaje(),
                articulo.precioFinal(), null, false, null);
        }
        BigDecimal margenNuevo = PrecioCalculator.margenEfectivo(
            articulo.margenPorcentaje(), destino.margenRubro(), destino.margenDefecto());
        BigDecimal cotizacion = destino.cotizacionPara(articulo.moneda());
        BigDecimal precioNuevo = articulo.precioCon(
            articulo.costo(), margenNuevo, articulo.alicuotaIva(), destino.redondeoMultiplo(), cotizacion);
        if (!TopeDeImporte.entra(precioNuevo)) {
            return Fila.conError(MOTIVO_DEMASIADO_GRANDE);
        }
        // Misma regla que Articulo: con costo 0 el calculado es 0 y un ajuste que lo deja en 0 no es error.
        BigDecimal calculado = PrecioCalculator.precioFinal(articulo.costoReal(articulo.costo()), margenNuevo,
            articulo.alicuotaIva(), destino.redondeoMultiplo(), cotizacion);
        if (!articulo.precioManual() && AjustesDePrecio.dejanElPrecioEnCeroOMenos(calculado, precioNuevo)) {
            return Fila.conError(MOTIVO_AJUSTES_EN_CERO);
        }
        String advertencia = MargenNegativo.junto(
            VariacionFuerte.mensaje(Sujeto.PRECIO, articulo.precioFinal(), precioNuevo),
            articulo.margenNegativo(articulo.costo(), articulo.alicuotaIva(), cotizacion));
        return new Fila(null, articulo.costo(), margenAnterior, margenNuevo, articulo.margenPorcentaje(), precioNuevo,
            advertencia, true, sinAjustes(articulo, calculado));
    }

    /** El costo despues de la operacion: sin cambios en MARGEN, porcentual o con un monto sumado en los otros dos. */
    private static BigDecimal costoNuevo(BigDecimal costo, OperacionMasiva operacion) {
        return switch (operacion.modo()) {
            case MARGEN, PRECIO_PORCENTAJE, PRECIO_MONTO, CAMBIAR_RUBRO -> costo;
            case COSTO_PORCENTAJE -> costoConPorcentaje(costo, operacion.valor());
            case COSTO_MONTO -> costo.add(operacion.valor());
        };
    }

    /** costo x (100 + valor) / 100 con un solo redondeo HALF_UP a 2 decimales. */
    private static BigDecimal costoConPorcentaje(BigDecimal costo, BigDecimal porcentaje) {
        return costo.multiply(CIEN.add(porcentaje)).divide(CIEN, 2, RoundingMode.HALF_UP);
    }

    /** Distintos por valor (compareTo, no por escala); null solo es igual a null. */
    private static boolean distintos(BigDecimal anterior, BigDecimal nuevo) {
        if (anterior == null || nuevo == null) {
            return anterior != nuevo;
        }
        return anterior.compareTo(nuevo) != 0;
    }
}
