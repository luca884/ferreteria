package com.ferreteria.auth.security;

import com.ferreteria.auth.service.JwtService;
import com.ferreteria.auth.service.SesionVigente;
import com.ferreteria.tenant.TenantFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   SesionVigente sesionVigente) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, sesionVigente);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        // El rol se exige ANTES de leer el multipart o el cuerpo: un Vendedor recibe 403 aunque
                        // mande datos invalidos. El @PreAuthorize de cada metodo queda como defensa en profundidad.
                        // Vale para la importacion de listas (CU-04) y la actualizacion masiva de precios (CU-05).
                        .requestMatchers("/api/importaciones/**").hasRole("DUENO")
                        .requestMatchers("/api/actualizacion-masiva/**").hasRole("DUENO")
                        // La configuracion de cotizacion de la ferreteria es solo del Dueño.
                        .requestMatchers("/api/configuracion-cotizacion/**").hasRole("DUENO")
                        // La configuracion de precios (margen por defecto y redondeo) tambien, leerla y cambiarla (H07).
                        .requestMatchers("/api/configuracion-precios/**").hasRole("DUENO")
                        // Los datos fiscales de la ferreteria (CU-01 paso 1) los lee y los edita solo el Dueño: el
                        // Vendedor y el super admin reciben 403 aunque el cuerpo no valide.
                        .requestMatchers("/api/datos-fiscales/**").hasRole("DUENO")
                        // «Recalcular ahora» tambien: el GET de la cotizacion (/api/cotizacion) sigue siendo de todos.
                        .requestMatchers(HttpMethod.POST, "/api/cotizacion/recalculo").hasRole("DUENO")
                        // Editar los ajustes de un articulo es solo del Dueño; el 403 va antes de leer el cuerpo.
                        .requestMatchers(HttpMethod.PUT, "/api/articulos/*/ajustes").hasRole("DUENO")
                        // Cobrar la cuenta corriente, ver un recibo y los pendientes de cobro son solo del Dueño (CC-028): el
                        // Vendedor recibe 403 aunque el cuerpo no valide, y sin que se consuma un número de recibo.
                        .requestMatchers("/api/cobranzas/**").hasRole("DUENO")
                        .requestMatchers("/api/recibos/**").hasRole("DUENO")
                        .requestMatchers(HttpMethod.GET, "/api/clientes/*/cuenta-corriente/pendientes").hasRole("DUENO")
                        // El extracto de la cuenta corriente (CC-072, CC-101) también: el 403 va antes de leer los parametros. Sin
                        // restricción de método a propósito: con solo GET, un HEAD del Vendedor con un parámetro inválido llegaba al
                        // binding y daba 400 en vez de 403 (el HEAD de Spring MVC es el GET). El Dueño con otro método recibe 405.
                        .requestMatchers("/api/clientes/*/cuenta-corriente/extracto").hasRole("DUENO")
                        // Ajustar la cuenta corriente (Recargo o Descuento, GDS-20) es solo del Dueño: el Vendedor recibe 403 aunque
                        // el cuerpo no valide, y sin que se consuma un número de ajuste.
                        .requestMatchers(HttpMethod.POST, "/api/clientes/*/cuenta-corriente/ajustes").hasRole("DUENO")
                        // Gestionar los usuarios de la ferretería (CU-02) es solo del Dueño: el Vendedor y el super admin
                        // reciben 403 aunque el cuerpo no valide.
                        .requestMatchers("/api/usuarios/**").hasRole("DUENO")
                        // Las ferreterias de la plataforma (alta, suspension, reset) son solo del super admin: el Dueño y el
                        // Vendedor reciben 403 aunque el cuerpo no valide.
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"status\":401,\"message\":\"No autenticado\"}");
                        })
                        // Mismo cuerpo que el 403 de @PreAuthorize (GlobalExceptionHandler.accessDenied).
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"status\":403,\"message\":\"No tenés permiso para hacer esto\"}");
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // El front corre en otro origen y lee de aca el nombre de los archivos que baja (exportacion de articulos).
        config.setExposedHeaders(List.of("Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
