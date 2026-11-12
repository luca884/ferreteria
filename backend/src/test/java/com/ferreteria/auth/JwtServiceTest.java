package com.ferreteria.auth;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.auth.service.JwtService;
import com.ferreteria.support.RelojControlable;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-ferreteria-solo-para-tests-0123456789";
    private static final SecretKey CLAVE = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final RelojControlable reloj = new RelojControlable(Instant.now());
    private final JwtService jwtService = new JwtService(SECRET, 60_000, reloj);

    @Test
    void elTokenLlevaUsuarioRolYFerreteria() {
        Usuario usuario = usuario(7L, 3L, Rol.DUENO);

        AuthUser leido = jwtService.leerToken(jwtService.generarToken(usuario)).orElseThrow();

        assertThat(leido).isEqualTo(new AuthUser(7L, "x@demo.local", Rol.DUENO, 3L));
    }

    @Test
    void elSuperAdminNoTieneFerreteria() {
        Usuario admin = usuario(1L, null, Rol.SUPER_ADMIN);

        AuthUser leido = jwtService.leerToken(jwtService.generarTokenConSegundoFactor(admin)).orElseThrow();

        assertThat(leido.ferreteriaId()).isNull();
    }

    @Test
    void unTokenFirmadoConOtraClaveEsInvalido() {
        JwtService otro = new JwtService("otra-clave-distinta-de-al-menos-32-bytes-xx", 60_000, reloj);
        String token = otro.generarToken(usuario(7L, 3L, Rol.DUENO));

        assertThat(jwtService.leerToken(token)).isEmpty();
    }

    @Test
    void unTokenVencidoEsInvalido() {
        JwtService vencido = new JwtService(SECRET, -1_000, reloj);
        String token = vencido.generarToken(usuario(7L, 3L, Rol.DUENO));

        assertThat(jwtService.leerToken(token)).isEmpty();
    }

    @Test
    void basuraNoEsUnToken() {
        assertThat(jwtService.leerToken("no-es-un-jwt")).isEmpty();
        assertThat(jwtService.leerTokenTotp("no-es-un-jwt")).isEmpty();
        assertThat(jwtService.leerTokenTotp(null)).isEmpty();
    }

    @Test
    void rechazaUnaClaveCorta() {
        assertThatThrownBy(() -> new JwtService("corta", 60_000, reloj)).isInstanceOf(IllegalStateException.class);
    }

    // ---- segundo paso del super admin (totp-super-admin) ----

    @Test
    @DisplayName("S5a: el tokenTotp lleva solo el usuario y su tipo, vence a los 5 minutos del reloj y no sirve como sesión")
    void elTokenTotpEsOtroToken() {
        String token = jwtService.generarTokenTotp(usuario(1L, null, Rol.SUPER_ADMIN));

        Claims claims = Jwts.parser().verifyWith(CLAVE).clock(() -> Date.from(reloj.instant())).build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("tipo", String.class)).isEqualTo("totp");
        assertThat(claims).doesNotContainKeys("rol", "email", "ferreteriaId", "segundoFactor");
        assertThat(Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(jwtService.leerToken(token)).isEmpty();
        assertThat(jwtService.leerTokenTotp(token)).hasValue(1L);
        reloj.avanzar(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(jwtService.leerTokenTotp(token)).hasValue(1L);
        reloj.avanzar(Duration.ofSeconds(2));
        assertThat(jwtService.leerTokenTotp(token)).isEmpty();
    }

    @Test
    @DisplayName("S5a: la sesión no se lee como tokenTotp, y un token con cualquier tipo no se lee como sesión")
    void cadaTokenSoloSirveParaLoSuyo() {
        String sesion = jwtService.generarToken(usuario(7L, 3L, Rol.DUENO));
        String conTipo = Jwts.builder().subject("7").claim("email", "x@demo.local").claim("rol", "DUENO")
                .claim("ferreteriaId", 3L).claim("tipo", "otro").expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(CLAVE).compact();

        assertThat(jwtService.leerTokenTotp(sesion)).isEmpty();
        assertThat(jwtService.leerToken(conTipo)).isEmpty();
    }

    @Test
    @DisplayName("S5b: un token de SUPER_ADMIN sin el segundo factor (como los de antes del deploy) es inválido")
    void superAdminSinSegundoFactor() {
        String deAntes = Jwts.builder().subject("1").claim("email", "admin@demo.local").claim("rol", "SUPER_ADMIN")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(CLAVE).compact();

        assertThat(jwtService.leerToken(deAntes)).isEmpty();
    }

    @Test
    @DisplayName("S5b: la sesión del super admin sale solo con el segundo factor, y la de un Dueño o Vendedor sin él")
    void cadaRolConSuToken() {
        assertThatThrownBy(() -> jwtService.generarToken(usuario(1L, null, Rol.SUPER_ADMIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtService.generarTokenConSegundoFactor(usuario(7L, 3L, Rol.DUENO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Un Dueño o un Vendedor salen de la fábrica. Un SUPER_ADMIN no se crea por Java (es el dato semilla), así que acá se
     * arma como lo lee Hibernate: sin pasar por los setters ni por el constructor de alta.
     */
    private static Usuario usuario(Long id, Long ferreteriaId, Rol rol) {
        Usuario usuario = rol == Rol.SUPER_ADMIN
                ? superAdminDeSemilla()
                : Usuario.nuevo(ferreteriaId, "x@demo.local", "hash", "X", rol);
        ReflectionTestUtils.setField(usuario, "id", id);
        return usuario;
    }

    private static Usuario superAdminDeSemilla() {
        Usuario admin = new UsuarioDeSemilla();
        ReflectionTestUtils.setField(admin, "email", "x@demo.local");
        ReflectionTestUtils.setField(admin, "passwordHash", "hash");
        ReflectionTestUtils.setField(admin, "nombre", "X");
        ReflectionTestUtils.setField(admin, "rol", Rol.SUPER_ADMIN);
        return admin;
    }

    /** Llega al constructor sin argumentos de la entidad, el mismo que usa Hibernate al leer el dato semilla. */
    private static final class UsuarioDeSemilla extends Usuario {
        private UsuarioDeSemilla() {
            super();
        }
    }
}
