package com.ferreteria.auth.service;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.security.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Emite y valida el JWT. Claims: sub (userId), email, rol y ferreteriaId (ausente para SUPER_ADMIN).
 * La ferreteria del request sale de aca: nunca de un header o parametro que mande el cliente.
 *
 * <p>totp-super-admin: la sesión de un SUPER_ADMIN lleva además {@code segundoFactor: true} y sale solo de
 * {@link #generarTokenConSegundoFactor}, después de un código bueno; un token de SUPER_ADMIN sin esa marca (los de antes del
 * deploy) no es una sesión (S5b). Entre la contraseña y el código, el super admin tiene el {@code tokenTotp}: otro token,
 * con {@code tipo: totp}, solo el id del usuario y 5 minutos del reloj inyectado, que lee solo {@link #leerTokenTotp}; ningún
 * token con {@code tipo} es una sesión (S5a).
 */
@Service
public class JwtService {

    static final String TIPO = "tipo";
    static final String TIPO_TOTP = "totp";
    static final String SEGUNDO_FACTOR = "segundoFactor";
    /** Lo que dura el tokenTotp: el tiempo para escanear el QR o mirar el código en la app. */
    static final Duration DURACION_TOKEN_TOTP = Duration.ofMinutes(5);

    private final SecretKey key;
    private final long expirationMillis;
    private final Clock reloj;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMillis,
                      Clock reloj) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret tiene que tener al menos 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMillis = expirationMillis;
        this.reloj = reloj;
    }

    /** La sesión de un Dueño o un Vendedor. La de un SUPER_ADMIN sale solo de {@link #generarTokenConSegundoFactor}. */
    public String generarToken(Usuario usuario) {
        if (usuario.getRol() == Rol.SUPER_ADMIN) {
            throw new IllegalArgumentException("La sesión del super admin sale solo después del código de la app.");
        }
        return sesion(usuario).compact();
    }

    /**
     * La sesión de un SUPER_ADMIN, con la marca del segundo factor. La llama solo {@code SegundoFactorService}, después de
     * un código bueno (lo exige {@code SesionDelSuperAdminArchTest}).
     */
    public String generarTokenConSegundoFactor(Usuario usuario) {
        if (usuario.getRol() != Rol.SUPER_ADMIN) {
            throw new IllegalArgumentException("Solo el super admin entra con el código de la app.");
        }
        return sesion(usuario).claim(SEGUNDO_FACTOR, true).compact();
    }

    /** El token del segundo paso del login del super admin: el id del usuario y el tipo, por 5 minutos del reloj. */
    public String generarTokenTotp(Usuario usuario) {
        Instant ahora = reloj.instant();
        return Jwts.builder()
                .subject(String.valueOf(usuario.getId()))
                .claim(TIPO, TIPO_TOTP)
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(DURACION_TOKEN_TOTP)))
                .signWith(key)
                .compact();
    }

    private JwtBuilder sesion(Usuario usuario) {
        long ahora = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(usuario.getId()))
                .claim("email", usuario.getEmail())
                .claim("rol", usuario.getRol().name())
                .claim("ferreteriaId", usuario.getFerreteriaId())
                .issuedAt(new Date(ahora))
                .expiration(new Date(ahora + expirationMillis))
                .signWith(key);
    }

    /**
     * Devuelve el usuario del token, o vacio si el token es invalido o esta vencido, si tiene {@code tipo} (el tokenTotp no
     * es una sesión) o si es de un SUPER_ADMIN y no pasó por el código de la app.
     */
    public Optional<AuthUser> leerToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (claims.containsKey(TIPO)) {
                return Optional.empty();
            }
            Rol rol = Rol.valueOf(claims.get("rol", String.class));
            if (rol == Rol.SUPER_ADMIN && !Boolean.TRUE.equals(claims.get(SEGUNDO_FACTOR, Boolean.class))) {
                return Optional.empty();
            }
            Number ferreteriaId = claims.get("ferreteriaId", Number.class);
            return Optional.of(new AuthUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    rol,
                    ferreteriaId == null ? null : ferreteriaId.longValue()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** El id del usuario de un tokenTotp vigente según el reloj inyectado, o vacío si no es uno o venció. */
    public Optional<Long> leerTokenTotp(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(reloj.instant())).build()
                    .parseSignedClaims(token).getPayload();
            if (!TIPO_TOTP.equals(claims.get(TIPO, String.class))) {
                return Optional.empty();
            }
            return Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
