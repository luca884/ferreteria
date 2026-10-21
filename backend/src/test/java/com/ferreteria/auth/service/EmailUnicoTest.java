package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El email es unico en todo el sistema: el chequeo mira todas las ferreterias y solo devuelve un booleano (R-008). */
class EmailUnicoTest {

    private UsuarioRepository usuarios;
    private EmailUnico emailUnico;

    @BeforeEach
    void armar() {
        usuarios = mock(UsuarioRepository.class);
        emailUnico = new EmailUnico(usuarios);
    }

    @Test
    @DisplayName("normalizar recorta los espacios y pasa a minusculas")
    void normalizarRecortaYBajaAMinusculas() {
        assertThat(EmailUnico.normalizar("  A@B.com ")).isEqualTo("a@b.com");
    }

    @Test
    @DisplayName("enUso delega en el repositorio y devuelve el booleano")
    void enUsoDelegaEnElRepositorio() {
        when(usuarios.existsByEmailIgnoreCase("a@b.com")).thenReturn(true);
        when(usuarios.existsByEmailIgnoreCase("libre@b.com")).thenReturn(false);

        assertThat(emailUnico.enUso("a@b.com")).isTrue();
        assertThat(emailUnico.enUso("libre@b.com")).isFalse();
    }

    @Test
    @DisplayName("enUso normaliza el email antes de consultar")
    void enUsoNormalizaAntesDeConsultar() {
        emailUnico.enUso("  A@B.com ");

        verify(usuarios).existsByEmailIgnoreCase("a@b.com");
    }

    @Test
    @DisplayName("enUsoPorOtro excluye al usuario indicado")
    void enUsoPorOtroExcluyeAlUsuario() {
        when(usuarios.existsByEmailIgnoreCaseAndIdNot("a@b.com", 7L)).thenReturn(true);

        assertThat(emailUnico.enUsoPorOtro(" A@b.com", 7L)).isTrue();
        assertThat(emailUnico.enUsoPorOtro("a@b.com", 8L)).isFalse();
    }

    @Test
    @DisplayName("el mensaje es el de siempre y la clase documenta su excepcion al tenant")
    void mensajeYExcepcionDocumentada() {
        assertThat(EmailUnico.MENSAJE).isEqualTo("Ese email ya está en uso.");
        assertThat(EmailUnico.class.getAnnotation(UnscopedTenantAccess.class)).isNotNull();
        assertThat(EmailUnico.class.getAnnotation(UnscopedTenantAccess.class).reason()).isNotBlank();
    }
}
