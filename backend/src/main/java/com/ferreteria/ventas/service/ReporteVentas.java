package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.TipoComprobante;
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
 * <p>Desde CU-08 (C4) las notas de credito y las anulaciones internas RESTAN en su fecha (no en la de la venta), atribuidas
 * a quien las hizo: el total, el neto, el IVA, el costo y la ganancia van netos. No cuentan como ventas en
 * {@code cantidad} ni en {@code ventasSinCosto}; el resumen las muestra aparte ({@code notasDeCredito}) junto con la
 * ganancia sin ellas ({@code gananciaSinNotas}, G66). «Por medio de pago» no las resta (no mueven plata) y «Con descuento»
 * lista solo ventas.
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

    /**
     * {@code cantidad} son las ventas; los importes y {@code ganancia} van netos de las notas de credito, y
     * {@code totalSinNotas} y {@code gananciaSinNotas} son los de las ventas solas (el renglon «Ventas» de la pantalla).
     */
    public record Resumen(LocalDate desde, LocalDate hasta, int cantidad, BigDecimal total, BigDecimal neto,
            BigDecimal iva, BigDecimal costo, BigDecimal ganancia, int ventasSinCosto, NotasDeCredito notasDeCredito,
            BigDecimal gananciaSinNotas, BigDecimal totalSinNotas) {}

    /** El renglon «Notas de credito» del resumen: cuantas y su total, en positivo (lo que se resto). */
    public record NotasDeCredito(int cantidad, BigDecimal total) {}

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

    /**
     * Una venta o nota de credito del rango con su costo; {@code costo} es null si algun renglon no tiene costo. Los
     * importes de una nota ya vienen en negativo.
     */
    private record Venta(LocalDateTime fecha, Long usuarioId, BigDecimal total, BigDecimal neto, BigDecimal iva,
            BigDecimal costo, boolean nota) {

        /** Lo que cuenta como venta en {@code cantidad} y {@code ventasSinCosto}: la nota no. */
        int comoVenta() {
            return nota ? 0 : 1;
        }

        int sinCosto() {
            return conCosto() ? 0 : comoVenta();
        }

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
        BigDecimal gananciaSinNotas = BigDecimal.ZERO;
        BigDecimal totalNotas = BigDecimal.ZERO;
        int cantidad = 0;
        int notas = 0;
        int sinCosto = 0;
        for (Venta v : ventas) {
            total = total.add(v.total());
            neto = neto.add(v.neto());
            iva = iva.add(v.iva());
            cantidad += v.comoVenta();
            sinCosto += v.sinCosto();
            if (v.nota()) {
                notas++;
                totalNotas = totalNotas.subtract(v.total());
            }
            if (v.conCosto()) {
                costo = costo.add(v.costo());
                ganancia = ganancia.add(v.ganancia());
                if (!v.nota()) {
                    gananciaSinNotas = gananciaSinNotas.add(v.ganancia());
                }
            }
        }
        return new Resumen(rango.desde(), rango.hasta(), cantidad, total, neto, iva, costo, ganancia, sinCosto,
                new NotasDeCredito(notas, totalNotas), gananciaSinNotas, total.add(totalNotas));
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

    /** Un grupo por dia ({@code 2026-10-22}) o por mes ({@code 2026-10}) con ventas o notas de credito, en orden. */
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

    /** Las ventas y las notas de credito del rango; las de una nota, con los importes en negativo (restan). */
    private List<Venta> ventas(Rango rango) {
        return comprobantes.ventasDelRangoScoped(inicio(rango), fin(rango)).stream()
                .map(f -> {
                    boolean nota = f[8] == TipoComprobante.NOTA_CREDITO;
                    BigDecimal costo = entero(f[7]) == 0 ? (BigDecimal) f[6] : null;
                    return new Venta((LocalDateTime) f[1], (Long) f[2], signo((BigDecimal) f[3], nota),
                            signo((BigDecimal) f[4], nota), signo((BigDecimal) f[5], nota),
                            costo == null ? null : signo(costo, nota), nota);
                })
                .toList();
    }

    private static BigDecimal signo(BigDecimal importe, boolean nota) {
        return nota ? importe.negate() : importe;
    }

    private static Map<String, Grupo> agrupar(List<Venta> ventas, Function<Venta, String> clave) {
        Map<String, Grupo> grupos = new LinkedHashMap<>();
        for (Venta v : ventas) {
            String k = clave.apply(v);
            Grupo previo = grupos.getOrDefault(k, new Grupo(k, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            grupos.put(k, new Grupo(k, previo.cantidad() + v.comoVenta(), previo.total().add(v.total()),
                    previo.ganancia().add(v.ganancia()), previo.ventasSinCosto() + v.sinCosto()));
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
