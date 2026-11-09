package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.auth.totp.CifradorTotp;
import com.ferreteria.facturacion.documento.LectorQr;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.CodigoDeLaApp;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * El login del super admin en dos pasos (totp-super-admin, S1 a S5 y S8): email y contraseña, y después el código de 6
 * números de Google Authenticator. El alta con el QR va una sola vez. Contra la API, con el reloj del test.
 */
@ExtendWith(OutputCaptureExtension.class)
class SegundoFactorIntegrationTest extends SegundoFactorTestBase {

    @Value("${app.jwt.secret}")
    String secretoJwt;

    @Value("${app.auth.totp.clave-maestra:}")
    String claveMaestraTotp;

    private HolderJdbc holder;

    @AfterEach
    void soltar() {
        if (holder != null) {
            holder.rollback();
        }
    }

    @Test
    @DisplayName("S1: con la contraseña bien no hay sesión sino el segundo paso; con el código bien, {token, sesion} y entra a "
            + "admin/ferreterias")
    void entraEnDosPasos() throws Exception {
        String email = crearSuperAdmin();

        JsonNode paso = primerPaso(email);

        assertThat(paso.get("token").isNull()).as("token").isTrue();
        assertThat(paso.get("sesion").isNull()).as("sesion").isTrue();
        assertThat(paso.get("segundoPaso").asText()).isEqualTo("ALTA");
        String tokenTotp = paso.get("tokenTotp").asText();
        assertThat(tokenTotp).isNotBlank();
        String clave = clave(tokenTotp);

        JsonNode entrada = cuerpo(verificar(tokenTotp, codigo(clave)).andExpect(status().isOk()));

        assertThat(nuloOAusente(entrada, "segundoPaso")).as("segundoPaso").isTrue();
        assertThat(nuloOAusente(entrada, "tokenTotp")).as("tokenTotp").isTrue();
        JsonNode sesion = entrada.get("sesion");
        assertThat(sesion.get("email").asText()).isEqualTo(email);
        assertThat(sesion.get("rol").asText()).isEqualTo("SUPER_ADMIN");
        assertThat(sesion.get("ferreteriaId").isNull()).isTrue();
        String token = entrada.get("token").asText();
        getResult("/api/admin/ferreterias", token).andExpect(status().isOk());
        getResult("/api/auth/me", token).andExpect(status().isOk()).andExpect(jsonPath("$.email", is(email)));
    }

