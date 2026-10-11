package com.ferreteria.auth;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.auth.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-ferreteria-solo-para-tests-0123456789";

    private final JwtService jwtService = new JwtService(SECRET, 60_000);

    @Test
    void elTokenLlevaUsuarioRolYFerreteria() {
        Usuario usuario = usuario(7L, 3L, Rol.DUENO);

        AuthUser leido = jwtService.leerToken(jwtService.generarToken(usuario)).orElseThrow();

        assertThat(leido).isEqualTo(new AuthUser(7L, "x@demo.local", Rol.DUENO, 3L));
    }

    @Test
    void elSuperAdminNoTieneFerreteria() {
        Usuario admin = usuario(1L, null, Rol.SUPER_ADMIN);

        AuthUser leido = jwtService.leerToken(jwtService.generarToken(admin)).orElseThrow();

        assertThat(leido.ferreteriaId()).isNull();
    }

    @Test
    void unTokenFirmadoConOtraClaveEsInvalido() {
        JwtService otro = new JwtService("otra-clave-distinta-de-al-menos-32-bytes-xx", 60_000);
        String token = otro.generarToken(usuario(7L, 3L, Rol.DUENO));

        assertThat(jwtService.leerToken(token)).isEmpty();
    }

    @Test
    void unTokenVencidoEsInvalido() {
        JwtService vencido = new JwtService(SECRET, -1_000);
        String token = vencido.generarToken(usuario(7L, 3L, Rol.DUENO));

        assertThat(jwtService.leerToken(token)).isEmpty();
    }

    @Test
    void basuraNoEsUnToken() {
        assertThat(jwtService.leerToken("no-es-un-jwt")).isEmpty();
    }

    @Test
    void rechazaUnaClaveCorta() {
        assertThatThrownBy(() -> new JwtService("corta", 60_000)).isInstanceOf(IllegalStateException.class);
    }

    private static Usuario usuario(Long id, Long ferreteriaId, Rol rol) {
        Usuario usuario = new Usuario(ferreteriaId, "x@demo.local", "hash", "X", rol);
        ReflectionTestUtils.setField(usuario, "id", id);
        return usuario;
    }
}
