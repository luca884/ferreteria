package com.ferreteria.auth.model;

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

import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null solo para SUPER_ADMIN. */
    @Column(name = "ferreteria_id")
    private Long ferreteriaId;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Rol rol;

    @Column(nullable = false)
    private boolean activo = true;

    /** El descuento máximo que puede dar en el mostrador (CU-07); null = sin tope. El Dueño nunca tiene. */
    @Column(name = "tope_descuento", precision = 5, scale = 2)
    private BigDecimal topeDescuento;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = HoraArgentina.ahora();

    protected Usuario() {}

    public Usuario(Long ferreteriaId, String email, String passwordHash, String nombre, Rol rol) {
        this.ferreteriaId = ferreteriaId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nombre = nombre;
        this.rol = rol;
    }

    /**
     * Alta de un usuario de una ferreteria. Un SUPER_ADMIN no se crea por la API (no tiene ferreteria), asi que se
     * rechaza aca: la unica via es el dato semilla.
     */
    public static Usuario nuevo(Long ferreteriaId, String email, String passwordHash, String nombre, Rol rol) {
        if (ferreteriaId == null) {
            throw new IllegalArgumentException("Un usuario de ferretería necesita ferretería");
        }
        exigirRolDeFerreteria(rol);
        return new Usuario(ferreteriaId, email, passwordHash, nombre, rol);
    }

    public void actualizar(String nombre, String email, Rol rol) {
        exigirRolDeFerreteria(rol);
        this.nombre = nombre;
        this.email = email;
        this.rol = rol;
        cambiarTopeDescuento(topeDescuento);
    }

    /** Un Dueño no tiene tope: se descarta el que venga, igual que al pasar a Dueño. */
    public void cambiarTopeDescuento(BigDecimal tope) {
        this.topeDescuento = rol == Rol.DUENO ? null : tope;
    }

    public void cambiarActivo(boolean activo) {
        this.activo = activo;
    }

    public void cambiarPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    private static void exigirRolDeFerreteria(Rol rol) {
        if (rol == null || rol == Rol.SUPER_ADMIN) {
            throw new IllegalArgumentException("El rol de un usuario de ferretería es Dueño o Vendedor");
        }
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNombre() { return nombre; }
    public Rol getRol() { return rol; }
    public boolean isActivo() { return activo; }
    public BigDecimal getTopeDescuento() { return topeDescuento; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
