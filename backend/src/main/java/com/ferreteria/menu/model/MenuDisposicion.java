package com.ferreteria.menu.model;

import com.ferreteria.auth.model.Rol;
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

@Entity
@Table(name = "menu_disposicion")
public class MenuDisposicion {

    public enum Alcance { ROL, USUARIO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 10)
    private Alcance alcance;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(updatable = false, length = 20)
    private Rol rol;

    @Column(name = "usuario_id", updatable = false)
    private Long usuarioId;

    @Column(nullable = false, columnDefinition = "json")
    private String contenido;

    @Column(name = "creada_en", nullable = false, insertable = false, updatable = false)
    private LocalDateTime creadaEn;

    @Column(name = "actualizada_en", nullable = false, insertable = false, updatable = false)
    private LocalDateTime actualizadaEn;

    protected MenuDisposicion() {}

    private MenuDisposicion(Long ferreteriaId, Alcance alcance, Rol rol, Long usuarioId, String contenido) {
        this.ferreteriaId = ferreteriaId;
        this.alcance = alcance;
        this.rol = rol;
        this.usuarioId = usuarioId;
        this.contenido = contenido;
    }

    public static MenuDisposicion paraRol(Long ferreteriaId, Rol rol, String contenido) {
        return new MenuDisposicion(ferreteriaId, Alcance.ROL, rol, null, contenido);
    }

    public static MenuDisposicion paraUsuario(Long ferreteriaId, Long usuarioId, String contenido) {
        return new MenuDisposicion(ferreteriaId, Alcance.USUARIO, null, usuarioId, contenido);
    }

    public void reemplazarContenido(String contenido) {
        this.contenido = contenido;
    }

    public Long getId() { return id; }
    public String getContenido() { return contenido; }
}
