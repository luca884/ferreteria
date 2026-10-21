package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.DatosInvalidosException;
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
    private PasswordEncoder encoder;
    private AuthService auth;

    @BeforeEach
    void armar() {
        usuarios = mock(UsuarioRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn(SENUELO);
        auth = new AuthService(usuarios, mock(FerreteriaRepository.class), encoder, mock(JwtService.class));
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
}
