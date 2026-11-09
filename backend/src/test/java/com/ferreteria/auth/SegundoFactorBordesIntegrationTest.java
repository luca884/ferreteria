package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.auth.totp.Totp;
import com.ferreteria.support.CodigoDeLaApp;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/**
 * Los bordes del login del super admin con el código de la app (totp-super-admin, S1 a S5 y S8) que probó la verificación
 * independiente (T4): tokens armados a mano, la ventana de pasos, lo que gasta intentos, pedidos simultáneos, el usuario
 * dado de baja, el log, la forma de las respuestas y el reset por base con el alta pendiente.
 */
@ExtendWith(OutputCaptureExtension.class)
class SegundoFactorBordesIntegrationTest extends SegundoFactorTestBase {

    /** Los campos de {@code LoginResponse}: el login y la verificación no devuelven otros. */
    private static final List<String> CAMPOS_DEL_LOGIN = List.of("token", "sesion", "segundoPaso", "tokenTotp");

    @Value("${app.jwt.secret}")
    String secretoJwt;

    /** S5a: el tokenTotp no sirve como Bearer en ningún endpoint, ni en el alta sin el tokenTotp en el cuerpo. */
    @Test
    void elTokenTotpNoSirveComoBearer() throws Exception {
        String tokenTotp = tokenTotp(crearSuperAdmin());

        for (String url : new String[] {"/api/auth/me", "/api/admin/ferreterias", "/api/rubros", "/api/articulos",
                "/api/ventas"}) {
            int estado = getResult(url, tokenTotp).andReturn().getResponse().getStatus();
            assertThat(estado).as("GET " + url).isEqualTo(401);
        }
        mvc.perform(post("/api/auth/totp/alta").header("Authorization", "Bearer " + tokenTotp)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
    }

    /** S5b: tokens firmados con la clave del JWT pero sin el segundo factor de verdad, o de un Dueño, no entran. */
    @Test
    void losTokensArmadosConLaFirmaNoEntran() throws Exception {
        String email = crearSuperAdmin();
        long id = id(email);
        SecretKey firma = Keys.hmacShaKeyFor(secretoJwt.getBytes(StandardCharsets.UTF_8));
        Date vence = Date.from(Instant.now().plus(Duration.ofHours(1)));
        String segundoFactorComoTexto = Jwts.builder().subject(String.valueOf(id)).claim("email", email)
                .claim("rol", "SUPER_ADMIN").claim("segundoFactor", "true").issuedAt(new Date()).expiration(vence)
                .signWith(firma).compact();
        String conTipoTotp = Jwts.builder().subject(String.valueOf(id)).claim("email", email).claim("rol", "SUPER_ADMIN")
                .claim("segundoFactor", true).claim("tipo", "totp").issuedAt(new Date()).expiration(vence)
                .signWith(firma).compact();
        String segundoFactorFalso = Jwts.builder().subject(String.valueOf(id)).claim("email", email)
                .claim("rol", "SUPER_ADMIN").claim("segundoFactor", false).issuedAt(new Date()).expiration(vence)
                .signWith(firma).compact();

        for (String armado : new String[] {segundoFactorComoTexto, conTipoTotp, segundoFactorFalso}) {
            int estado = getResult("/api/admin/ferreterias", armado).andReturn().getResponse().getStatus();
            assertThat(estado).isEqualTo(401);
        }

        // La sesión de un Dueño como tokenTotp.
        verificar(login("dueno@tornillo.demo"), "123456").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
        // Un tokenTotp bien firmado, con tipo totp, pero de un Dueño (defensa en profundidad).
        String tokenTotpDeUnDueno = Jwts.builder().subject(String.valueOf(id("dueno@tornillo.demo"))).claim("tipo", "totp")
                .issuedAt(Date.from(reloj.instant())).expiration(Date.from(reloj.instant().plusSeconds(200)))
                .signWith(firma).compact();
        alta(tokenTotpDeUnDueno).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
        verificar(tokenTotpDeUnDueno, "123456").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
    }

    /** S3: el código del paso anterior y el del siguiente activan; los de dos pasos atrás o adelante, no. */
    @Test
    void laVentanaEsElPasoAnteriorYElSiguiente() throws Exception {
        long paso = Totp.paso(reloj.instant());

        String conElAnterior = tokenTotp(crearSuperAdmin());
        verificar(conElAnterior, CodigoDeLaApp.paraElPaso(clave(conElAnterior), paso - 1)).andExpect(status().isOk());

        String conElSiguiente = tokenTotp(crearSuperAdmin());
        verificar(conElSiguiente, CodigoDeLaApp.paraElPaso(clave(conElSiguiente), paso + 1)).andExpect(status().isOk());

        String fueraDeLaVentana = crearSuperAdmin();
        String tokenTotp = tokenTotp(fueraDeLaVentana);
        String clave = clave(tokenTotp);
        String dosAtras = CodigoDeLaApp.paraElPaso(clave, paso - 2);
        String dosAdelante = CodigoDeLaApp.paraElPaso(clave, paso + 2);
        Set<String> ventana = Set.of(CodigoDeLaApp.paraElPaso(clave, paso - 1), CodigoDeLaApp.paraElPaso(clave, paso),
                CodigoDeLaApp.paraElPaso(clave, paso + 1));
        // Si por azar coinciden con uno de la ventana, valdrían: ese caso no se prueba.
        if (!ventana.contains(dosAtras)) {
            verificar(tokenTotp, dosAtras).andExpect(status().isUnauthorized());
        }
        if (!ventana.contains(dosAdelante)) {
            verificar(tokenTotp, dosAdelante).andExpect(status().isUnauthorized());
        }
        assertThat(fila(fueraDeLaVentana).get("activado")).as("sin activar").isNull();
    }

    /**
     * S5d: el 409, el 400 y el tokenTotp inválido no gastan intentos; el bloqueo es del usuario, sigue con un tokenTotp
     * nuevo y no alcanza a otro super admin.
     */
    @Test
    void losRechazosQueNoSonCodigosNoGastanIntentosYElLimiteEsPorUsuario() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        for (int i = 0; i < 10; i++) {
            verificar(tokenTotp, "123456").andExpect(status().isConflict());
        }
        String clave = clave(tokenTotp);
        for (int i = 0; i < 20; i++) {
            verificar(tokenTotp, "12a456").andExpect(status().isBadRequest());
        }
        for (int i = 0; i < 20; i++) {
            verificar("basura", codigo(clave)).andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 4; i++) {
            verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized());
        }
        verificar(tokenTotp, codigo(clave)).andExpect(status().isOk());

