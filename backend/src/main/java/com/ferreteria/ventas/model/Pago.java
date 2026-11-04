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
 * Un pago de la venta: uno por forma de pago (pago mixto, GDS-07). Guarda la forma, una copia de su nombre y su % del
 * momento (cambiarlos despues no toca la venta), el medio, el recargo en importe y el monto: lo que se cobro con ese
 * medio, que es la parte del total que cubre mas su recargo. La caja suma el monto por medio. El importe recibido y el
 * vuelto no se guardan.
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

    @Column(name = "forma_pago_id", nullable = false, updatable = false)
    private Long formaPagoId;

    @Column(name = "forma_pago_nombre", nullable = false, updatable = false, length = 60)
    private String formaPagoNombre;

    @Column(name = "recargo_porcentaje", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal recargoPorcentaje;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal recargo;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    protected Pago() {}

    /** {@code parte} es lo que el pago cubre del total sin recargo; el monto guardado es la parte mas el recargo. */
    public static Pago de(Long ferreteriaId, Long comprobanteId, FormaPago forma, BigDecimal parte,
            BigDecimal recargo) {
        Pago pago = new Pago();
        pago.ferreteriaId = ferreteriaId;
        pago.comprobanteId = comprobanteId;
        pago.medio = forma.getMedio();
        pago.formaPagoId = forma.getId();
        pago.formaPagoNombre = forma.getNombre();
        pago.recargoPorcentaje = forma.ajustePorcentaje();
        pago.recargo = recargo;
        pago.monto = parte.add(recargo);
        return pago;
    }

    /** Lo que el pago cubre del total sin recargo: el monto menos el recargo. */
    public BigDecimal getParte() { return monto.subtract(recargo); }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getComprobanteId() { return comprobanteId; }
    public MedioPago getMedio() { return medio; }
    public Long getFormaPagoId() { return formaPagoId; }
    public String getFormaPagoNombre() { return formaPagoNombre; }
    public BigDecimal getRecargoPorcentaje() { return recargoPorcentaje; }
    public BigDecimal getRecargo() { return recargo; }
    public BigDecimal getMonto() { return monto; }
}
