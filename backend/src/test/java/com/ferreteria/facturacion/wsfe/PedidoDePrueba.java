package com.ferreteria.facturacion.wsfe;

import com.ferreteria.facturacion.model.DesgloseFiscal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Arma pedidos de {@code FECAESolicitar} para los tests. Por defecto: Factura B (6), punto de venta 1, número 1, concepto 1,
 * a Consumidor Final sin identificar (DocTipo 99, DocNro 0, CondicionIVAReceptorId 5), por $ 121 con $ 100 al 21 %.
 */
public final class PedidoDePrueba {

    private int ptoVta = 1;
    private int cbteTipo = 6;
    private int concepto = 1;
    private int docTipo = 99;
    private long docNro;
    private long numero = 1;
    private LocalDate cbteFch;
    private BigDecimal impTotal = new BigDecimal("121.00");
    private BigDecimal impTotConc = BigDecimal.ZERO;
    private BigDecimal impNeto = new BigDecimal("100.00");
    private BigDecimal impOpEx = BigDecimal.ZERO;
    private BigDecimal impTrib = BigDecimal.ZERO;
    private BigDecimal impIva = new BigDecimal("21.00");
    private Integer condicion = 5;
    private List<AlicIva> iva = List.of(alicuota(5, "100.00", "21.00"));
    private List<CbteAsoc> cbtesAsoc = List.of();
    private PeriodoAsoc periodoAsoc;

    private PedidoDePrueba(LocalDate cbteFch) {
        this.cbteFch = cbteFch;
    }

    /** La Factura B por defecto, con esa CbteFch. */
    public static PedidoDePrueba facturaB(LocalDate cbteFch) {
        return new PedidoDePrueba(cbteFch);
    }

    public static AlicIva alicuota(int id, String baseImp, String importe) {
        return new AlicIva(id, new BigDecimal(baseImp), new BigDecimal(importe));
    }

    public PedidoDePrueba tipo(int cbteTipo) {
        this.cbteTipo = cbteTipo;
        return this;
    }

    public PedidoDePrueba ptoVta(int ptoVta) {
        this.ptoVta = ptoVta;
        return this;
    }

    public PedidoDePrueba numero(long numero) {
        this.numero = numero;
        return this;
    }

    public PedidoDePrueba concepto(int concepto) {
        this.concepto = concepto;
        return this;
    }

    public PedidoDePrueba documento(int docTipo, long docNro) {
        this.docTipo = docTipo;
        this.docNro = docNro;
        return this;
    }

    public PedidoDePrueba condicion(Integer condicionIvaReceptorId) {
        this.condicion = condicionIvaReceptorId;
        return this;
    }

    public PedidoDePrueba cbteFch(LocalDate cbteFch) {
        this.cbteFch = cbteFch;
        return this;
    }

    /** ImpTotal, ImpNeto e ImpIVA (los demás importes quedan como están). */
    public PedidoDePrueba importes(String total, String neto, String iva) {
        this.impTotal = new BigDecimal(total);
        this.impNeto = new BigDecimal(neto);
        this.impIva = new BigDecimal(iva);
        return this;
    }

    public PedidoDePrueba impTotal(String total) {
        this.impTotal = new BigDecimal(total);
        return this;
    }

    public PedidoDePrueba alicuotas(AlicIva... alicuotas) {
        this.iva = List.of(alicuotas);
        return this;
    }

    /** Todos los importes y el array de IVA salen del desglose del dominio. */
    public PedidoDePrueba desglose(DesgloseFiscal desglose) {
        this.impTotal = desglose.impTotal();
        this.impTotConc = desglose.impTotConc();
        this.impNeto = desglose.impNeto();
        this.impOpEx = desglose.impOpEx();
        this.impTrib = desglose.impTrib();
        this.impIva = desglose.impIva();
        this.iva = desglose.alicuotas().stream()
                .map(a -> new AlicIva(a.alicuota().id(), a.baseImp(), a.importe()))
                .toList();
        return this;
    }

    public PedidoDePrueba asociados(CbteAsoc... asociados) {
        this.cbtesAsoc = List.of(asociados);
        return this;
    }

    public PedidoDePrueba periodo(PeriodoAsoc periodoAsoc) {
        this.periodoAsoc = periodoAsoc;
        return this;
    }

    public FeCaeDetRequest detalle() {
        return new FeCaeDetRequest(concepto, docTipo, docNro, numero, numero, cbteFch, impTotal, impTotConc, impNeto, impOpEx,
                impTrib, impIva, "PES", BigDecimal.ONE, condicion, iva, cbtesAsoc, periodoAsoc);
    }

    public FeCaeReq armar() {
        return new FeCaeReq(FeCabReq.de(ptoVta, cbteTipo), detalle());
    }
}
