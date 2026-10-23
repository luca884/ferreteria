package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.csv.EscritorCsv;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exportacion de articulos a CSV (GDS-19), como la «Exportación Rápida» de GDS (G25): los mismos articulos y en el mismo
 * orden que el listado con esa busqueda, todos de una vez. El margen sale como en la pantalla: el propio o «Heredado».
 */
@Service
public class ExportacionArticulosService {

    static final List<String> ENCABEZADO = List.of("Código", "Código de barras", "Código del proveedor",
            "Descripción", "Rubro", "Proveedor", "Unidad", "Moneda", "Costo", "IVA %", "Margen %", "Precio final",
            "Precio manual", "Stock", "Stock mínimo", "Ubicación", "Activo");

    private final ArticuloRepository articuloRepository;
    private final RubroRepository rubroRepository;
    private final ProveedorRepository proveedorRepository;

    public ExportacionArticulosService(ArticuloRepository articuloRepository, RubroRepository rubroRepository,
                                       ProveedorRepository proveedorRepository) {
        this.articuloRepository = articuloRepository;
        this.rubroRepository = rubroRepository;
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional(readOnly = true)
    public byte[] exportar(String q, boolean incluirInactivos) {
        // Solo los de la ferreteria: un id de rubro o proveedor ajeno no encuentra nombre y queda vacio.
        Map<Long, String> rubros = rubroRepository.findAllScoped().stream()
                .collect(Collectors.toMap(Rubro::getId, Rubro::getNombre));
        Map<Long, String> proveedores = proveedorRepository.findAllScoped().stream()
                .collect(Collectors.toMap(Proveedor::getId, Proveedor::getNombre));
        List<List<Object>> filas = articuloRepository.paraExportarScoped(q, incluirInactivos).stream()
                .map(a -> fila(a, rubros, proveedores))
                .toList();
        return EscritorCsv.escribir(ENCABEZADO, filas);
    }

    private static List<Object> fila(Articulo a, Map<Long, String> rubros, Map<Long, String> proveedores) {
        return Arrays.asList(
                EscritorCsv.codigo(a.getCodigoInterno()),
                EscritorCsv.codigo(a.getCodigoBarras()),
                EscritorCsv.codigo(a.getCodigoProveedor()),
                a.getDescripcion(),
                rubros.get(a.getRubroId()),
                proveedores.get(a.getProveedorId()),
                capitalizada(a.getUnidad().name()),
                a.getMoneda().name(),
                a.getCosto(),
                sinCerosDeMas(a.getAlicuotaIva().porcentaje()),
                a.getMargenPorcentaje() == null ? "Heredado" : sinCerosDeMas(a.getMargenPorcentaje()),
                a.getPrecioFinal(),
                siNo(a.isPrecioManual()),
                sinCerosDeMas(a.getStockActual()),
                sinCerosDeMas(a.getStockMinimo()),
                a.getUbicacion(),
                siNo(a.isActivo()));
    }

    private static String capitalizada(String nombre) {
        return nombre.charAt(0) + nombre.substring(1).toLowerCase();
    }

    private static BigDecimal sinCerosDeMas(BigDecimal valor) {
        BigDecimal limpio = valor.stripTrailingZeros();
        return limpio.scale() < 0 ? limpio.setScale(0) : limpio;
    }

    private static String siNo(boolean valor) {
        return valor ? "Sí" : "No";
    }
}
