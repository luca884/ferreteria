package com.ferreteria.auth.security;

import com.ferreteria.auth.service.JwtService;
import com.ferreteria.auth.service.SesionVigente;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lee "Authorization: Bearer ..." y, si el token es valido, autentica el request.
 * Un token invalido no corta aca: el request sigue sin autenticacion y Spring Security
 * responde 401 en las rutas protegidas (via el entry point de SecurityConfig). Lo mismo con un token valido
 * cuya sesion ya no esta vigente (usuario dado de baja, ferreteria suspendida o rol cambiado).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SesionVigente sesionVigente;

    public JwtAuthenticationFilter(JwtService jwtService, SesionVigente sesionVigente) {
        this.jwtService = jwtService;
        this.sesionVigente = sesionVigente;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.leerToken(header.substring(7)).filter(sesionVigente::vigente).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.rol().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        filterChain.doFilter(request, response);
    }
}
