package com.ferreteria.compras.web;

import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.BorradorDeFactura;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * El borrador de compra que sale de leer la factura en PDF (CU-13): no se guardo nada. {@code proveedorId} es null si
 * ningun CUIT leido es de un proveedor activo; {@code articuloId} es null en el renglon cuyo codigo no tiene articulo.
 * {@code control} es CUADRA (la suma de la factura coincide con la leida) o SIN_CONTROL.
 */
public record BorradorDeFacturaResponse(
        Long proveedorId,
        String proveedorNombre,
        List<String> cuits,
        TipoCompra tipo,
        Integer puntoVenta,
        Long numero,
        LocalDate fecha,
        List<Recargo> recargos,
        List<Renglon> renglones,
        BigDecimal sumaNetos,
        String control,
        boolean cantidadNoEntera) {

    public record Recargo(String nombre, BigDecimal porcentaje, BigDecimal importe) {
    }

    public record Renglon(
            String codigo,
            String descripcion,
            BigDecimal cantidad,
            BigDecimal costoUnitario,
            BigDecimal neto,
            Long articuloId,
            String articuloDescripcion,
            String articuloCodigoInterno,
            String articuloMoneda,
            boolean cantidadNoEntera) {
    }

    public static BorradorDeFacturaResponse de(BorradorDeFactura b) {
        return new BorradorDeFacturaResponse(b.proveedorId(), b.proveedorNombre(), b.cuits(), b.tipo(),
                b.puntoVenta(), b.numero(), b.fecha(),
                b.recargos().stream().map(r -> new Recargo(r.nombre(), r.porcentaje(), r.importe())).toList(),
                b.renglones().stream().map(r -> new Renglon(r.codigo(), r.descripcion(), r.cantidad(),
                        r.costoUnitario(), r.neto(), r.articuloId(), r.articuloDescripcion(),
                        r.articuloCodigoInterno(), r.articuloMoneda(), r.cantidadNoEntera()))
                        .toList(),
                b.sumaNetos(), b.control().name(), b.cantidadNoEntera());
    }
}
