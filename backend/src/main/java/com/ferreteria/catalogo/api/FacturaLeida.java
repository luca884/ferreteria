package com.ferreteria.catalogo.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que se lee de una factura de proveedor en PDF (CU-13), sin resolver articulos ni proveedor: el codigo y el CUIT
 * son los que trae la factura. Solo tipos del JDK. {@code letra} es A, B, C o null; cualquier dato del encabezado que
 * no aparece en la factura es null (los CUIT, una lista vacia). Los importes van a 2 decimales.
 *
 * <p>{@code control} dice si la lectura cuadra con la aritmetica de la propia factura: {@link Control#CUADRA} si un total
 * impreso despues del ultimo renglon es la suma de los netos mas los recargos; si no, {@link Control#SIN_CONTROL}.
 */
public record FacturaLeida(
        Encabezado encabezado,
        List<Renglon> renglones,
        List<Recargo> recargos,
        BigDecimal sumaNetos,
        Control control) {

    public enum Control { CUADRA, SIN_CONTROL }

    /** {@code cuits} son 11 digitos sin guiones, validos por su digito verificador, en el orden en que aparecen. */
    public record Encabezado(String letra, Integer puntoVenta, Long numero, LocalDate fecha, List<String> cuits) {
    }

    /** {@code costoUnitario} ya trae la bonificacion del renglon; {@code neto} es el importe impreso. */
    public record Renglon(
            String codigo,
            BigDecimal cantidad,
            String descripcion,
            BigDecimal costoUnitario,
            BigDecimal neto) {
    }

    /** Un cargo sobre el total (por ejemplo «EMBALAJE Y CARGA 4.44 %»): su importe es su porcentaje de la suma de netos. */
    public record Recargo(String nombre, BigDecimal porcentaje, BigDecimal importe) {
    }
}
