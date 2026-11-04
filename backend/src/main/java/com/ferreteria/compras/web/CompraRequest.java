package com.ferreteria.compras.web;

import com.ferreteria.catalogo.api.RecargoDeCompra;
import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.RegistrarCompra;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * La compra que carga el Dueno (CU-13), para la vista previa y para registrarla. La clave de idempotencia solo la exige
 * registrar. No tiene ferreteria ni usuario: salen del JWT. Aca solo se valida la forma; lo que depende del proveedor y de
 * los articulos lo valida el servicio. Los {@code recargos} son opcionales (sin ellos, la compra los borra de sus
 * articulos).
 */
public record CompraRequest(
        @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "La clave de la compra no es válida.")
        String clave,

        @NotNull(message = "Elegí el proveedor.")
        Long proveedorId,

        @NotNull(message = "Elegí el tipo de comprobante.")
        TipoCompra tipo,

        @NotNull(message = "Falta el punto de venta.")
        @Min(value = 1, message = "El punto de venta va de 1 a 99999.")
        @Max(value = 99999, message = "El punto de venta va de 1 a 99999.")
        Integer puntoVenta,

        @NotNull(message = "Falta el número.")
        @Min(value = 1, message = "El número va de 1 a 99999999.")
        @Max(value = 99999999, message = "El número va de 1 a 99999999.")
        Long numero,

        @NotNull(message = "Falta la fecha.")
        LocalDate fecha,

        @NotEmpty(message = "La compra no tiene artículos.")
        @Size(max = 500, message = "La compra admite hasta 500 renglones.")
        List<@NotNull(message = "Hay un renglón vacío en la compra.") @Valid RenglonRequest> renglones,

        List<@NotNull(message = "Hay un recargo vacío en la compra.") RecargoRequest> recargos) {

    /**
     * Un recargo sobre el costo (por ejemplo «Embalaje y carga» 4,44). Sus reglas (nombre, rango, 2 decimales, hasta 5) las
     * valida catalogo, las mismas que las de los recargos del articulo.
     */
    public record RecargoRequest(String nombre, BigDecimal porcentaje) {
    }

    public record RenglonRequest(
            @NotNull(message = "Elegí el artículo.")
            Long articuloId,

            @NotNull(message = "Falta la cantidad.")
            @DecimalMin(value = "0", inclusive = false, message = "La cantidad tiene que ser mayor a 0.")
            @Digits(integer = 12, fraction = 3, message = "La cantidad admite hasta 3 decimales.")
            BigDecimal cantidad,

            @NotNull(message = "Falta el costo.")
            @DecimalMin(value = "0", inclusive = false, message = "El costo tiene que ser mayor a 0.")
            @Digits(integer = 13, fraction = 2, message = "El costo admite hasta 2 decimales.")
            BigDecimal costoUnitario) {
    }

    public RegistrarCompra aPedido() {
        return new RegistrarCompra(clave, proveedorId, tipo, puntoVenta, numero, fecha, renglones.stream()
                .map(r -> new RegistrarCompra.RenglonCompra(r.articuloId(), r.cantidad(), r.costoUnitario()))
                .toList(),
                recargos == null ? List.of()
                        : recargos.stream().map(r -> new RecargoDeCompra(r.nombre(), r.porcentaje())).toList());
    }
}
