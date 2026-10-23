package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reportes de ventas del Dueno (como el menu Ventas de GDS: G63, G66, G67): resumen, por usuario, por medio de pago, por
 * dia o mes, por articulo, por rubro y con descuento, de un rango de dias de Buenos Aires. Solo ventas: los presupuestos
 * y los remitos no son comprobantes, y las cobranzas no son ventas.
 *
 * <p>Los totales salen del total de cada comprobante, nunca de sumar renglones (GDS-37). La ganancia es el neto menos el
 * costo copiado al vender (V26), y solo de las ventas cuyo costo esta completo: una venta con algun renglon sin costo
 * (anterior a la V26) se cuenta aparte en {@code ventasSinCosto}. Por articulo y por rubro la cuenta es por renglon.
 */
@Service
public class ReporteVentas {

    /** Rango maximo de un reporte, en dias contando los dos extremos. */
    static final int DIAS_MAXIMOS = 366;

    public enum Agrupar { DIA, MES }

    public record Rango(LocalDate desde, LocalDate hasta) {}

    public record Resumen(LocalDate desde, LocalDate hasta, int cantidad, BigDecimal total, BigDecimal neto,
            BigDecimal iva, BigDecimal costo, BigDecimal ganancia, int ventasSinCosto) {}

    /** Una fila agrupada por usuario o por periodo. */
    public record Grupo(String clave, int cantidad, BigDecimal total, BigDecimal ganancia, int ventasSinCosto) {}

    public record PorUsuario(Long usuarioId, String nombre, int cantidad, BigDecimal total, BigDecimal ganancia,
            int ventasSinCosto) {}

    public record PorMedioPago(String formaPago, MedioPago medio, long cantidad, BigDecimal monto) {}

    public record PorArticulo(Long articuloId, String descripcion, BigDecimal cantidad, BigDecimal total,
            BigDecimal ganancia, int renglonesSinCosto, int renglones) {}

    public record PorRubro(String rubro, int renglones, BigDecimal total, BigDecimal ganancia, int renglonesSinCosto) {}

    public record ConDescuento(Long ventaId, Long numero, LocalDateTime fecha, String usuario, String cliente,
            BigDecimal descuentoPorcentaje, BigDecimal descuento, BigDecimal total) {}

    /** Una venta del rango con su costo; {@code costo} es null si algun renglon no tiene costo. */
    private record Venta(LocalDateTime fecha, Long usuarioId, BigDecimal total, BigDecimal neto, BigDecimal iva,
            BigDecimal costo) {

        boolean conCosto() {
            return costo != null;
        }

        BigDecimal ganancia() {
            return conCosto() ? neto.subtract(costo) : BigDecimal.ZERO;
        }
    }

    static final String SIN_RUBRO = "Sin rubro";

    private final ComprobanteRepository comprobantes;
    private final CajaRepository cajas;
    private final ArticulosParaVenta articulos;
    private final Clock reloj;

    public ReporteVentas(ComprobanteRepository comprobantes, CajaRepository cajas, ArticulosParaVenta articulos,
            Clock reloj) {
        this.comprobantes = comprobantes;
        this.cajas = cajas;
        this.articulos = articulos;
        this.reloj = reloj;
    }

    /** El rango pedido: sin fechas es hoy; «hasta» no puede ser anterior a «desde» y el rango es de hasta 366 dias. */
    public Rango rango(LocalDate desde, LocalDate hasta) {
        LocalDate hoy = LocalDate.now(reloj);
        LocalDate d = desde != null ? desde : (hasta != null ? hasta : hoy);
        LocalDate h = hasta != null ? hasta : (desde != null ? desde : hoy);
        if (h.isBefore(d)) {
            throw new DatosInvalidosException(Map.of("hasta", "La fecha «Hasta» no puede ser anterior a «Desde»."));
        }
        if (ChronoUnit.DAYS.between(d, h) + 1 > DIAS_MAXIMOS) {
            throw new DatosInvalidosException(Map.of("desde", "El reporte admite hasta 366 días."));
        }
        return new Rango(d, h);
    }

    @Transactional(readOnly = true)
    public Resumen resumen(Rango rango) {
        List<Venta> ventas = ventas(rango);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal neto = BigDecimal.ZERO;
        BigDecimal iva = BigDecimal.ZERO;
        BigDecimal costo = BigDecimal.ZERO;
        BigDecimal ganancia = BigDecimal.ZERO;
        int sinCosto = 0;
        for (Venta v : ventas) {
            total = total.add(v.total());
            neto = neto.add(v.neto());
            iva = iva.add(v.iva());
            if (v.conCosto()) {
                costo = costo.add(v.costo());
                ganancia = ganancia.add(v.ganancia());
            } else {
                sinCosto++;
            }
        }
        return new Resumen(rango.desde(), rango.hasta(), ventas.size(), total, neto, iva, costo, ganancia, sinCosto);
    }