        String bloqueado = crearSuperAdmin();
        String suTokenTotp = tokenTotp(bloqueado);
        String suClave = clave(suTokenTotp);
        for (int i = 0; i < 5; i++) {
            verificar(suTokenTotp, incorrecto(suClave)).andExpect(status().isUnauthorized());
        }
        Map<String, Object> antes = fila(bloqueado);
        // Con un tokenTotp nuevo sigue bloqueado: el código bueno da 429 y no activa.
        verificar(tokenTotp(bloqueado), codigo(suClave)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.timestamp").exists());
        assertThat(fila(bloqueado)).isEqualTo(antes);

        String otro = crearSuperAdmin();
        String otroTokenTotp = tokenTotp(otro);
        verificar(otroTokenTotp, codigo(clave(otroTokenTotp))).andExpect(status().isOk());
    }

    /** S5c: un código usado, uno incorrecto, uno mal escrito y un alta repetida no cambian la fila del usuario. */
    @Test
    void unRechazoNoCambiaLaFilaDelUsuario() throws Exception {
        String email = crearSuperAdmin();
        String clave = activar(email);
        Map<String, Object> antes = fila(email);
        assertThat(antes.get("activado")).as("activado").isNotNull();
        assertThat(((Number) antes.get("paso")).longValue()).as("el paso del código usado")
                .isEqualTo(Totp.paso(reloj.instant()));
        String tokenTotp = tokenTotp(email);

        verificar(tokenTotp, codigo(clave)).andExpect(status().isUnauthorized());
        verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized());
        verificar(tokenTotp, "abc").andExpect(status().isBadRequest());
        alta(tokenTotp).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is("El código de la app ya está activado.")));

        assertThat(fila(email)).isEqualTo(antes);
    }

    /**
     * S5c y S2: de cinco verificaciones simultáneas con el mismo código entra una sola, y cuatro altas simultáneas dan la
     * misma clave.
     */
    @Test
    void cincoVerificacionesYCuatroAltasSimultaneas() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String elCodigo = codigo(clave(tokenTotp));
        String nuevo = crearSuperAdmin();
        String tokenTotpNuevo = tokenTotp(nuevo);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Integer> estados = new ArrayList<>();
        Set<String> altas = new HashSet<>();
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> verificaciones = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                verificaciones.add(pool.submit(() -> {
                    largada.await();
                    return verificar(tokenTotp, elCodigo).andReturn().getResponse().getStatus();
                }));
            }
            largada.countDown();
            for (Future<Integer> verificacion : verificaciones) {
                estados.add(verificacion.get(60, TimeUnit.SECONDS));
            }

            CountDownLatch largadaDeLasAltas = new CountDownLatch(1);
            List<Future<String>> pedidosDeAlta = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                pedidosDeAlta.add(pool.submit(() -> {
                    largadaDeLasAltas.await();
                    var respuesta = alta(tokenTotpNuevo).andReturn().getResponse();
                    return respuesta.getStatus() + ":" + (respuesta.getStatus() == 200
                            ? objectMapper.readTree(respuesta.getContentAsString()).get("clave").asText()
                            : respuesta.getContentAsString());
                }));
            }
            largadaDeLasAltas.countDown();
            for (Future<String> pedido : pedidosDeAlta) {
                altas.add(pedido.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(estados.stream().filter(estado -> estado == 200).count()).isEqualTo(1);
        assertThat(estados.stream().filter(estado -> estado == 401).count()).isEqualTo(4);
        assertThat(altas).hasSize(1);
        assertThat(altas.iterator().next()).startsWith("200:");
    }

    /** S1 y S5: un super admin dado de baja no pasa el código, y su sesión de antes deja de entrar. */
    @Test
    void unSuperAdminDadoDeBajaNoEntra() throws Exception {
        String email = crearSuperAdmin();
        String clave = activar(email);
        reloj.avanzar(Duration.ofSeconds(30));
        String sesion = cuerpo(verificar(tokenTotp(email), codigo(clave)).andExpect(status().isOk()))
                .get("token").asText();
        reloj.avanzar(Duration.ofSeconds(30));
        String tokenTotp = tokenTotp(email);

        jdbc.update("update usuario set activo = false where email = ?", email);

        verificar(tokenTotp, codigo(clave)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
        getResult("/api/admin/ferreterias", sesion).andExpect(status().isUnauthorized());
    }

    /** S5e: ni la clave, ni el uri, ni los códigos, ni los tokens van al log, ni siquiera con el aviso del bloqueo. */
    @Test
    void elLogNoLlevaSecretosNiCodigosNiTokens(CapturedOutput salida) throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        JsonNode alta = cuerpo(alta(tokenTotp).andExpect(status().isOk()));
        String clave = alta.get("clave").asText();
        String bueno = codigo(clave);
        String malo = incorrecto(clave);
        for (int i = 0; i < 6; i++) {
            verificar(tokenTotp, malo);
        }
        reloj.avanzar(Duration.ofMinutes(1));
        // El bloqueo de 1 minuto ya terminó, y el código bueno de hace un minuto es de dos pasos atrás.
        verificar(tokenTotp, bueno).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("CODIGO_INCORRECTO")));
        reloj.avanzar(Duration.ofSeconds(1));
        String token = cuerpo(verificar(tokenTotp, codigo(clave)).andExpect(status().isOk())).get("token").asText();

        String log = salida.getAll();
        assertThat(log).as("el aviso del bloqueo, con el id del usuario")
                .contains("incorrectos seguidos del usuario " + id(email) + "; se bloquea 1 min");
        assertThat(log).doesNotContain(clave).doesNotContain(alta.get("uri").asText()).doesNotContain(tokenTotp)
                .doesNotContain(token).doesNotContain("\"codigo\"").doesNotContain("codigo=" + bueno)
                .doesNotContain("codigo=" + malo);
    }

    /** S1 y S8: el login del Dueño, el del super admin, la verificación y los errores tienen la forma del contrato. */
    @Test
    void laFormaDelLoginYDeLosErroresEsLaDelContrato() throws Exception {
        JsonNode dueno = cuerpo(intentarLogin("dueno@tornillo.demo", "demo1234").andExpect(status().isOk()));
        assertThat(campos(dueno)).isSubsetOf(CAMPOS_DEL_LOGIN);
        assertThat(dueno.get("token").asText()).isNotBlank();
        assertThat(dueno.get("sesion").isObject()).as("sesion del Dueño").isTrue();
        assertThat(nuloOAusente(dueno, "segundoPaso")).as("segundoPaso del Dueño").isTrue();
        assertThat(nuloOAusente(dueno, "tokenTotp")).as("tokenTotp del Dueño").isTrue();

        JsonNode paso = primerPaso(crearSuperAdmin());
        assertThat(campos(paso)).isSubsetOf(CAMPOS_DEL_LOGIN);
        assertThat(nuloOAusente(paso, "token")).as("token del super admin").isTrue();
        assertThat(nuloOAusente(paso, "sesion")).as("sesion del super admin").isTrue();
        assertThat(paso.get("segundoPaso").asText()).isEqualTo("ALTA");
        String tokenTotp = paso.get("tokenTotp").asText();

        verificar(tokenTotp, "123456").andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is("Primero escaneá el código QR.")))
                .andExpect(jsonPath("$.timestamp").exists());
        String clave = clave(tokenTotp);
        verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.message", is(CODIGO_INCORRECTO)))
                .andExpect(jsonPath("$.errors.motivo", is("CODIGO_INCORRECTO")))
                .andExpect(jsonPath("$.timestamp").exists());
        verificar(tokenTotp, "12").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("El código tiene 6 números.")))
                .andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/auth/totp/verificar").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/totp/alta")).andExpect(status().isUnauthorized());

        JsonNode entrada = cuerpo(verificar(tokenTotp, codigo(clave)).andExpect(status().isOk()));
        assertThat(campos(entrada)).isSubsetOf(CAMPOS_DEL_LOGIN);
        assertThat(entrada.get("token").asText()).isNotBlank();
        assertThat(entrada.get("sesion").get("rol").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(nuloOAusente(entrada, "segundoPaso")).as("segundoPaso de la verificación").isTrue();
        assertThat(nuloOAusente(entrada, "tokenTotp")).as("tokenTotp de la verificación").isTrue();
    }

    /**
     * S4: el UPDATE del README con el alta pendiente invalida la clave vieja; contra un Dueño no toca nada, y el CHECK de la
     * base no le deja un secreto.
     */
    @Test
    void elResetPorBaseConElAltaPendienteYContraUnDueno() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String claveVieja = clave(tokenTotp);

        jdbc.update("UPDATE usuario SET totp_secreto = NULL, totp_activado_en = NULL, totp_ultimo_paso = NULL WHERE email = '"
                + email + "' AND rol = 'SUPER_ADMIN'");

        // Con el tokenTotp de antes del reset ya no hay alta: 409.
        verificar(tokenTotp, codigo(claveVieja)).andExpect(status().isConflict());
        String claveNueva = clave(tokenTotp);
        assertThat(claveNueva).isNotEqualTo(claveVieja);
        verificar(tokenTotp, codigo(claveVieja)).andExpect(status().isUnauthorized());
        verificar(tokenTotp, codigo(claveNueva)).andExpect(status().isOk());

        int filas = jdbc.update("UPDATE usuario SET totp_secreto = NULL, totp_activado_en = NULL, totp_ultimo_paso = NULL "
                + "WHERE email = 'dueno@tornillo.demo' AND rol = 'SUPER_ADMIN'");
        assertThat(filas).isZero();
        assertThatThrownBy(() -> jdbc.update("update usuario set totp_secreto = x'01' where email = 'dueno@tornillo.demo'"))
                .as("el CHECK no deja un secreto a un Dueño")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_usuario_totp");
    }

    private long id(String email) {
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }

    /** Las columnas del código de la app; el secreto en hexadecimal, para comparar filas con equals. */
    private Map<String, Object> fila(String email) {
        return jdbc.queryForMap("select hex(totp_secreto) secreto, totp_activado_en activado, totp_ultimo_paso paso "
                + "from usuario where email = ?", email);
    }

    private static List<String> campos(JsonNode nodo) {
        List<String> campos = new ArrayList<>();
        nodo.fieldNames().forEachRemaining(campos::add);
        return campos;
    }
}
