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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un ingreso, gasto o retiro de la caja (CU-10). El importe es siempre positivo: el tipo dice si suma o resta al
 * efectivo. Registro inmutable, como MovimientoStock: no se edita ni se borra. Sin relaciones JPA: guarda el id de la
 * caja.
 */
@Entity
@Table(name = "movimiento_caja")
public class MovimientoCaja {

    /** "Texto corto": entra en una linea del resumen de la caja. */
    public static final int MOTIVO_MAXIMO = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "caja_id", nullable = false, updatable = false)
    private Long cajaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private TipoMovimientoCaja tipo;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal importe;

    @Column(nullable = false, updatable = false, length = MOTIVO_MAXIMO)
    private String motivo;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha;

    protected MovimientoCaja() {}

    /** Los errores de los datos, por campo y todos juntos; vacio si son validos. */
    public static Map<String, String> validar(TipoMovimientoCaja tipo, BigDecimal importe, String motivo) {
        Map<String, String> errores = new LinkedHashMap<>();
        if (tipo == null) {
            errores.put("tipo", "Elegí el tipo de movimiento: INGRESO, GASTO o RETIRO.");
        }
        ImporteCaja.validarImporte(importe).ifPresent(mensaje -> errores.put("importe", mensaje));
        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            errores.put("motivo", "Escribí el motivo del movimiento.");
        } else if (limpio.length() > MOTIVO_MAXIMO) {
            errores.put("motivo", "El motivo admite hasta " + MOTIVO_MAXIMO + " caracteres.");
        }
        return errores;
    }

    public static MovimientoCaja de(Long ferreteriaId, Long cajaId, TipoMovimientoCaja tipo, BigDecimal importe,
            String motivo, Long usuarioId, LocalDateTime fecha) {
        Map<String, String> errores = validar(tipo, importe, motivo);
        if (!errores.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", errores.values()));
        }
        MovimientoCaja movimiento = new MovimientoCaja();
        movimiento.ferreteriaId = ferreteriaId;
        movimiento.cajaId = cajaId;
        movimiento.tipo = tipo;
        movimiento.importe = importe;
        movimiento.motivo = motivo.strip();
        movimiento.usuarioId = usuarioId;
        movimiento.fecha = fecha;
        return movimiento;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getCajaId() { return cajaId; }
    public TipoMovimientoCaja getTipo() { return tipo; }
    public BigDecimal getImporte() { return importe; }
    public String getMotivo() { return motivo; }
    public Long getUsuarioId() { return usuarioId; }
    public LocalDateTime getFecha() { return fecha; }
}
