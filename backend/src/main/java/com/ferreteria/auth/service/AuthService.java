package com.ferreteria.auth.service;

import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.dto.LoginResponse;
import com.ferreteria.auth.dto.SesionResponse;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@UnscopedTenantAccess(reason = "El login resuelve al usuario por email antes de conocer su ferreteria.")
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final FerreteriaRepository ferreteriaRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Hash de una contrasena que nadie tiene: contra el se compara cuando el email no existe. */
    private final String hashSenuelo;

    public AuthService(UsuarioRepository usuarioRepository,
                       FerreteriaRepository ferreteriaRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.ferreteriaRepository = ferreteriaRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.hashSenuelo = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<Usuario> encontrado = usuarioRepository.findByEmailIgnoreCase(request.email().trim());
        // Siempre un bcrypt, exista o no el email y este o no activo: el tiempo de respuesta no revela cuentas.
        boolean coincide = passwordEncoder.matches(request.password(),
                encontrado.map(Usuario::getPasswordHash).orElse(hashSenuelo));
        Usuario usuario = encontrado
                .filter(u -> coincide)
                .filter(Usuario::isActivo)
                .orElseThrow(CredencialesInvalidasException::new);

        Ferreteria ferreteria = null;
        if (usuario.getFerreteriaId() != null) {
            ferreteria = ferreteriaRepository.findById(usuario.getFerreteriaId())
                    .filter(Ferreteria::isActiva)
                    // Misma respuesta que una contrasena mala: no revela si la ferreteria existe.
                    .orElseThrow(CredencialesInvalidasException::new);
        }
        return new LoginResponse(jwtService.generarToken(usuario), toSesion(usuario, ferreteria));
    }

    @Transactional(readOnly = true)
    public SesionResponse sesionActual(AuthUser authUser) {
        Usuario usuario = usuarioRepository.findById(authUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        Ferreteria ferreteria = usuario.getFerreteriaId() == null
                ? null
                : ferreteriaRepository.findById(usuario.getFerreteriaId()).orElse(null);
        return toSesion(usuario, ferreteria);
    }

    private SesionResponse toSesion(Usuario usuario, Ferreteria ferreteria) {
        return new SesionResponse(
                usuario.getId(),
                usuario.getNombre(),
                usuario.getEmail(),
                usuario.getRol(),
                usuario.getFerreteriaId(),
                ferreteria == null ? null : ferreteria.getRazonSocial());
    }
}
