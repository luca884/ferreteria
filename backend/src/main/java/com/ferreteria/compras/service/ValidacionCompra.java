package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.compras.service.RegistrarCompra.RenglonCompra;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Las reglas de la compra (CU-13) que el cuerpo del pedido no puede validar solo. Dominio puro: la fecha de hoy y los
 * articulos se los pasa el que llama. Precedencia: forma (renglon repetido, fecha) → 404 del articulo → 400 de los
 * articulos (baja, cantidad entera, moneda).
 */
public final class ValidacionCompra {

    private ValidacionCompra() {
    }

    /** Lo que se valida sin leer la base: un articulo no se repite y la fecha no es posterior a hoy. */
    public static void forma(RegistrarCompra pedido, LocalDate hoy) {
        Map<String, String> errores = new LinkedHashMap<>();
        Set<Long> vistos = new HashSet<>();
        List<RenglonCompra> renglones = pedido.renglones();
        for (int i = 0; i < renglones.size(); i++) {
            if (!vistos.add(renglones.get(i).articuloId())) {
                errores.putIfAbsent("renglones[" + i + "].articuloId", "El artículo está repetido en la compra.");
            }
        }
        if (pedido.fecha().isAfter(hoy)) {
            errores.put("fecha", "La fecha no puede ser posterior a hoy.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    /**
     * Los articulos de la compra contra lo que planeo catalogo: el que falta es de otra ferreteria o no existe (404); el
     * dado de baja no se compra; uno por unidad no lleva decimales; y todos son de la misma moneda.
     */
    public static void articulos(RegistrarCompra pedido, List<CostoDeCompra> costos) {
        Map<Long, CostoDeCompra> porId = new HashMap<>();
        costos.forEach(c -> porId.put(c.articuloId(), c));
        List<RenglonCompra> renglones = pedido.renglones();
        if (renglones.stream().anyMatch(r -> !porId.containsKey(r.articuloId()))) {
            throw new ResourceNotFoundException("Artículo no encontrado");
        }

        Map<String, String> errores = new LinkedHashMap<>();
        for (int i = 0; i < renglones.size(); i++) {
            RenglonCompra renglon = renglones.get(i);
            CostoDeCompra articulo = porId.get(renglon.articuloId());
            if (!articulo.activo()) {
                errores.put("renglones[" + i + "].articuloId", articulo.descripcion() + " está dado de baja.");
            } else if ("UNIDAD".equals(articulo.unidad()) && !esEntera(renglon)) {
                errores.put("renglones[" + i + "].cantidad",
                        articulo.descripcion() + " se compra por unidad: la cantidad tiene que ser entera.");
            }
        }
        if (costos.stream().map(CostoDeCompra::moneda).distinct().count() > 1) {
            errores.put("renglones", "Una compra no puede mezclar artículos en pesos y en dólares.");
        }
        if (!errores.isEmpty()) {
            throw new DatosInvalidosException(errores);
        }
    }

    private static boolean esEntera(RenglonCompra renglon) {
        return renglon.cantidad().stripTrailingZeros().scale() <= 0;
    }
}
