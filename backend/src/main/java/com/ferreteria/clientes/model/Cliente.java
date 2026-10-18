package com.ferreteria.clientes.model;

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

/**
 * Un cliente de la ferretería (CL-001). El documento, si está, es el normalizado de {@link DocumentoCliente}: solo
 * dígitos y siempre con su tipo. No se borra, se desactiva: las ventas, los presupuestos y los remitos lo referencian.
 * Consumidor Final no es un cliente: es una venta sin cliente.
 */
@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "razon_social", nullable = false, length = 120)
    private String razonSocial;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo_documento", length = 10)
    private TipoDocumento tipoDocumento;

    @Column(name = "numero_documento", length = 11)
    private String numeroDocumento;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "condicion_iva", nullable = false, length = 30)
    private CondicionIvaCliente condicionIva;

    @Column(length = 200)
    private String domicilio;

    @Column(length = 30)
    private String telefono;

    @Column(length = 30)
    private String whatsapp;

    @Column(length = 120)
    private String email;

    @Column(nullable = false)
    private boolean activo = true;

    protected Cliente() {}

    public static Cliente de(Long ferreteriaId, String razonSocial, TipoDocumento tipoDocumento, String numeroDocumento,
            CondicionIvaCliente condicionIva, String domicilio, String telefono, String whatsapp, String email) {
        Cliente cliente = new Cliente();
        cliente.ferreteriaId = ferreteriaId;
        cliente.actualizar(razonSocial, tipoDocumento, numeroDocumento, condicionIva, domicilio, telefono, whatsapp,
                email);
        return cliente;
    }

    public void actualizar(String razonSocial, TipoDocumento tipoDocumento, String numeroDocumento,
            CondicionIvaCliente condicionIva, String domicilio, String telefono, String whatsapp, String email) {
        this.razonSocial = razonSocial;
        this.tipoDocumento = tipoDocumento;
        this.numeroDocumento = numeroDocumento;
        this.condicionIva = condicionIva;
        this.domicilio = domicilio;
        this.telefono = telefono;
        this.whatsapp = whatsapp;
        this.email = email;
    }

    public void cambiarActivo(boolean activo) {
        this.activo = activo;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getRazonSocial() { return razonSocial; }
    public TipoDocumento getTipoDocumento() { return tipoDocumento; }
    public String getNumeroDocumento() { return numeroDocumento; }
    public CondicionIvaCliente getCondicionIva() { return condicionIva; }
    public String getDomicilio() { return domicilio; }
    public String getTelefono() { return telefono; }
    public String getWhatsapp() { return whatsapp; }
    public String getEmail() { return email; }
    public boolean isActivo() { return activo; }
}
