package com.ferreteria.auth.service;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.security.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Emite y valida el JWT. Claims: sub (userId), email, rol y ferreteriaId (ausente para SUPER_ADMIN).
 * La ferreteria del request sale de aca: nunca de un header o parametro que mande el cliente.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMillis) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret tiene que tener al menos 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMillis = expirationMillis;
    }

    public String generarToken(Usuario usuario) {
        long ahora = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(usuario.getId()))
                .claim("email", usuario.getEmail())
                .claim("rol", usuario.getRol().name())
                .claim("ferreteriaId", usuario.getFerreteriaId())
                .issuedAt(new Date(ahora))
                .expiration(new Date(ahora + expirationMillis))
                .signWith(key)
                .compact();
    }

    /** Devuelve el usuario del token, o vacio si el token es invalido o esta vencido. */
    public Optional<AuthUser> leerToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Number ferreteriaId = claims.get("ferreteriaId", Number.class);
            return Optional.of(new AuthUser(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    Rol.valueOf(claims.get("rol", String.class)),
                    ferreteriaId == null ? null : ferreteriaId.longValue()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
