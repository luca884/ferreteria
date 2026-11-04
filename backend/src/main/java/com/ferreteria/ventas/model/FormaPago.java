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
 * Una forma de pago de la ferreteria (GDS-11): "Visa 6 cuotas", "Mercado Pago"... Pertenece a un {@link MedioPago}, que
 * es el que suma la caja, y trae su recargo o su descuento en %. No se borra, se desactiva: los pagos la van a
 * referenciar.
 */
@Entity
@Table(name = "forma_pago")
public class FormaPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, length = 60)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MedioPago medio;

    /** Porcentaje: 10.00 significa 10 %. De 0 a 200. */
    @Column(name = "recargo_porcentaje", nullable = false, precision = 5, scale = 2)
    private BigDecimal recargoPorcentaje;

    /** Descuento por pagar con esta forma (descuento en efectivo): 10.00 significa 10 %. De 0 a 99,99. */
    @Column(name = "descuento_porcentaje", nullable = false, precision = 5, scale = 2)
    private BigDecimal descuentoPorcentaje;

    @Column(nullable = false)
    private boolean activa;

    @Column(nullable = false)
    private int orden;

    protected FormaPago() {}

    public static FormaPago de(Long ferreteriaId, String nombre, MedioPago medio, BigDecimal recargoPorcentaje,
            BigDecimal descuentoPorcentaje, boolean activa, int orden) {
        FormaPago forma = new FormaPago();
        forma.ferreteriaId = ferreteriaId;
        forma.actualizar(nombre, medio, recargoPorcentaje, descuentoPorcentaje, activa, orden);
        return forma;
    }

    public void actualizar(String nombre, MedioPago medio, BigDecimal recargoPorcentaje,
            BigDecimal descuentoPorcentaje, boolean activa, int orden) {
        this.nombre = nombre;
        this.medio = medio;
        this.recargoPorcentaje = recargoPorcentaje;
        this.descuentoPorcentaje = descuentoPorcentaje;
        this.activa = activa;
        this.orden = orden;
    }

    /**
     * Lo que la forma le suma (positivo, recargo) o le resta (negativo, descuento) a la parte que paga, en %. Una forma
     * lleva uno de los dos, nunca ambos.
     */
    public BigDecimal ajustePorcentaje() {
        return recargoPorcentaje.subtract(descuentoPorcentaje);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getNombre() { return nombre; }
    public MedioPago getMedio() { return medio; }
    public BigDecimal getRecargoPorcentaje() { return recargoPorcentaje; }
    public BigDecimal getDescuentoPorcentaje() { return descuentoPorcentaje; }
    public boolean isActiva() { return activa; }
    public int getOrden() { return orden; }
}
