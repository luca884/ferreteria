package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ventas.model.CantidadVendible;
import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.model.RenglonVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaRechazadaException.Articulo;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Las reglas del ticket que comparten la venta y el presupuesto: los articulos existen, estan activos y tienen
 * precio, las cantidades y los descuentos son validos, los importes entran en la base y los precios mostrados son los
 * actuales. Cada una corta con su excepcion y sin escribir nada. El orden de precedencia lo fija el que llama.
 */
final class ValidacionTicket {

    static final String MENSAJE_DESCUENTO = "El descuento tiene que estar entre 0 y 99,99 %.";
    static final String MENSAJE_INEXISTENTE = "Hay un artículo del ticket que ya no está disponible";

    private ValidacionTicket() {}

    /** Un id repetido es un error del cliente que no depende de la base: se corta antes de consultar. */
    static void articulosDistintos(List<Renglon> renglones) {
        Set<Long> vistos = new HashSet<>();
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            if (!vistos.add(renglones.get(i).articuloId())) {
                errores.put("renglones[" + i + "].articuloId",
                        "Un artículo no puede repetirse en el ticket: sumá la cantidad en un solo renglón.");
            }
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** Inexistente, de baja, sin precio, cantidad y descuentos: en ese orden de precedencia. */
    static void renglones(List<Renglon> renglones, BigDecimal descuentoGlobal, Map<Long, ArticuloVendible> articulos) {
        queExistan(renglones, articulos);
        queEstenActivos(renglones, articulos);
        queTenganPrecio(renglones, articulos);
        cantidades(renglones, articulos);
        descuentos(renglones, descuentoGlobal);
    }

    /** R-010: inexistente o ajeno, la misma respuesta; solo vuelven los ids que mando el cliente. */
    private static void queExistan(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<Articulo> faltantes = renglones.stream()
                .filter(r -> !articulos.containsKey(r.articuloId()))
                .map(r -> Articulo.soloId(r.articuloId()))
                .toList();
        if (!faltantes.isEmpty()) {
            throw new VentaRechazadaException(Motivo.ARTICULO_INEXISTENTE, MENSAJE_INEXISTENTE, faltantes);
        }
    }

    /** R-009: si alguno esta dado de baja se rechaza TODA la venta. */
    private static void queEstenActivos(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<ArticuloVendible> dadosDeBaja = renglones.stream()
                .map(r -> articulos.get(r.articuloId()))
                .filter(a -> !a.activo())
                .toList();
        if (dadosDeBaja.isEmpty()) {
            return;
        }
        String mensaje = dadosDeBaja.size() == 1
                ? "\"" + dadosDeBaja.get(0).descripcion() + "\" fue dado de baja. Sacalo del ticket para seguir."
                : "Hay artículos dados de baja: "
                        + dadosDeBaja.stream().map(a -> "\"" + a.descripcion() + "\"")
                                .collect(Collectors.joining(", "))
                        + ". Sacalos del ticket para seguir.";
        throw new VentaRechazadaException(Motivo.ARTICULO_DADO_DE_BAJA, mensaje,
                dadosDeBaja.stream().map(a -> Articulo.conDescripcion(a.id(), a.descripcion())).toList());
    }

    /**
     * Un articulo con precio final $0 (costo sin cargar) no se vende: se rechaza TODA la venta, como con los dados
     * de baja. Mira el precio del servidor, no el que mando el cliente.
     */
    private static void queTenganPrecio(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        List<ArticuloVendible> sinPrecio = renglones.stream()
                .map(r -> articulos.get(r.articuloId()))
                .filter(a -> a.precioFinal().signum() <= 0)
                .toList();
        if (sinPrecio.isEmpty()) {
            return;
        }
        String mensaje = sinPrecio.size() == 1
                ? "\"" + sinPrecio.get(0).descripcion() + "\" no tiene precio. Sacalo del ticket y pedile al dueño "
                        + "que le cargue el costo."
                : "Hay artículos sin precio: "
                        + sinPrecio.stream().map(a -> "\"" + a.descripcion() + "\"")
                                .collect(Collectors.joining(", "))
                        + ". Sacalos del ticket y pedile al dueño que les cargue el costo.";
        throw new VentaRechazadaException(Motivo.ARTICULO_SIN_PRECIO, mensaje,
                sinPrecio.stream().map(a -> Articulo.conDescripcion(a.id(), a.descripcion())).toList());
    }

    /** R-007: la unidad la lee el servidor; cada renglon invalido queda con su mensaje. */
    private static void cantidades(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            int indice = i;
            CantidadVendible.validar(articulo.descripcion(), renglon.cantidad(), articulo.admiteDecimales())
                    .ifPresent(mensaje -> errores.put("renglones[" + indice + "].cantidad", mensaje));
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /** GDS-12: cada descuento va de 0 a 99,99 %; el mensaje queda en el campo del renglon o en el global. */
    private static void descuentos(List<Renglon> renglones, BigDecimal descuentoGlobal) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            if (descuentoInvalido(renglones.get(i).descuentoPorcentaje())) {
                errores.put("renglones[" + i + "].descuentoPorcentaje", MENSAJE_DESCUENTO);
            }
        }
        if (descuentoInvalido(descuentoGlobal)) {
            errores.put("descuentoPorcentaje", MENSAJE_DESCUENTO);
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    private static boolean descuentoInvalido(BigDecimal porcentaje) {
        return porcentaje.signum() < 0 || porcentaje.compareTo(RenglonVenta.DESCUENTO_MAXIMO) > 0;
    }

    /**
     * Tope de DECIMAL(15,2): cada dato entra solo (lo frena el request) pero cantidad x precio del
     * servidor, o la suma de los renglones, puede no entrar. Se corta con un 400 antes de escribir.
     * {@code totalFinal} es el total que se cobra (con el recargo, si lo hay); se pide recien si hace falta.
     */
    static void importes(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos, BigDecimal descuentoGlobal,
            Supplier<BigDecimal> totalFinal) {
        Map<String, String> errores = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            RenglonVenta calculado = calcular(renglon, articulo, descuentoGlobal);
            if (calculado.bruto().compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0) {
                errores.put("renglones[" + i + "].cantidad", "El importe de \"" + articulo.descripcion()
                        + "\" es demasiado grande: el máximo por renglón es $ 9.999.999.999.999,99.");
            }
            total = total.add(calculado.bruto());
        }
        if (errores.isEmpty() && (total.compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0
                || totalFinal.get().compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0)) {
            errores.put("totalMostrado", "El total de la venta es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /**
     * R-008: recalcula con el precio actual. Si algun precio mostrado difiere, o el total mostrado difiere del
     * actual ({@code totalActual}, con el recargo si lo hay), 409 con los renglones que cambiaron y el total actual.
     */
    static void precios(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos, BigDecimal descuentoGlobal,
            BigDecimal totalMostrado, BigDecimal totalActual) {
        List<RenglonVenta> actuales = calcular(renglones, articulos, descuentoGlobal);
        List<Articulo> cambiados = new ArrayList<>();
        for (int i = 0; i < renglones.size(); i++) {
            Renglon renglon = renglones.get(i);
            ArticuloVendible articulo = articulos.get(renglon.articuloId());
            RenglonVenta actual = actuales.get(i);
            if (renglon.precioUnitario().compareTo(articulo.precioFinal()) != 0) {
                cambiados.add(new Articulo(articulo.id(), articulo.descripcion(), renglon.precioUnitario(),
                        articulo.precioFinal(), actual.total()));
            }
        }
        if (cambiados.isEmpty() && totalMostrado.compareTo(totalActual) == 0) {
            return;
        }
        String mensaje = switch (cambiados.size()) {
            case 0 -> "El total cambió. Revisá el total y confirmá de nuevo.";
            case 1 -> "Cambió el precio de 1 artículo. Revisá el total y confirmá de nuevo.";
            default -> "Cambiaron los precios de " + cambiados.size()
                    + " artículos. Revisá el total y confirmá de nuevo.";
        };
        throw new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, mensaje, cambiados, totalActual);
    }

    /** Los renglones calculados con el precio del servidor y los descuentos que mando el cajero (GDS-12). */
    static List<RenglonVenta> calcular(List<Renglon> renglones, Map<Long, ArticuloVendible> articulos,
            BigDecimal descuentoGlobal) {
        List<RenglonVenta> calculados = new ArrayList<>();
        for (Renglon renglon : renglones) {
            calculados.add(calcular(renglon, articulos.get(renglon.articuloId()), descuentoGlobal));
        }
        return calculados;
    }

    private static RenglonVenta calcular(Renglon renglon, ArticuloVendible articulo, BigDecimal descuentoGlobal) {
        return RenglonVenta.de(articulo.precioFinal(), renglon.cantidad(), articulo.porcentajeIva(),
                renglon.descuentoPorcentaje(), descuentoGlobal);
    }
}
