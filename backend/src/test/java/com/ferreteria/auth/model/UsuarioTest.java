package com.ferreteria.auth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Version;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UsuarioTest {

    @Test
    @DisplayName("nuevo arma un usuario activo de esa ferreteria")
    void nuevoArmaUnUsuarioActivo() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        assertThat(u.getFerreteriaId()).isEqualTo(5L);
        assertThat(u.getEmail()).isEqualTo("a@b.com");
        assertThat(u.getPasswordHash()).isEqualTo("hash");
        assertThat(u.getNombre()).isEqualTo("Ana");
        assertThat(u.getRol()).isEqualTo(Rol.VENDEDOR);
        assertThat(u.isActivo()).isTrue();
        assertThat(u.getCreadoEn()).isNotNull();
    }

    @Test
    @DisplayName("nuevo rechaza el rol SUPER_ADMIN")
    void nuevoRechazaSuperAdmin() {
        assertThatThrownBy(() -> Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.SUPER_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nuevo rechaza una ferreteria nula")
    void nuevoRechazaFerreteriaNula() {
        assertThatThrownBy(() -> Usuario.nuevo(null, "a@b.com", "hash", "Ana", Rol.DUENO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("actualizar cambia nombre, email y rol")
    void actualizarMuta() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        u.actualizar("Ana Maria", "am@b.com", Rol.DUENO);

        assertThat(u.getNombre()).isEqualTo("Ana Maria");
        assertThat(u.getEmail()).isEqualTo("am@b.com");
        assertThat(u.getRol()).isEqualTo(Rol.DUENO);
    }

    @Test
    @DisplayName("actualizar no permite pasar a SUPER_ADMIN")
    void actualizarRechazaSuperAdmin() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        assertThatThrownBy(() -> u.actualizar("Ana", "a@b.com", Rol.SUPER_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cambiarActivo desactiva y reactiva")
    void cambiarActivoMuta() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        u.cambiarActivo(false);
        assertThat(u.isActivo()).isFalse();
        u.cambiarActivo(true);
        assertThat(u.isActivo()).isTrue();
    }

    @Test
    @DisplayName("un usuario recién creado por otra persona queda marcado para cambiar la contraseña")
    void nuevoQuedaMarcado() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        assertThat(u.isDebeCambiarContrasena()).isTrue();
    }

    @Test
    @DisplayName("cambiarPropiaContrasena reemplaza el hash y apaga la marca")
    void cambiarPropiaContrasenaApagaLaMarca() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);

        u.cambiarPropiaContrasena("otro");

        assertThat(u.getPasswordHash()).isEqualTo("otro");
        assertThat(u.isDebeCambiarContrasena()).isFalse();
    }

    @Test
    @DisplayName("cambiarPropiaContrasena nunca prende la marca")
    void cambiarPropiaContrasenaNoLaPrende() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);
        u.cambiarPropiaContrasena("uno");

        u.cambiarPropiaContrasena("dos");

        assertThat(u.isDebeCambiarContrasena()).isFalse();
    }

    @Test
    @DisplayName("resetearContrasena reemplaza el hash y prende la marca")
    void resetearContrasenaPrendeLaMarca() {
        Usuario u = Usuario.nuevo(5L, "a@b.com", "hash", "Ana", Rol.VENDEDOR);
        u.cambiarPropiaContrasena("propio");

        u.resetearContrasena("otro");

        assertThat(u.getPasswordHash()).isEqualTo("otro");
        assertThat(u.isDebeCambiarContrasena()).isTrue();
    }

    @Test
    @DisplayName("sin setters ni @Version (el handler de version es el de articulos)")
    void sinSettersNiVersion() {
        assertThat(Arrays.stream(Usuario.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .noneMatch(n -> n.startsWith("set"));
        assertThat(Arrays.stream(Usuario.class.getDeclaredFields()))
                .noneMatch(f -> f.isAnnotationPresent(Version.class));
    }
}
