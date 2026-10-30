package com.ferreteria.ventas.web;

/**
 * Los cuerpos de las acciones del Dueño sobre la factura de una venta (cu-07-contingencias C2 y C3) y la fila de «llegó el
 * CAE» (C7). Sin validaciones de Bean Validation: el motivo lo valida {@code facturacion} (400 en {@code motivo}), así el
 * Vendedor recibe 403 sin que se mire el cuerpo.
 */
public final class AccionesFacturaRequests {

    private AccionesFacturaRequests() {
    }

    /** «Reintentar»: {@code fechaDeHoy} es la confirmación del Dueño de que la factura salga con la fecha de hoy. */
    public record Reintentar(Boolean fechaDeHoy) {

        boolean conFechaDeHoy() {
            return Boolean.TRUE.equals(fechaDeHoy);
        }
    }

    /** «Dejar sin factura»: el motivo, obligatorio, de hasta 200 caracteres. */
    public record SinFactura(String motivo) {}

    /**
     * Anular la venta (CU-08 punto 6): el motivo solo vale en la anulación interna (obligatorio, hasta 200 caracteres); en
     * la nota de crédito se ignora.
     */
    public record Anular(String motivo) {}

    /** Una venta de la caja abierta a la que le llegó el CAE (C7), con el texto de su factura. */
    public record ProvisoriaAutorizada(long ventaId, long numero, String factura) {}
}
