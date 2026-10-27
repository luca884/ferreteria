package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * El login no revela por el tiempo de respuesta si un email existe: un email inexistente o un usuario inactivo pagan el
 * mismo bcrypt que una contrasena mala.
 */
class AuthServiceTest {

    private static final String SENUELO = "$2a$10$senuelo";

    private UsuarioRepository usuarios;
    private FerreteriaRepository ferreterias;
    private JwtService jwt;
    private PasswordEncoder encoder;
    private AuthService auth;

    @BeforeEach
    void armar() {
        usuarios = mock(UsuarioRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn(SENUELO);
        ferreterias = mock(FerreteriaRepository.class);
        jwt = mock(JwtService.class);
        auth = new AuthService(usuarios, ferreterias, encoder, jwt);
    }

    @Test
    @DisplayName("un email inexistente compara la contrasena contra el hash senuelo y responde credenciales invalidas")
    void emailInexistentePagaBcrypt() {
        when(usuarios.findByEmailIgnoreCase("nadie@demo.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(new LoginRequest("nadie@demo.local", "demo1234")))
                .isInstanceOf(CredencialesInvalidasException.class);
        verify(encoder, times(1)).matches("demo1234", SENUELO);
    }

    @Test
    @DisplayName("un usuario inactivo compara la contrasena contra su hash y responde credenciales invalidas")
    void usuarioInactivoPagaBcrypt() {
        Usuario inactivo = mock(Usuario.class);
        when(inactivo.isActivo()).thenReturn(false);
        when(inactivo.getPasswordHash()).thenReturn("$2a$10$real");
        when(encoder.matches("demo1234", "$2a$10$real")).thenReturn(true);
        when(usuarios.findByEmailIgnoreCase("baja@demo.local")).thenReturn(Optional.of(inactivo));

        assertThatThrownBy(() -> auth.login(new LoginRequest("baja@demo.local", "demo1234")))
                .isInstanceOf(CredencialesInvalidasException.class);
        verify(encoder, times(1)).matches("demo1234", "$2a$10$real");
    }

    // ---- login con la ferreteria suspendida (R-111) ----

    private static final String GENERICO = "Email o contraseña incorrectos";

    private Usuario deFerreteria(boolean activo, boolean passwordBuena) {
        Usuario usuario = mock(Usuario.class);
        when(usuario.isActivo()).thenReturn(activo);
        when(usuario.getPasswordHash()).thenReturn("$2a$10$real");
        when(usuario.getFerreteriaId()).thenReturn(3L);
        when(encoder.matches("demo1234", "$2a$10$real")).thenReturn(passwordBuena);
        when(usuarios.findByEmailIgnoreCase("u@demo.local")).thenReturn(Optional.of(usuario));
        return usuario;
    }

    private Ferreteria ferreteria(boolean activa) {
        Ferreteria ferreteria = mock(Ferreteria.class);
        when(ferreteria.isActiva()).thenReturn(activa);
        when(ferreterias.findById(3L)).thenReturn(Optional.of(ferreteria));
        return ferreteria;
    }

    @Test
    @DisplayName("ferretería suspendida con email y contraseña correctos: mensaje de suspendida, sin token")
    void suspendidaConContrasenaBuena() {
        deFerreteria(true, true);
        ferreteria(false);

        assertThatThrownBy(() -> auth.login(new LoginRequest("u@demo.local", "demo1234")))
                .isExactlyInstanceOf(FerreteriaSuspendidaException.class)
                .isInstanceOf(CredencialesInvalidasException.class)
                .hasMessage("Tu ferretería está suspendida. Contactá al proveedor.");
        verify(jwt, never()).generarToken(any());
    }

    @Test
    @DisplayName("ferretería suspendida con contraseña mala: mensaje genérico y un solo bcrypt")
    void suspendidaConContrasenaMala() {
        deFerreteria(true, false);
        ferreteria(false);

        assertThatThrownBy(() -> auth.login(new LoginRequest("u@demo.local", "demo1234")))
                .isExactlyInstanceOf(CredencialesInvalidasException.class)
                .hasMessage(GENERICO);
        verify(encoder, times(1)).matches("demo1234", "$2a$10$real");
    }

    @Test
    @DisplayName("usuario inactivo con contraseña buena en una ferretería suspendida: genérico")
    void inactivoEnFerreteriaSuspendida() {
        deFerreteria(false, true);
        ferreteria(false);

        assertThatThrownBy(() -> auth.login(new LoginRequest("u@demo.local", "demo1234")))
                .isExactlyInstanceOf(CredencialesInvalidasException.class)
                .hasMessage(GENERICO);
    }

    @Test
    @DisplayName("email inexistente: genérico, y el bcrypt corre igual contra el señuelo")
    void emailInexistenteSigueGenerico() {
        when(usuarios.findByEmailIgnoreCase("nadie@demo.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(new LoginRequest("nadie@demo.local", "demo1234")))
                .isExactlyInstanceOf(CredencialesInvalidasException.class)
                .hasMessage(GENERICO);
        verify(encoder, times(1)).matches("demo1234", SENUELO);
    }

    @Test
    @DisplayName("una ferretería que no existe sigue dando el genérico")
    void ferreteriaInexistente() {
        deFerreteria(true, true);
        when(ferreterias.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.login(new LoginRequest("u@demo.local", "demo1234")))
                .isExactlyInstanceOf(CredencialesInvalidasException.class)
                .hasMessage(GENERICO);
    }

    @Test
    @DisplayName("el super admin, sin ferretería, entra y no se consulta ninguna")
    void superAdminSinFerreteria() {
        Usuario admin = mock(Usuario.class);
        when(admin.isActivo()).thenReturn(true);
        when(admin.getPasswordHash()).thenReturn("$2a$10$admin");
        when(admin.getFerreteriaId()).thenReturn(null);
        when(encoder.matches("demo1234", "$2a$10$admin")).thenReturn(true);
        when(usuarios.findByEmailIgnoreCase("admin@demo.local")).thenReturn(Optional.of(admin));
        when(jwt.generarToken(admin)).thenReturn("token");

        assertThat(auth.login(new LoginRequest("admin@demo.local", "demo1234")).token()).isEqualTo("token");
        verify(ferreterias, never()).findById(any());
    }

    // ---- cambiar la propia contrasena (R-012) ----

    private static final AuthUser YO = new AuthUser(7L, "yo@demo.local", Rol.DUENO, 3L);

    private Usuario yo() {
        Usuario usuario = Usuario.nuevo(3L, "yo@demo.local", "$2a$10$actual", "Yo", Rol.DUENO);
        when(usuarios.findById(7L)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    @Test
    @DisplayName("con la contraseña actual incorrecta responde 400 en passwordActual y no cambia nada")
    void cambiarContrasenaConActualMala() {
        Usuario usuario = yo();
        when(encoder.matches("mala", "$2a$10$actual")).thenReturn(false);

        assertThatThrownBy(() -> auth.cambiarContrasena(YO, "mala", "nueva-clave-99"))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e ->
                        assertThat(e.errors()).containsExactly(
                                entry("passwordActual", "La contraseña actual no es correcta.")));
        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$actual");
    }

    @Test
    @DisplayName("con la actual buena y la nueva corta responde 400 en passwordNueva")
    void cambiarContrasenaConNuevaCorta() {
        Usuario usuario = yo();
        when(encoder.matches("buena", "$2a$10$actual")).thenReturn(true);

        assertThatThrownBy(() -> auth.cambiarContrasena(YO, "buena", "corta"))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("passwordNueva"));
        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$actual");
    }

    @Test
    @DisplayName("con la actual buena y la nueva válida guarda el hash nuevo")
    void cambiarContrasenaOk() {
        Usuario usuario = yo();
        when(encoder.matches("buena", "$2a$10$actual")).thenReturn(true);
        when(encoder.encode("nueva-clave-99")).thenReturn("$2a$10$nuevo");

        auth.cambiarContrasena(YO, "buena", "nueva-clave-99");

        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$nuevo");
    }

    @Test
    @DisplayName("con la marca, repetir la contraseña que le dieron responde 400 en passwordNueva y la marca sigue")
    void cambiarContrasenaObligatoriaPorLaMisma() {
        Usuario usuario = yo();
        when(encoder.matches("la-que-me-dieron", "$2a$10$actual")).thenReturn(true);

        assertThatThrownBy(() -> auth.cambiarContrasena(YO, "la-que-me-dieron", "la-que-me-dieron"))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e ->
                        assertThat(e.errors()).containsExactly(
                                entry("passwordNueva", "Elegí una contraseña distinta de la que te dieron.")));
        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$actual");
        assertThat(usuario.isDebeCambiarContrasena()).isTrue();
    }

    @Test
    @DisplayName("sin la marca, repetir la misma contraseña sigue permitido, como antes")
    void cambiarContrasenaPorLaMismaSinMarca() {
        Usuario usuario = yo();
        usuario.cambiarPropiaContrasena("$2a$10$actual");
        when(encoder.matches("la-misma-de-siempre", "$2a$10$actual")).thenReturn(true);
        when(encoder.encode("la-misma-de-siempre")).thenReturn("$2a$10$otro");

        auth.cambiarContrasena(YO, "la-misma-de-siempre", "la-misma-de-siempre");

        assertThat(usuario.getPasswordHash()).isEqualTo("$2a$10$otro");
    }

    @Test
    @DisplayName("cambiar la propia contraseña apaga la marca de cambio obligatorio")
    void cambiarContrasenaApagaLaMarca() {
        Usuario usuario = yo();
        when(encoder.matches("buena", "$2a$10$actual")).thenReturn(true);
        when(encoder.encode("nueva-clave-99")).thenReturn("$2a$10$nuevo");
        assertThat(usuario.isDebeCambiarContrasena()).isTrue();

        auth.cambiarContrasena(YO, "buena", "nueva-clave-99");

        assertThat(usuario.isDebeCambiarContrasena()).isFalse();
    }

    @Test
    @DisplayName("el login y la sesión devuelven la marca del usuario")
    void sesionConLaMarca() {
        Usuario usuario = Usuario.nuevo(3L, "u@demo.local", "$2a$10$real", "U", Rol.VENDEDOR);
        when(usuarios.findByEmailIgnoreCase("u@demo.local")).thenReturn(Optional.of(usuario));
        when(usuarios.findById(7L)).thenReturn(Optional.of(usuario));
        when(encoder.matches("demo1234", "$2a$10$real")).thenReturn(true);
        Ferreteria ferreteria = mock(Ferreteria.class);
        when(ferreteria.isActiva()).thenReturn(true);
        when(ferreterias.findById(3L)).thenReturn(Optional.of(ferreteria));

        assertThat(auth.login(new LoginRequest("u@demo.local", "demo1234")).sesion().debeCambiarContrasena()).isTrue();
        assertThat(auth.sesionActual(new AuthUser(7L, "u@demo.local", Rol.VENDEDOR, 3L)).debeCambiarContrasena()).isTrue();

        usuario.cambiarPropiaContrasena("otro");

        assertThat(auth.sesionActual(new AuthUser(7L, "u@demo.local", Rol.VENDEDOR, 3L)).debeCambiarContrasena()).isFalse();
    }
}
