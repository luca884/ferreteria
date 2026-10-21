package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
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
}
