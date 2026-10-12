package com.ferreteria.catalogo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getNombre() { return nombre; }
    public String getCuit() { return cuit; }
    public boolean isActivo() { return activo; }
}
