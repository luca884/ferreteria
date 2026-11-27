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
import com.ferreteria.common.exception.CredencialesDemoProtegidasException;
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

    /** Opcional (mejoras-ferreteros-bloque-1, S5). */
    @Column(length = 30)
    private String telefono;

    /**
     * Su contraseña la eligió otra persona (al crearlo o al resetearla): el frontend le pide cambiarla antes de usar
     * cualquier pantalla. Cambiar la propia contraseña la apaga y nunca la prende. El backend no bloquea otros endpoints.
     */
    @Column(name = "debe_cambiar_contrasena", nullable = false)
    private boolean debeCambiarContrasena;

    /** Se administra por migración, no desde la aplicación. */
    @Column(name = "cuenta_demo", nullable = false, insertable = false, updatable = false)
    private boolean cuentaDemo;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = HoraArgentina.ahora();

    /**
     * El secreto del código de la app del super admin (totp-super-admin), cifrado con {@code CifradorTotp}; null sin alta.
     * Solo lectura para JPA: lo escriben las consultas condicionales de {@code UsuarioRepository} (el alta y el código
     * usado), así guardar la entidad nunca pisa un paso usado por otro pedido.
     */
    @Column(name = "totp_secreto", length = 64, insertable = false, updatable = false)
    private byte[] totpSecreto;

    /** Cuándo se confirmó el código de la app con el primer código bueno; null mientras el alta está pendiente. */
    @Column(name = "totp_activado_en", insertable = false, updatable = false)
    private LocalDateTime totpActivadoEn;

    protected Usuario() {}

    /**
     * Solo para {@link #nuevo}: un usuario de una ferreteria arranca con la marca de cambiar la contrasena, y el
     * SUPER_ADMIN no se crea por Java (es el dato semilla). Privado para que nadie pueda dejar la marca en {@code false}.
     */
    private Usuario(Long ferreteriaId, String email, String passwordHash, String nombre, Rol rol) {
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
        Usuario usuario = new Usuario(ferreteriaId, email, passwordHash, nombre, rol);
        usuario.debeCambiarContrasena = true;
        return usuario;
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

    /** En blanco o ausente queda sin telefono; se guarda sin los espacios de los costados. */
    public void cambiarTelefono(String telefono) {
        this.telefono = telefono == null || telefono.isBlank() ? null : telefono.strip();
    }

    public void cambiarActivo(boolean activo) {
        this.activo = activo;
    }

    public void exigirCredencialesEditables() {
        if (cuentaDemo) {
            throw new CredencialesDemoProtegidasException();
        }
    }

    /** El titular cambia la suya: la contraseña pasa a ser una que eligió él. */
    public void cambiarPropiaContrasena(String passwordHash) {
        this.passwordHash = passwordHash;
        this.debeCambiarContrasena = false;
    }

    /** Otra persona (el super admin o el Dueño) le fija una contraseña: tiene que cambiarla al entrar. */
    public void resetearContrasena(String passwordHash) {
        this.passwordHash = passwordHash;
        this.debeCambiarContrasena = true;
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
    public boolean isDebeCambiarContrasena() { return debeCambiarContrasena; }
    public boolean isCuentaDemo() { return cuentaDemo; }
    public BigDecimal getTopeDescuento() { return topeDescuento; }
    public String getTelefono() { return telefono; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public byte[] getTotpSecreto() { return totpSecreto == null ? null : totpSecreto.clone(); }
    public boolean isTotpActivado() { return totpActivadoEn != null; }
}
