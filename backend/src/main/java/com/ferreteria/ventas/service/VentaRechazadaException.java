package com.ferreteria.ventas.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * La venta no se hizo por algo del ticket que el cajero puede corregir (R-008, R-009, R-010, R-011). Lleva el
 * motivo, su estado HTTP, el mensaje para el usuario y los articulos involucrados. Ningun rechazo guarda
 * nada ni consume la clave de idempotencia.
 */
public class VentaRechazadaException extends RuntimeException {

    public enum Motivo {
        /** Inexistente o de otra ferreteria: indistinguibles a proposito. */
        ARTICULO_INEXISTENTE(404),
        ARTICULO_DADO_DE_BAJA(422),
        /** Precio final $0: no se vende gratis por un costo sin cargar. */
        ARTICULO_SIN_PRECIO(422),
        PRECIOS_CAMBIADOS(409),
        /** La misma clave ya registro otra venta, con otros datos. */
        CLAVE_CON_OTRO_CONTENIDO(422);

        private final int estadoHttp;

        Motivo(int estadoHttp) {
            this.estadoHttp = estadoHttp;
        }

        public int estadoHttp() {
            return estadoHttp;
        }
    }

    /**
     * Un articulo del rechazo. Segun el motivo vienen unos campos u otros y el resto es null: inexistente
     * solo trae el id que mando el cliente; baja, id y descripcion; precios, todos.
     */
    public record Articulo(Long articuloId, String descripcion, BigDecimal precioMostrado, BigDecimal precioActual,
            BigDecimal totalRenglon) {

        public static Articulo soloId(Long articuloId) {
            return new Articulo(articuloId, null, null, null, null);
        }

        public static Articulo conDescripcion(Long articuloId, String descripcion) {
            return new Articulo(articuloId, descripcion, null, null, null);
        }
    }

    private final Motivo motivo;
    private final List<Articulo> articulos;
    private final BigDecimal total;

    public VentaRechazadaException(Motivo motivo, String mensaje, List<Articulo> articulos, BigDecimal total) {
        super(mensaje);
        this.motivo = motivo;
        this.articulos = List.copyOf(articulos);
        this.total = total;
    }

    public VentaRechazadaException(Motivo motivo, String mensaje, List<Articulo> articulos) {
        this(motivo, mensaje, articulos, null);
    }

    public Motivo motivo() {
        return motivo;
    }

    public List<Articulo> articulos() {
        return articulos;
    }

    /** El total actual recalculado; solo en PRECIOS_CAMBIADOS, null en los demas. */
    public BigDecimal total() {
        return total;
    }
}
