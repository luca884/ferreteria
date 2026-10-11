package com.ferreteria.tenant;

import com.ferreteria.auth.security.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Corre despues de JwtAuthenticationFilter: toma la ferreteria del usuario autenticado
 * y la pone en {@link TenantContext}. Siempre limpia el contexto al final del request.
 * No es un @Component a proposito: se instancia en SecurityConfig para que Spring Boot
 * no lo registre dos veces como filtro del servlet.
 */
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthUser user && user.ferreteriaId() != null) {
                TenantContext.setCurrentFerreteriaId(user.ferreteriaId());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
