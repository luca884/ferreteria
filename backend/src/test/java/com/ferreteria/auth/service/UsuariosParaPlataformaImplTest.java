package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.api.UsuariosParaPlataforma.EmailEnUsoException;
import com.ferreteria.auth.api.UsuariosParaPlataforma.ResumenUsuarios;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.repository.UsuarioRepository.ConteoPorRol;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UsuariosParaPlataformaImplTest {

    private UsuarioRepository usuarios;
    private EmailUnico emailUnico;
    private PasswordEncoder encoder;
    private UsuariosParaPlataformaImpl puerto;

    @BeforeEach
    void armar() {
        usuarios = mock(UsuarioRepository.class);
        emailUnico = mock(EmailUnico.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash-nuevo");
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 77L);
            return u;
        });
        puerto = new UsuariosParaPlataformaImpl(usuarios, emailUnico, encoder);
    }

    private static DataIntegrityViolationException duplicado() {
        return new DataIntegrityViolationException("dup", new SQLException("Duplicate entry", "23000", 1062));
    }

    private static Usuario dueno(long id, String email) {
        Usuario u = Usuario.nuevo(5L, email, "h", "Dueño", Rol.DUENO);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    @DisplayName("la clase lleva @UnscopedTenantAccess con motivo")
    void anotacionDeAccesoSinScope() {
        UnscopedTenantAccess a = UsuariosParaPlataformaImpl.class.getAnnotation(UnscopedTenantAccess.class);

        assertThat(a).isNotNull();
        assertThat(a.reason()).isNotBlank();
    }

    @Test
    @DisplayName("crearDueno exige una transacción abierta (MANDATORY)")
    void crearDuenoEsMandatory() throws Exception {
        Method m = UsuariosParaPlataformaImpl.class.getMethod("crearDueno", long.class, String.class, String.class,
                String.class);

        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("crearDueno guarda un DUENO activo de esa ferretería, con el email normalizado y la contraseña hasheada")
    void crearDueno() {
        long id = puerto.crearDueno(5L, "  Ana  ", "  ANA@Tienda.com ", "clave-segura-77");

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).saveAndFlush(guardado.capture());
        Usuario u = guardado.getValue();
        assertThat(id).isEqualTo(77L);
        assertThat(u.getFerreteriaId()).isEqualTo(5L);
        assertThat(u.getRol()).isEqualTo(Rol.DUENO);
        assertThat(u.isActivo()).isTrue();
        assertThat(u.getNombre()).isEqualTo("Ana");
        assertThat(u.getEmail()).isEqualTo("ana@tienda.com");
        assertThat(u.getPasswordHash()).isEqualTo("hash-nuevo");
        assertThat(u.isDebeCambiarContrasena()).isTrue();
    }

    @Test
    @DisplayName("crearDueno rechaza una contraseña fuera de política con la clave password")
    void crearDuenoContrasenaMala() {
        assertThatThrownBy(() -> puerto.crearDueno(5L, "Ana", "a@b.com", "corta"))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("password"));
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("crearDueno traduce el choque con la unique del email a EmailEnUsoException")
    void crearDuenoEmailRepetido() {
        when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(duplicado());

        assertThatThrownBy(() -> puerto.crearDueno(5L, "Ana", "a@b.com", "clave-segura-77"))
                .isInstanceOf(EmailEnUsoException.class);
    }

    @Test
    @DisplayName("crearDueno relanza cualquier otro error de la base")
    void crearDuenoOtroError() {
        DataIntegrityViolationException otro = new DataIntegrityViolationException("x",
                new SQLException("Data too long", "22001", 1406));
        when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(otro);

        assertThatThrownBy(() -> puerto.crearDueno(5L, "Ana", "a@b.com", "clave-segura-77")).isSameAs(otro);
    }

    @Test
    @DisplayName("emailEnUso consulta el chequeo global")
    void emailEnUso() {
        when(emailUnico.enUso("a@b.com")).thenReturn(true);

        assertThat(puerto.emailEnUso("a@b.com")).isTrue();
        assertThat(puerto.emailEnUso("otro@b.com")).isFalse();
    }

    @Test
    @DisplayName("resumenPorFerretería sale en 2 consultas: cuenta por rol y Dueño de menor id")
    void resumenEnDosConsultas() {
        when(usuarios.conteoActivosPorRol()).thenReturn(List.of(
                new ConteoPorRol(1L, Rol.DUENO, 2), new ConteoPorRol(1L, Rol.VENDEDOR, 3),
                new ConteoPorRol(2L, Rol.VENDEDOR, 1)));
        Usuario d1 = dueno(9L, "nuevo@x.com");
        ReflectionTestUtils.setField(d1, "ferreteriaId", 1L);
        Usuario d2 = dueno(4L, "viejo@x.com");
        ReflectionTestUtils.setField(d2, "ferreteriaId", 1L);
        when(usuarios.duenosActivos()).thenReturn(List.of(d1, d2));

        Map<Long, ResumenUsuarios> resumen = puerto.resumenPorFerreteria();

        assertThat(resumen.get(1L)).isEqualTo(new ResumenUsuarios(2, 3, 4L, "viejo@x.com"));
        assertThat(resumen.get(2L)).isEqualTo(new ResumenUsuarios(0, 1, null, null));
        verify(usuarios, times(1)).conteoActivosPorRol();
        verify(usuarios, times(1)).duenosActivos();
        verifyNoMoreInteractions(usuarios);
    }

    @Test
    @DisplayName("resetear devuelve false si el usuario no es DUENO de esa ferretería, y no guarda nada")
    void resetearAjeno() {
        when(usuarios.duenoDe(5L, 9L)).thenReturn(Optional.empty());

        assertThat(puerto.resetearContrasenaDeDueno(5L, 9L, "clave-segura-77")).isFalse();
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("resetear cambia el hash del Dueño y devuelve true")
    void resetear() {
        Usuario d = dueno(9L, "d@x.com");
        d.cambiarPropiaContrasena("propio");
        when(usuarios.duenoDe(5L, 9L)).thenReturn(Optional.of(d));

        assertThat(puerto.resetearContrasenaDeDueno(5L, 9L, "clave-segura-77")).isTrue();
        assertThat(d.getPasswordHash()).isEqualTo("hash-nuevo");
        assertThat(d.isDebeCambiarContrasena()).isTrue();
        verify(usuarios).saveAndFlush(d);
    }

    @Test
    @DisplayName("resetear rechaza una contraseña fuera de política con la clave password")
    void resetearContrasenaMala() {
        when(usuarios.duenoDe(5L, 9L)).thenReturn(Optional.of(dueno(9L, "d@x.com")));

        assertThatThrownBy(() -> puerto.resetearContrasenaDeDueno(5L, 9L, "corta"))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("password"));
    }
}
