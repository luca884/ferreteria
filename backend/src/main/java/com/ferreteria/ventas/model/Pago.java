package com.ferreteria.ventas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * Un pago de la venta. La tabla es 1 a N desde ya (pago mixto, corte 5); el corte 1 crea siempre uno, por el
 * total y con el medio elegido. El importe recibido y el vuelto no se guardan.
 */
@Entity
@Table(name = "pago")
public class Pago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "comprobante_id", nullable = false, updatable = false)
    private Long comprobanteId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private MedioPago medio;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    protected Pago() {}

    public static Pago de(Long ferreteriaId, Long comprobanteId, MedioPago medio, BigDecimal monto) {
        Pago pago = new Pago();
        pago.ferreteriaId = ferreteriaId;
        pago.comprobanteId = comprobanteId;
        pago.medio = medio;
        pago.monto = monto;
        return pago;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getComprobanteId() { return comprobanteId; }
    public MedioPago getMedio() { return medio; }
    public BigDecimal getMonto() { return monto; }
}
