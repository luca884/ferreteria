package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.Unidad;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Alta y edicion de un articulo. No tiene stock actual ni precio final: el primero lo mueve CU-09
 * y el segundo lo calcula el backend, asi que si el cliente los manda se ignoran.
 */
public record ArticuloRequest(
        @NotBlank(message = "El código interno es obligatorio")
        @Size(max = 30, message = "El código interno no puede superar 30 caracteres")
        String codigoInterno,

        @Size(max = 30, message = "El código de barras no puede superar 30 caracteres")
        String codigoBarras,

        @Size(max = 40, message = "El código del proveedor no puede superar 40 caracteres")
        String codigoProveedor,

        @NotBlank(message = "La descripción es obligatoria")
        @Size(max = 200, message = "La descripción no puede superar 200 caracteres")
        String descripcion,

        Long rubroId,

        Long proveedorId,

        @NotNull(message = "El costo es obligatorio")
        @DecimalMin(value = "0.00", inclusive = false, message = "El costo tiene que ser mayor que cero.")
        @Digits(integer = 13, fraction = 2, message = "El costo admite hasta 2 decimales")
        BigDecimal costo,

        /** null significa que hereda el margen del rubro o el de la ferreteria. */
        @DecimalMin(value = "0.00", message = "El margen no puede ser negativo")
        @DecimalMax(value = "1000.00", message = "El margen no puede superar 1000 %")
        @Digits(integer = 4, fraction = 2, message = "El margen admite hasta 2 decimales")
        BigDecimal margenPorcentaje,

        /** null significa IVA 21 %. */
        AlicuotaIva alicuotaIva,

        @NotNull(message = "La unidad es obligatoria")
        Unidad unidad,

        /** null significa 0. */
        @DecimalMin(value = "0", message = "El stock mínimo no puede ser negativo")
        @Digits(integer = 12, fraction = 3, message = "El stock mínimo admite hasta 3 decimales")
        BigDecimal stockMinimo,

        @Size(max = 60, message = "La ubicación no puede superar 60 caracteres")
        String ubicacion,

        /** null significa activo en el alta y "conservar el estado actual" en la edicion. */
        Boolean activo,

        /**
         * Moneda del costo: ARS o USD. null significa ARS en el alta y "conservar la moneda actual" en la edicion. Un
         * valor que no es de la enumeracion da 400 (lo resuelve el manejador global de errores). Cambiar la moneda NO
         * convierte el costo: se toma tal cual en la moneda nueva.
         */
        Moneda moneda,

        /**
         * Version del articulo que el cliente leyo (bloqueo optimista). Solo la usa el PUT, donde es OBLIGATORIA
         * desde el lote 6 (decision 48): sin ella es un 400 y, si no coincide con la vigente, un 409. El alta la
         * ignora. Un valor que no es un entero da 400 por el camino generico del cuerpo ilegible.
         */
        Long version) {

    /** Pedido sin version: sirve para el alta, que no la usa; un PUT sin ella da 400. */
    public ArticuloRequest(
            String codigoInterno, String codigoBarras, String codigoProveedor, String descripcion, Long rubroId,
            Long proveedorId, BigDecimal costo, BigDecimal margenPorcentaje, AlicuotaIva alicuotaIva, Unidad unidad,
            BigDecimal stockMinimo, String ubicacion, Boolean activo, Moneda moneda) {
        this(codigoInterno, codigoBarras, codigoProveedor, descripcion, rubroId, proveedorId, costo, margenPorcentaje,
                alicuotaIva, unidad, stockMinimo, ubicacion, activo, moneda, null);
    }

    /** Pedido sin moneda: en el alta el articulo es en pesos; en la edicion conserva la que tenia. */
    public ArticuloRequest(
            String codigoInterno, String codigoBarras, String codigoProveedor, String descripcion, Long rubroId,
            Long proveedorId, BigDecimal costo, BigDecimal margenPorcentaje, AlicuotaIva alicuotaIva, Unidad unidad,
            BigDecimal stockMinimo, String ubicacion, Boolean activo) {
        this(codigoInterno, codigoBarras, codigoProveedor, descripcion, rubroId, proveedorId, costo, margenPorcentaje,
                alicuotaIva, unidad, stockMinimo, ubicacion, activo, null, null);
    }

    /**
     * Para el alta: sin espacios sobrantes, textos en blanco como null y los defectos aplicados (activo = true,
     * moneda = ARS).
     */
    public DatosArticulo toDatos() {
        return toDatos(true, Moneda.ARS);
    }

    /**
     * Para la edicion: igual que {@link #toDatos()}, pero si el pedido no trae activo ni moneda se conservan los
     * actuales del articulo (un PUT sin activo no reactiva uno dado de baja y un PUT sin moneda no pasa a pesos uno
     * que esta en dolares).
     */
    public DatosArticulo toDatos(boolean activoActual, Moneda monedaActual) {
        return new DatosArticulo(
                codigoInterno == null ? null : codigoInterno.trim(),
                blancoANull(codigoBarras),
                blancoANull(codigoProveedor),
                descripcion == null ? null : descripcion.trim(),
                rubroId,
                proveedorId,
                costo,
                margenPorcentaje,
                alicuotaIva == null ? AlicuotaIva.IVA_21 : alicuotaIva,
                unidad,
                stockMinimo == null ? BigDecimal.ZERO : stockMinimo,
                blancoANull(ubicacion),
                activo == null ? activoActual : activo,
                moneda == null ? monedaActual : moneda);
    }

    private static String blancoANull(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim();
    }
}
