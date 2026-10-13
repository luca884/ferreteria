package com.ferreteria.catalogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/** Proveedor de una ferreteria. La baja es logica (activo = false): los articulos conservan la referencia. */
@Entity
@Table(name = "proveedor")
public class Proveedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(length = 13)
    private String cuit;

    @Column(nullable = false)
    private boolean activo = true;

    /** Mapeo de columnas de la lista, guardado como JSON. Lo fija solo una importacion aplicada. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapeo_lista")
    private MapeoLista mapeoLista;

    @Column(name = "lista_incluye_iva", nullable = false)
    private boolean listaIncluyeIva;

    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion1;

    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion2;

    @Column(precision = 5, scale = 2)
    private BigDecimal bonificacion3;

    protected Proveedor() {}

    public Proveedor(Long ferreteriaId, String nombre, String cuit) {
        this.ferreteriaId = ferreteriaId;
        this.nombre = nombre;
        this.cuit = cuit;
    }

    public void actualizar(String nombre, String cuit, boolean activo) {
        this.nombre = nombre;
        this.cuit = cuit;
        this.activo = activo;
    }

    /** Baja logica: los articulos que lo referencian conservan la referencia. */
    public void desactivar() {
        this.activo = false;
    }

    /**
     * Fija el mapeo, el IVA y las bonificaciones de la lista. Solo lo llama una importacion aplicada:
     * actualizar (el PUT del proveedor) no los toca.
     */
    public void configurarLista(MapeoLista mapeo, boolean incluyeIva, Bonificaciones bonificaciones) {
        this.mapeoLista = mapeo;
        this.listaIncluyeIva = incluyeIva;
        this.bonificacion1 = bonificaciones.b1();
        this.bonificacion2 = bonificaciones.b2();
        this.bonificacion3 = bonificaciones.b3();
    }

    public Bonificaciones bonificaciones() {
        return new Bonificaciones(bonificacion1, bonificacion2, bonificacion3);
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getNombre() { return nombre; }
    public String getCuit() { return cuit; }
    public boolean isActivo() { return activo; }
    public MapeoLista getMapeoLista() { return mapeoLista; }
    public boolean isListaIncluyeIva() { return listaIncluyeIva; }
    public BigDecimal getBonificacion1() { return bonificacion1; }
    public BigDecimal getBonificacion2() { return bonificacion2; }
    public BigDecimal getBonificacion3() { return bonificacion3; }
}