    @Transactional(readOnly = true)
    public List<PorUsuario> porUsuario(Rango rango) {
        Map<String, Grupo> grupos = agrupar(ventas(rango), v -> String.valueOf(v.usuarioId()));
        Map<Long, String> nombres = cajas.nombresScoped(grupos.keySet().stream().map(Long::valueOf).toList());
        return grupos.values().stream()
                .sorted(Comparator.comparing(Grupo::total).reversed())
                .map(g -> new PorUsuario(Long.valueOf(g.clave()), nombres.get(Long.valueOf(g.clave())),
                        g.cantidad(), g.total(), g.ganancia(), g.ventasSinCosto()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PorMedioPago> porMedioPago(Rango rango) {
        return comprobantes.pagosDelRangoScoped(inicio(rango), fin(rango)).stream()
                .map(f -> new PorMedioPago((String) f[0], (MedioPago) f[1], (Long) f[2], (BigDecimal) f[3]))
                .toList();
    }

    /** Un grupo por dia ({@code 2026-10-22}) o por mes ({@code 2026-10}) con ventas, en orden. */
    @Transactional(readOnly = true)
    public List<Grupo> porPeriodo(Rango rango, Agrupar agrupar) {
        Function<Venta, String> periodo = agrupar == Agrupar.MES
                ? v -> v.fecha().toLocalDate().toString().substring(0, 7)
                : v -> v.fecha().toLocalDate().toString();
        return new ArrayList<>(new TreeMap<>(agrupar(ventas(rango), periodo)).values());
    }

    @Transactional(readOnly = true)
    public List<PorArticulo> porArticulo(Rango rango) {
        return comprobantes.articulosDelRangoScoped(inicio(rango), fin(rango)).stream()
                .map(f -> new PorArticulo((Long) f[0], (String) f[1], (BigDecimal) f[2], (BigDecimal) f[3],
                        (BigDecimal) f[4], entero(f[5]), entero(f[6])))
                .toList();
    }

    /** Por el rubro de HOY de cada articulo vendido; los que no tienen rubro van a «Sin rubro». */
    @Transactional(readOnly = true)
    public List<PorRubro> porRubro(Rango rango) {
        List<PorArticulo> vendidos = porArticulo(rango);
        Map<Long, String> rubros = articulos.rubros(vendidos.stream().map(PorArticulo::articuloId).toList());
        Map<String, PorRubro> porRubro = new LinkedHashMap<>();
        for (PorArticulo a : vendidos) {
            String rubro = rubros.getOrDefault(a.articuloId(), SIN_RUBRO);
            PorRubro previo = porRubro.getOrDefault(rubro, new PorRubro(rubro, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            porRubro.put(rubro, new PorRubro(rubro, previo.renglones() + a.renglones(), previo.total().add(a.total()),
                    previo.ganancia().add(a.ganancia()), previo.renglonesSinCosto() + a.renglonesSinCosto()));
        }
        return porRubro.values().stream().sorted(Comparator.comparing(PorRubro::total).reversed()).toList();
    }

    /** El descuento es lo que se resto: subtotal (antes de descuentos) + recargo - total. */
    @Transactional(readOnly = true)
    public List<ConDescuento> conDescuento(Rango rango) {
        List<Comprobante> ventas = comprobantes.conDescuentoDelRangoScoped(inicio(rango), fin(rango));
        Map<Long, String> nombres = cajas.nombresScoped(ventas.stream().map(Comprobante::getUsuarioId).toList());
        return ventas.stream()
                .map(c -> new ConDescuento(c.getId(), c.getNumero(), c.getFecha(), nombres.get(c.getUsuarioId()),
                        c.getClienteNombre(), c.getDescuentoPorcentaje(),
                        c.getSubtotal().add(c.getRecargo()).subtract(c.getTotal()), c.getTotal()))
                .toList();
    }

    private List<Venta> ventas(Rango rango) {
        return comprobantes.ventasDelRangoScoped(inicio(rango), fin(rango)).stream()
                .map(f -> new Venta((LocalDateTime) f[1], (Long) f[2], (BigDecimal) f[3], (BigDecimal) f[4],
                        (BigDecimal) f[5], entero(f[7]) == 0 ? (BigDecimal) f[6] : null))
                .toList();
    }

    private static Map<String, Grupo> agrupar(List<Venta> ventas, Function<Venta, String> clave) {
        Map<String, Grupo> grupos = new LinkedHashMap<>();
        for (Venta v : ventas) {
            String k = clave.apply(v);
            Grupo previo = grupos.getOrDefault(k, new Grupo(k, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            grupos.put(k, new Grupo(k, previo.cantidad() + 1, previo.total().add(v.total()),
                    previo.ganancia().add(v.ganancia()), previo.ventasSinCosto() + (v.conCosto() ? 0 : 1)));
        }
        return grupos;
    }

    private static LocalDateTime inicio(Rango rango) {
        return rango.desde().atStartOfDay();
    }

    /** Exclusivo: el dia «hasta» entra completo. */
    private static LocalDateTime fin(Rango rango) {
        return rango.hasta().plusDays(1).atStartOfDay();
    }

    private static int entero(Object numero) {
        return ((Number) numero).intValue();
    }
}
