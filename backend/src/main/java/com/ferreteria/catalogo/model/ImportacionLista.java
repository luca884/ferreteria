package com.ferreteria.catalogo.model;

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

import java.time.LocalDateTime;

/**
 * Registro inmutable de una importacion de lista aplicada. Se escribe en la misma transaccion que
 * los cambios de costo; no hay estado intermedio porque la vista previa no se guarda.
 */
@Entity
@Table(name = "importacion_lista")
public class ImportacionLista {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "proveedor_id", nullable = false, updatable = false)
    private Long proveedorId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Column(nullable = false, updatable = false, length = 255)
    private String archivo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(nullable = false, updatable = false)
    private int suben;

    @Column(nullable = false, updatable = false)
    private int bajan;

    @Column(nullable = false, updatable = false)
    private int nuevos;

    @Column(name = "sin_cambio", nullable = false, updatable = false)
    private int sinCambio;

    @Column(name = "no_estan", nullable = false, updatable = false)
    private int noEstan;

    @Column(name = "con_error", nullable = false, updatable = false)
    private int conError;

    @Column(name = "cambian_moneda", nullable = false, updatable = false)
    private int cambianMoneda;

    @Column(nullable = false, updatable = false)
    private int corrigen;

    @Column(nullable = false, updatable = false)
    private int salteadas;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private EstadoImportacion estado;

    protected ImportacionLista() {}

    public static ImportacionLista aplicada(
            Long ferreteriaId, Long proveedorId, Long usuarioId, String archivo, ConteosImportacion conteos) {
        ImportacionLista importacion = new ImportacionLista();
        importacion.ferreteriaId = ferreteriaId;
        importacion.proveedorId = proveedorId;
        importacion.usuarioId = usuarioId;
        importacion.archivo = archivo;
        importacion.suben = conteos.suben();
        importacion.bajan = conteos.bajan();
        importacion.nuevos = conteos.nuevos();
        importacion.sinCambio = conteos.sinCambio();
        importacion.noEstan = conteos.noEstan();
        importacion.conError = conteos.conError();
        importacion.cambianMoneda = conteos.cambianMoneda();
        importacion.corrigen = conteos.corrigen();
        importacion.salteadas = conteos.salteadas();
        importacion.estado = EstadoImportacion.APLICADA;
        return importacion;
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public Long getProveedorId() { return proveedorId; }
    public Long getUsuarioId() { return usuarioId; }
    public String getArchivo() { return archivo; }
    public LocalDateTime getFecha() { return fecha; }
    public int getSuben() { return suben; }
    public int getBajan() { return bajan; }
    public int getNuevos() { return nuevos; }
    public int getSinCambio() { return sinCambio; }
    public int getNoEstan() { return noEstan; }
    public int getConError() { return conError; }
    public int getCambianMoneda() { return cambianMoneda; }
    public int getCorrigen() { return corrigen; }
    public int getSalteadas() { return salteadas; }
    public EstadoImportacion getEstado() { return estado; }
}
