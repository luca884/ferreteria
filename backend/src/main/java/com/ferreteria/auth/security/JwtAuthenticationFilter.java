package com.ferreteria.auth.security;

import com.ferreteria.auth.service.JwtService;
import com.ferreteria.auth.service.SesionVigente;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Lee "Authorization: Bearer ..." y, si el token es valido, autentica el request.
 * Un token invalido no corta aca: el request sigue sin autenticacion y Spring Security
 * responde 401 en las rutas protegidas (via el entry point de SecurityConfig). Lo mismo con un token valido
 * cuya sesion ya no esta vigente (usuario dado de baja, ferreteria suspendida o rol cambiado).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String MENSAJE_CAMBIAR_CONTRASENA = "Cambiá tu contraseña para seguir.";

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
            Optional<AuthUser> user = jwtService.leerToken(header.substring(7));
            SesionVigente.Estado estado = user.map(sesionVigente::estado).orElse(SesionVigente.Estado.CERRADA);
            if (estado == SesionVigente.Estado.DEBE_CAMBIAR_CONTRASENA && !permitidoSinCambiar(request)) {
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":403,\"message\":\"" + MENSAJE_CAMBIAR_CONTRASENA + "\"}");
                return;
            }
            if (estado != SesionVigente.Estado.CERRADA) {
                var auth = new UsernamePasswordAuthenticationToken(
                        user.get(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.get().rol().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Hallazgo 5 de la revision de seguridad: con la contraseña que le dio otra persona (alta o reseteo), el usuario solo
     * puede ver su sesion, cambiarla o volver a entrar. El frontend ya lo lleva a «Mi contraseña» (contrasenaGuard); esto
     * lo exige tambien a quien use la API directo con ese token.
     */
    private static boolean permitidoSinCambiar(HttpServletRequest request) {
        String metodo = request.getMethod();
        String ruta = request.getRequestURI();
        return ("GET".equals(metodo) && "/api/auth/me".equals(ruta))
                || ("PUT".equals(metodo) && "/api/auth/password".equals(ruta))
                || ("POST".equals(metodo) && "/api/auth/login".equals(ruta))
                || !ruta.startsWith("/api/");
    }
}