    @Test
    @DisplayName("S2: el QR del alta es el de la clave y se repite igual hasta activarlo; activado, /alta da 409 y el login pide "
            + "CODIGO")
    void elQrEsUnaSolaVez() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);

        JsonNode alta = cuerpo(alta(tokenTotp).andExpect(status().isOk()));

        String clave = alta.get("clave").asText();
        String uri = alta.get("uri").asText();
        assertThat(clave).matches("[A-Z2-7]{32}");
        assertThat(uri).isEqualTo("otpauth://totp/Ferreter%C3%ADa:" + email + "?secret=" + clave
                + "&issuer=Ferreter%C3%ADa&algorithm=SHA1&digits=6&period=30");
        List<String> qr = new ArrayList<>();
        alta.get("qr").forEach(fila -> qr.add(fila.asText()));
        assertThat(qr).allMatch(fila -> fila.matches("[01]+") && fila.length() == qr.size());
        assertThat(LectorQr.leer(qr).texto()).isEqualTo(uri);
        assertThat(cuerpo(alta(tokenTotp(email)).andExpect(status().isOk()))).as("el alta pendiente, otra vez")
                .isEqualTo(alta);

        verificar(tokenTotp, codigo(clave)).andExpect(status().isOk());

        JsonNode despues = primerPaso(email);
        assertThat(despues.get("segundoPaso").asText()).isEqualTo("CODIGO");
        assertThat(despues.get("token").isNull()).isTrue();
        String respuesta = alta(despues.get("tokenTotp").asText()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("El código de la app ya está activado.")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(respuesta).doesNotContain(clave);
    }

    @Test
    @DisplayName("verificar sin haber pedido el alta da 409 «Primero escaneá el código QR.»")
    void verificarSinAlta() throws Exception {
        String email = crearSuperAdmin();

        verificar(tokenTotp(email), "123456").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Primero escaneá el código QR.")));
    }

    @Test
    @DisplayName("un tokenTotp inválido, de sesión o vencido da 401 TOTP_VENCIDO en los dos endpoints")
    void tokenTotpVencido() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);
        String sesionDeUnDueno = login("dueno@tornillo.demo");

        for (String invalido : new String[] {"no-es-un-token", sesionDeUnDueno, null}) {
            alta(invalido).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message", is(TOTP_VENCIDO)))
                    .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
            verificar(invalido, codigo(clave)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message", is(TOTP_VENCIDO)))
                    .andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
        }
        reloj.avanzar(Duration.ofMinutes(5).plusSeconds(1));
        alta(tokenTotp).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
        verificar(tokenTotp, codigo(clave)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401))).andExpect(jsonPath("$.errors.motivo", is("TOTP_VENCIDO")));
    }

    @Test
    @DisplayName("un código incorrecto da 401 CODIGO_INCORRECTO; uno que no son 6 números, 400 «El código tiene 6 números.»")
    void codigoIncorrectoOMalEscrito() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);

        verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is(CODIGO_INCORRECTO)))
                .andExpect(jsonPath("$.errors.motivo", is("CODIGO_INCORRECTO")));
        for (String malEscrito : new String[] {"12345", "1234567", "12a456", " 123456", "", null}) {
            verificar(tokenTotp, malEscrito).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("El código tiene 6 números.")));
        }
        assertThat(primerPaso(email).get("segundoPaso").asText()).as("sigue sin activar").isEqualTo("ALTA");
    }

    @Test
    @DisplayName("S4: con el UPDATE del README (Deploy) el login vuelve a pedir ALTA, y el alta da una clave nueva")
    void resetPorBase() throws Exception {
        String email = crearSuperAdmin();
        String claveVieja = activar(email);
        assertThat(primerPaso(email).get("segundoPaso").asText()).isEqualTo("CODIGO");

        jdbc.update(updateDelReadme(email));

        String tokenTotp = tokenTotp(email);
        assertThat(primerPaso(email).get("segundoPaso").asText()).isEqualTo("ALTA");
        String claveNueva = clave(tokenTotp);
        assertThat(claveNueva).isNotEqualTo(claveVieja);
        verificar(tokenTotp, codigo(claveNueva)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S5a: el tokenTotp no sirve como token de sesión: cualquier otro endpoint da 401")
    void elTokenTotpNoEsUnaSesion() throws Exception {
        String tokenTotp = tokenTotp(crearSuperAdmin());

        getResult("/api/auth/me", tokenTotp).andExpect(status().isUnauthorized());
        getResult("/api/admin/ferreterias", tokenTotp).andExpect(status().isUnauthorized());
        putJson("/api/auth/password", tokenTotp, json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S5b: un token de SUPER_ADMIN que no pasó por el código (como los de antes del deploy) da 401")
    void sinSegundoFactorNoEntra() throws Exception {
        String email = crearSuperAdmin();
        long id = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        String deAntes = Jwts.builder().subject(String.valueOf(id)).claim("email", email).claim("rol", "SUPER_ADMIN")
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(secretoJwt.getBytes(StandardCharsets.UTF_8))).compact();

        getResult("/api/auth/me", deAntes).andExpect(status().isUnauthorized());
        getResult("/api/admin/ferreterias", deAntes).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S5c: un código ya usado da CODIGO_INCORRECTO, y uno de un paso anterior al usado también")
    void codigoUsado() throws Exception {
        String email = crearSuperAdmin();
        String clave = activar(email);
        String usado = codigo(clave);

        verificar(tokenTotp(email), usado).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("CODIGO_INCORRECTO")));

        reloj.avanzar(Duration.ofSeconds(30));
        String delSiguiente = CodigoDeLaApp.para(clave, reloj.instant().plusSeconds(30));
        verificar(tokenTotp(email), delSiguiente).andExpect(status().isOk());
        // El del paso actual: es anterior al que se acaba de usar.
        verificar(tokenTotp(email), codigo(clave)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.motivo", is("CODIGO_INCORRECTO")));
    }

    @Test
    @DisplayName("S5c: dos verificaciones simultáneas con el mismo código dan un 200 y un 401, sin deadlocks")
    void dosVerificacionesSimultaneas() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);
        String elCodigo = codigo(clave);
        long id = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocks = barrera.deadlocksAntes();
        // Otra transacción tiene la fila del usuario: las dos verificaciones leen el secreto y llegan a escribir el paso.
        holder = BarreraDeLocks.holderJdbc("select id from usuario where id = " + id + " for update");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codigos = new ArrayList<>();
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futuros.add(pool.submit(() -> verificar(tokenTotp, elCodigo).andReturn().getResponse().getStatus()));
            }
            barrera.esperarBloqueados(2);
            holder.rollback();
            for (Future<Integer> futuro : futuros) {
                codigos.add(futuro.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(codigos).containsExactlyInAnyOrder(200, 401);
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("S5e: el secreto se guarda cifrado con TOTP_CLAVE_MAESTRA, no va al log y no vuelve a la API después de activado")
    void elSecretoVaCifrado(CapturedOutput salida) throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        JsonNode alta = cuerpo(alta(tokenTotp).andExpect(status().isOk()));
        String clave = alta.get("clave").asText();
        String entrada = verificar(tokenTotp, codigo(clave)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        byte[] guardado = jdbc.queryForObject("select totp_secreto from usuario where id = ?", byte[].class, id);

        byte[] secreto = CodigoDeLaApp.decodificar(clave);
        assertThat(secreto).hasSize(20);
        assertThat(new CifradorTotp(claveMaestraTotp).descifrar(id, guardado)).isEqualTo(secreto);
        assertThat(guardado).hasSize(1 + 12 + 20 + 16);
        assertThat(Arrays.equals(Arrays.copyOfRange(guardado, 13, 33), secreto)).isFalse();
        assertThat(new String(guardado, StandardCharsets.ISO_8859_1)).doesNotContain(clave);
        String sesion = getJson("/api/auth/me", objectMapper.readTree(entrada).get("token").asText()).toString();
        assertThat(entrada + sesion).doesNotContain(clave);
        assertThat(salida.getAll()).doesNotContain(clave).doesNotContain(alta.get("uri").asText()).doesNotContain("secret=");
    }

    @Test
    @DisplayName("S8: el Dueño y el Vendedor entran como siempre, con {token, sesion} y sin segundo paso")
    void duenoYVendedorSinCambios() throws Exception {
        long ferreteria = crearFerreteria("Sin TOTP");
        for (String rol : new String[] {"DUENO", "VENDEDOR"}) {
            String email = unico(rol.toLowerCase()) + "@sin-totp.test";
            crearUsuario(ferreteria, email, "Usuario " + rol, rol);

            JsonNode login = primerPaso(email);

            assertThat(login.get("token").asText()).isNotBlank();
            assertThat(login.get("sesion").get("rol").asText()).isEqualTo(rol);
            assertThat(nuloOAusente(login, "segundoPaso")).as("segundoPaso").isTrue();
            assertThat(nuloOAusente(login, "tokenTotp")).as("tokenTotp").isTrue();
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + login.get("token").asText()))
                    .andExpect(status().isOk());
        }
    }

    /** El UPDATE del reset del README (Deploy), con el email del test en lugar de {@code <email>}. */
    private static String updateDelReadme(String email) throws Exception {
        String readme = Files.readString(Path.of("..", "README.md"));
        Matcher update = Pattern.compile("`(UPDATE usuario SET totp_secreto = NULL[^`]*)`").matcher(readme);
        assertThat(update.find()).as("el UPDATE del reset en README.md").isTrue();
        assertThat(update.group(1)).contains("'<email>'").contains("rol = 'SUPER_ADMIN'");
        return update.group(1).replace("<email>", email);
    }
}
