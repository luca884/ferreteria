package com.ferreteria.auth.service;

import com.ferreteria.auth.dto.LoginRequest;
import com.ferreteria.auth.dto.LoginResponse;
import com.ferreteria.auth.dto.SesionResponse;
import com.ferreteria.auth.model.PoliticaDeContrasena;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@UnscopedTenantAccess(reason = "El login resuelve al usuario por email antes de conocer su ferreteria, y el cambio de contraseña "
        + "lo busca por el userId del JWT del propio usuario.")
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final FerreteriaRepository ferreteriaRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LimiteDeIntentos limiteDeIntentos;
    /** Hash de una contrasena que nadie tiene: contra el se compara cuando el email no existe. */
    private final String hashSenuelo;

    public AuthService(UsuarioRepository usuarioRepository,
                       FerreteriaRepository ferreteriaRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LimiteDeIntentos limiteDeIntentos) {
        this.usuarioRepository = usuarioRepository;
        this.ferreteriaRepository = ferreteriaRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.limiteDeIntentos = limiteDeIntentos;
        this.hashSenuelo = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Login con límite de intentos por email y por IP ({@link LimiteDeIntentos}): pasado el límite da 429 antes de buscar al
     * usuario y sin gastar el bcrypt. La contraseña correcta borra la racha del email, aunque la ferretería esté suspendida.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String ip) {
        Optional<Usuario> encontrado = usuarioRepository.findByEmailIgnoreCase(request.email().trim());
        // La racha es del email guardado, no del tipeado: la columna (utf8mb4_0900_ai_ci) da por iguales variantes con
        // tildes, ancho completo, invisibles o ß, y con el texto tal cual cada variante tendría su propio límite.
        String clave = encontrado.map(Usuario::getEmail).orElse(request.email());
        limiteDeIntentos.reservar(clave, ip);
        // Siempre un bcrypt, exista o no el email y este o no activo: el tiempo de respuesta no revela cuentas.
        boolean coincide = passwordEncoder.matches(request.password(),
                encontrado.map(Usuario::getPasswordHash).orElse(hashSenuelo));
        Usuario usuario = encontrado
                .filter(u -> coincide)
                .filter(Usuario::isActivo)
                .orElseThrow(CredencialesInvalidasException::new);
        limiteDeIntentos.exito(clave);

        Ferreteria ferreteria = null;
        if (usuario.getFerreteriaId() != null) {
            // Misma respuesta que una contrasena mala: no revela si la ferreteria existe.
            ferreteria = ferreteriaRepository.findById(usuario.getFerreteriaId())
                    .orElseThrow(CredencialesInvalidasException::new);
            // Llegar hasta aca ya probo el email y la contrasena y que el usuario esta activo: solo ahi se avisa.
            if (!ferreteria.isActiva()) {
                throw new FerreteriaSuspendidaException();
            }
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

    /**
     * Cada usuario cambia la propia contraseña pidiendo la actual. Una actual incorrecta es un error del campo (400), nunca
     * un 401: el front desloguea ante cualquier 401. Las actuales incorrectas tienen el límite de {@link LimiteDeIntentos}:
     * pasado, 429 sin gastar el bcrypt.
     *
     * <p>Deuda conocida (R-013): los tokens ya emitidos siguen valiendo hasta que vencen (12 horas); cambiar la contraseña
     * no los invalida.
     */
    @Transactional
    public void cambiarContrasena(AuthUser authUser, String actual, String nueva) {
        Usuario usuario = usuarioRepository.findById(authUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        limiteDeIntentos.reservarCambioDeContrasena(authUser.userId());
        if (!passwordEncoder.matches(actual, usuario.getPasswordHash())) {
            throw new DatosInvalidosException(Map.of("passwordActual", "La contraseña actual no es correcta."));
        }
        limiteDeIntentos.exitoCambioDeContrasena(authUser.userId());
        // Con la marca, repetir la que eligió otra persona no es cambiarla: la marca seguiría apagándose sin rotar nada.
        if (usuario.isDebeCambiarContrasena() && nueva.equals(actual)) {
            throw new DatosInvalidosException(Map.of("passwordNueva", "Elegí una contraseña distinta de la que te dieron."));
        }
        PoliticaDeContrasena.validar("passwordNueva", nueva);
        usuario.cambiarPropiaContrasena(passwordEncoder.encode(nueva));
    }

    private SesionResponse toSesion(Usuario usuario, Ferreteria ferreteria) {
        return new SesionResponse(
                usuario.getId(),
                usuario.getNombre(),
                usuario.getEmail(),
                usuario.getRol(),
                usuario.getFerreteriaId(),
                ferreteria == null ? null : ferreteria.getRazonSocial(),
                usuario.getTopeDescuento(),
                usuario.isDebeCambiarContrasena());
    }
}
