package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.ArticulosParaCompra;
import com.ferreteria.catalogo.api.ArticulosParaCompra.ArticuloPorCodigo;
import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.LectorDeFacturas;
import com.ferreteria.catalogo.api.ProveedoresParaCompra;
import com.ferreteria.catalogo.api.ProveedoresParaCompra.ProveedorDeCompra;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.compras.model.TipoCompra;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Lee la factura del proveedor en PDF y la resuelve contra la ferreteria del request (CU-13): el proveedor por el
 * primer CUIT del encabezado que sea de un proveedor activo, y cada renglon por el codigo del proveedor. No guarda nada y
 * deliberadamente SIN transaccion: la lectura espera su turno y puede tardar hasta 30 s; cada consulta abre la suya.
 */
@Service
public class LecturaDeFactura {

    static final String SIN_RENGLONES = "No se encontraron renglones en la factura. Cargá la compra a mano.";

    private final LectorDeFacturas lector;
    private final ProveedoresParaCompra proveedores;
    private final ArticulosParaCompra articulos;

    public LecturaDeFactura(LectorDeFacturas lector, ProveedoresParaCompra proveedores,
                            ArticulosParaCompra articulos) {
        this.lector = lector;
        this.proveedores = proveedores;
        this.articulos = articulos;
    }

    public BorradorDeFactura leer(byte[] pdf) {
        FacturaLeida factura = leerPdf(pdf);
        if (factura.renglones().isEmpty()) {
            throw new BadRequestException(SIN_RENGLONES);
        }
        FacturaLeida.Encabezado encabezado = factura.encabezado();
        Optional<ProveedorDeCompra> proveedor = proveedores.buscarPorCuit(encabezado.cuits());
        Map<String, ArticuloPorCodigo> porCodigo = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        proveedor.ifPresent(p -> articulos.porCodigoDeProveedor(p.id(),
                factura.renglones().stream().map(FacturaLeida.Renglon::codigo).distinct().toList())
                .forEach(a -> porCodigo.put(a.codigoProveedor(), a)));

        List<BorradorDeFactura.Renglon> renglones = new ArrayList<>();
        boolean cantidadNoEntera = false;
        for (FacturaLeida.Renglon r : factura.renglones()) {
            ArticuloPorCodigo articulo = porCodigo.get(r.codigo());
            boolean noEntera = articulo != null && "UNIDAD".equals(articulo.unidad())
                    && r.cantidad().stripTrailingZeros().scale() > 0;
            cantidadNoEntera |= noEntera;
            renglones.add(new BorradorDeFactura.Renglon(r.codigo(), r.descripcion(), r.cantidad(), r.costoUnitario(),
                    r.neto(), articulo == null ? null : articulo.id(),
                    articulo == null ? null : articulo.descripcion(), noEntera));
        }
        return new BorradorDeFactura(
                proveedor.map(ProveedorDeCompra::id).orElse(null),
                proveedor.map(ProveedorDeCompra::nombre).orElse(null),
                encabezado.cuits(), tipo(encabezado.letra()), encabezado.puntoVenta(), encabezado.numero(),
                encabezado.fecha(),
                factura.recargos().stream()
                        .map(r -> new BorradorDeFactura.Recargo(r.nombre(), r.porcentaje(), r.importe())).toList(),
                renglones, factura.sumaNetos(), BorradorDeFactura.Control.valueOf(factura.control().name()),
                cantidadNoEntera);
    }

    /** El aviso del PDF escaneado ya viene con el texto de la factura ({@code LectorDeFacturaPdf}). */
    private FacturaLeida leerPdf(byte[] pdf) {
        return lector.leer(pdf);
    }

    private static TipoCompra tipo(String letra) {
        return switch (letra == null ? "" : letra) {
            case "A" -> TipoCompra.FA;
            case "B" -> TipoCompra.FB;
            case "C" -> TipoCompra.FC;
            default -> null;
        };
    }
}
