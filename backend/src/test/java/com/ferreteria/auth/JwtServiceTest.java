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
     * Los campos que no tienen setter: la entidad solo los expone por getter, así que el fixture los escribe por reflejo.
     * Van como constantes para que un renombre se cambie en un solo lugar y no rompa el test en silencio.
     */
    private static final String CAMPO_ID = "id";
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_HASH = "passwordHash";
    private static final String CAMPO_NOMBRE = "nombre";
    private static final String CAMPO_ROL = "rol";

    /**
     * Un Dueño o un Vendedor salen de la fábrica {@code Usuario.nuevo}: con la ferretería que se les pasa y la marca de
     * cambiar la contraseña <b>puesta</b>. Un SUPER_ADMIN no se crea por Java, es el dato semilla: no pertenece a ninguna
     * ferretería (por eso {@code ferreteriaId} se ignora en esa rama) y su marca queda en {@code false}, el valor por
     * defecto del campo, que la semilla SQL nunca toca.
     *
     * <p>Estos tests miran los claims del token (id y rol), no el aislamiento por ferretería: la ferretería y el usuario
     * quedan ligados por el {@code ferreteriaId} que se pasa, y el resto del vínculo es de los tests de integración.
     */
    private static Usuario usuario(Long id, Long ferreteriaId, Rol rol) {
        Usuario usuario;
        if (rol == Rol.SUPER_ADMIN) {
            assertThat(ferreteriaId).isNull();
            usuario = superAdminDeSemilla();
        } else {
            usuario = Usuario.nuevo(ferreteriaId, "x@demo.local", "hash", "X", rol);
        }
        ReflectionTestUtils.setField(usuario, CAMPO_ID, id);
        return usuario;
    }

    private static Usuario superAdminDeSemilla() {
        Usuario admin = new UsuarioDeSemilla();
        ReflectionTestUtils.setField(admin, CAMPO_EMAIL, "x@demo.local");
        ReflectionTestUtils.setField(admin, CAMPO_HASH, "hash");
        ReflectionTestUtils.setField(admin, CAMPO_NOMBRE, "X");
        ReflectionTestUtils.setField(admin, CAMPO_ROL, Rol.SUPER_ADMIN);
        return admin;
    }

    /** Llega al constructor sin argumentos de la entidad, el mismo que usa Hibernate al leer el dato semilla. */
    private static final class UsuarioDeSemilla extends Usuario {
        private UsuarioDeSemilla() {
            super();
        }
    }
}
