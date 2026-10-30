package com.ferreteria.facturacion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/**
 * Una alícuota del desglose del registro fiscal (tabla {@code comprobante_fiscal_alicuota}, V32): el Id de WSFEv1, su
 * {@code BaseImp} y su {@code Importe}, congelados al registrar. La C no lleva filas. Inmutable, como lo congelado del
 * registro.
 */
@Entity
@Immutable
@Table(name = "comprobante_fiscal_alicuota")
public class ComprobanteFiscalAlicuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "comprobante_fiscal_id", nullable = false, updatable = false)
    private long comprobanteFiscalId;

    @Column(name = "iva_id", nullable = false, updatable = false)
    private int ivaId;

    @Column(name = "base_imp", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal baseImp;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal importe;

    protected ComprobanteFiscalAlicuota() {
    }

    public static ComprobanteFiscalAlicuota de(long ferreteriaId, long comprobanteFiscalId, AlicuotaDesglosada alicuota) {
        ComprobanteFiscalAlicuota a = new ComprobanteFiscalAlicuota();
        a.ferreteriaId = ferreteriaId;
        a.comprobanteFiscalId = comprobanteFiscalId;
        a.ivaId = alicuota.alicuota().id();
        a.baseImp = alicuota.baseImp();
        a.importe = alicuota.importe();
        return a;
    }

    /** La misma alícuota en otro registro: el desglose de la factura, copiado a su nota de crédito (rama 8). */
    public static ComprobanteFiscalAlicuota copiaDe(long comprobanteFiscalId, ComprobanteFiscalAlicuota original) {
        ComprobanteFiscalAlicuota a = new ComprobanteFiscalAlicuota();
        a.ferreteriaId = original.ferreteriaId;
        a.comprobanteFiscalId = comprobanteFiscalId;
        a.ivaId = original.ivaId;
        a.baseImp = original.baseImp;
        a.importe = original.importe;
        return a;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public long getComprobanteFiscalId() { return comprobanteFiscalId; }
    public int getIvaId() { return ivaId; }
    public BigDecimal getBaseImp() { return baseImp; }
    public BigDecimal getImporte() { return importe; }
}
