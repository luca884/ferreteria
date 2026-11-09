package com.ferreteria.auth.service;

import com.ferreteria.auth.dto.AltaTotpResponse;
import com.ferreteria.auth.dto.LoginResponse;
import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.totp.Base32;
import com.ferreteria.auth.totp.CifradorTotp;
import com.ferreteria.auth.totp.QrTotp;
import com.ferreteria.auth.totp.Totp;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.tenant.UnscopedTenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El segundo paso del login del super admin (totp-super-admin): el código de 6 números de Google Authenticator (RFC 6238,
 * {@link Totp}). Los dos endpoints de {@code /api/auth/totp/} son públicos y los autoriza el {@code tokenTotp} que dio el
 * login con la contraseña bien ({@link JwtService#generarTokenTotp}).
 *
 * <ul>
 *   <li><b>Alta</b> (S2): la primera vez se genera un secreto de 20 bytes ({@link SecureRandom}), se guarda cifrado
 *       ({@link CifradorTotp}, S5e) y se devuelven la clave, el {@code otpauth://} y su QR. Mientras no se confirme se
 *       devuelve el mismo; activado, el alta da 409 y el secreto no vuelve a salir por la API.</li>
 *   <li><b>Código</b> (S1): se acepta el del paso actual, el anterior y el siguiente; el primero bueno activa el alta. Un
 *       código usado, o uno de un paso anterior al último usado, no vale (S5c: una sola sentencia condicional en la base).
 *       Los códigos mal tienen el límite de {@link LimiteDeIntentos} por usuario (S5d).</li>
 * </ul>
 * Ni el secreto, ni la clave, ni la URI, ni los códigos van al log.
 */
@Service
@UnscopedTenantAccess(reason = "El super admin no tiene ferreteria: el segundo paso de su login lo busca por el id de su "
        + "tokenTotp, antes de que haya una sesion y un TenantContext.")
public class SegundoFactorService {

    static final String YA_ACTIVADO = "El código de la app ya está activado.";
    static final String SIN_ALTA = "Primero escaneá el código QR.";
    /** «Ferretería» en UTF-8 con %-encoding: el emisor que muestra Google Authenticator. */
    static final String EMISOR = "Ferreter%C3%ADa";

    private static final int LARGO_SECRETO = 20;
    private static final Pattern SEIS_NUMEROS = Pattern.compile("[0-9]{6}");

    private final UsuarioRepository usuarioRepository;
    private final JwtService jwtService;
    private final CifradorTotp cifrador;
    private final LimiteDeIntentos limiteDeIntentos;
    private final Clock reloj;
    private final SecureRandom azar = new SecureRandom();

    public SegundoFactorService(UsuarioRepository usuarioRepository, JwtService jwtService, CifradorTotp cifrador,
                                LimiteDeIntentos limiteDeIntentos, Clock reloj) {
        this.usuarioRepository = usuarioRepository;
        this.jwtService = jwtService;
        this.cifrador = cifrador;
        this.limiteDeIntentos = limiteDeIntentos;
        this.reloj = reloj;
    }

    /**
     * El alta del código de la app: la clave, la URI y el QR del secreto pendiente (el mismo hasta activarlo). Toma la fila
     * del usuario: dos altas a la vez guardan un solo secreto.
     */
    @Transactional
    public AltaTotpResponse alta(String tokenTotp) {
        long id = idDelToken(tokenTotp);
        Usuario usuario = usuarioRepository.bloquearParaAltaTotp(id).filter(SegundoFactorService::esSuperAdminActivo)
                .orElseThrow(SegundoPasoRechazadoException::totpVencido);
        if (usuario.isTotpActivado()) {
            throw new ConflictException(YA_ACTIVADO);
        }
        byte[] guardado = usuario.getTotpSecreto();
        if (guardado == null) {
            byte[] nuevo = new byte[LARGO_SECRETO];
            azar.nextBytes(nuevo);
            guardado = cifrador.cifrar(id, nuevo);
            Arrays.fill(nuevo, (byte) 0);
            if (usuarioRepository.guardarSecretoTotp(id, guardado) != 1) {
                throw new IllegalStateException("No se pudo guardar el secreto del código de la app del usuario " + id);
            }
        }
        byte[] secreto = cifrador.descifrar(id, guardado);
        String clave = Base32.codificar(secreto);
        Arrays.fill(secreto, (byte) 0);
        String uri = "otpauth://totp/" + EMISOR + ":" + etiqueta(usuario.getEmail()) + "?secret=" + clave
                + "&issuer=" + EMISOR + "&algorithm=SHA1&digits=" + Totp.DIGITOS + "&period=" + Totp.PERIODO;
        return new AltaTotpResponse(clave, uri, QrTotp.modulos(uri));
    }

    /**
     * El código de la app: si vale, la sesión del super admin (y el alta pendiente queda activada). El orden de los
     * rechazos: el código mal escrito (400, sin gastar un intento), el tokenTotp (401), el alta sin pedir (409), el límite de
     * intentos (429) y el código (401).
     */
    @Transactional
    public LoginResponse verificar(String tokenTotp, String codigo) {
        if (codigo == null || !SEIS_NUMEROS.matcher(codigo).matches()) {
            throw SegundoPasoRechazadoException.codigoMalEscrito();
        }
        long id = idDelToken(tokenTotp);
        Usuario usuario = usuarioRepository.findById(id).filter(SegundoFactorService::esSuperAdminActivo)
                .orElseThrow(SegundoPasoRechazadoException::totpVencido);
        byte[] guardado = usuario.getTotpSecreto();
        if (guardado == null) {
            throw new ConflictException(SIN_ALTA);
        }
        limiteDeIntentos.reservarCodigoDeLaApp(id);
        byte[] secreto = cifrador.descifrar(id, guardado);
        OptionalLong paso = Totp.pasoDelCodigo(secreto, codigo, Totp.paso(reloj.instant()));
        Arrays.fill(secreto, (byte) 0);
        if (paso.isEmpty() || usuarioRepository.usarPasoTotp(id, guardado, paso.getAsLong(), LocalDateTime.now(reloj)) != 1) {
            throw SegundoPasoRechazadoException.codigoIncorrecto();
        }
        limiteDeIntentos.exitoCodigoDeLaApp(id);
        return entrar(usuario);
    }

    /**
     * La sesión del super admin. Es el único lugar que la emite ({@code SesionDelSuperAdminArchTest}) y en producción se
     * llega solo con un código bueno ({@link #verificar}). Es público para el helper de los tests de integración, que entran
     * como super admin sin probar el código (S9).
     */
    public LoginResponse entrar(Usuario usuario) {
        return LoginResponse.sesion(jwtService.generarTokenConSegundoFactor(usuario), AuthService.toSesion(usuario, null));
    }

    private long idDelToken(String tokenTotp) {
        return jwtService.leerTokenTotp(tokenTotp).orElseThrow(SegundoPasoRechazadoException::totpVencido);
    }

    private static boolean esSuperAdminActivo(Usuario usuario) {
        return usuario.isActivo() && usuario.getRol() == Rol.SUPER_ADMIN;
    }

    /**
     * El email en la etiqueta del {@code otpauth://}: un email común queda tal cual (letras, números, {@code -._~} y
     * {@code @}); cualquier otro byte de su UTF-8 va como {@code %XX}, para que la URI no se corte.
     */
    static String etiqueta(String email) {
        StringBuilder etiqueta = new StringBuilder();
        for (byte b : email.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean talCual = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~@".indexOf(c) >= 0;
            if (talCual) {
                etiqueta.append((char) c);
            } else {
                etiqueta.append('%').append(String.format("%02X", c));
            }
        }
        return etiqueta.toString();
    }
}
