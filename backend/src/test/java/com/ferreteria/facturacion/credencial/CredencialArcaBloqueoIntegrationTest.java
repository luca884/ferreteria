package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lo que la credencial de ARCA traba o avisa (rama 5): el aviso 30 días antes del vencimiento y la facturación bloqueada con
 * la credencial vencida o ilegible, solo en homologación o producción y solo en esa ferretería (S6, S8, S12); la
 * habilitación trabada hasta la conexión real (S13); el 403 del Vendedor y del super admin, y otra ferretería que no ve nada
 * (S11); y ningún secreto en el JSON ni en el log (S7).
 */
@ExtendWith(OutputCaptureExtension.class)
class CredencialArcaBloqueoIntegrationTest extends CredencialArcaTestBase {

    private static final String SIN_PEDIDO = "No hay un pedido de certificado pendiente.";

    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    PlatformTransactionManager transacciones;
    @Autowired
    CifradorDeClaves cifrador;

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    /** La facturación prendida por SQL (hasta la rama 6 el panel no la prende en homologación ni producción, S13). */
    private void facturacionPrendida(Ferreteria f) {
        jdbc.update("update config_arca set punto_venta = 3, facturacion_habilitada = true, habilitada_en = "
                + "'2026-10-01 12:00:00' where ferreteria_id = ?", f.id());
    }

    /** Lo que decide la venta (con lock) y lo que anticipa el mostrador (sin lock, por HTTP): si factura. */
    private List<Boolean> factura(Ferreteria f) throws Exception {
        TenantContext.setCurrentFerreteriaId(f.id());
        try {
            boolean decide = new TransactionTemplate(transacciones)
                    .execute(t -> facturacion.decidir(ReceptorVenta.sinCliente()).factura());
            boolean mostrador = getJson("/api/ventas/facturacion", f.dueno()).get("factura").asBoolean();
            return List.of(decide, mostrador);
        } finally {
            TenantContext.clear();
        }
    }

    // ---- S8 y S12: el aviso y el bloqueo por vencimiento ----

    @Test
    @DisplayName("con el reloj fijo, la credencial a 29 días tiene el aviso y la de 31 no")
    void avisoTreintaDiasAntes() throws Exception {
        Ferreteria a29 = ferreteria();
        X509Certificate certificado = emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(a29), a29, a29.cuit(),
                AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(29)));
        subir(a29, pem(certificado)).andExpect(status().isOk());
        Ferreteria a31 = ferreteria();
        subir(a31, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(a31), a31, a31.cuit(), AHORA.minus(DIA),
                AHORA.plus(DIA.multipliedBy(31))))).andExpect(status().isOk());

        JsonNode porVencer = configuracion(a29);
        JsonNode vigente = configuracion(a31);

        assertThat(porVencer.get("credencial").get("estado").asText()).isEqualTo("POR_VENCER");
        assertThat(porVencer.get("credencial").get("diasParaVencer").asLong()).isEqualTo(29);
        assertThat(porVencer.get("avisos")).singleElement().satisfies(a -> assertThat(a.asText())
                .isEqualTo("El certificado de ARCA vence el 26/11/2026: renovalo en Facturación."));
        assertThat(vigente.get("credencial").get("estado").asText()).isEqualTo("VIGENTE");
        assertThat(vigente.get("credencial").get("diasParaVencer").asLong()).isEqualTo(31);
        assertThat(vigente.get("avisos")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("con la credencial vencida, la venta sale sin factura (D5) solo en esa ferretería; la otra sigue facturando")
    void vencidaBloqueaSoloEsaFerreteria(String ambiente) throws Exception {
        AutoridadDePrueba ca = ambiente.equals("HOMOLOGACION") ? CasDeArcaDePrueba.HOMOLOGACION : CasDeArcaDePrueba.PRODUCCION;
        Ferreteria vence = ferreteria();
        subir(vence, pem(emitido(ca, pedido(vence), vence, vence.cuit(), AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(10)))))
                .andExpect(status().isOk());
        facturacionPrendida(vence);
        Ferreteria sigue = ferreteria();
        activa(sigue, ca);
        facturacionPrendida(sigue);
        assertThat(factura(vence)).containsExactly(true, true);

        reloj.avanzar(DIA.multipliedBy(11));

        assertThat(factura(vence)).containsExactly(false, false);
        assertThat(factura(sigue)).containsExactly(true, true);
        JsonNode config = configuracion(vence);
        assertThat(config.get("credencial").get("estado").asText()).isEqualTo("VENCIDA");
        assertThat(config.get("credencial").get("diasParaVencer").asLong()).isEqualTo(-1);
        assertThat(config.get("avisos")).singleElement().satisfies(a -> assertThat(a.asText()).isEqualTo(
                "El certificado de ARCA venció el 07/11/2026: generá un pedido nuevo y subí el certificado renovado."));
    }

    @Test
    @DisplayName("sin credencial activa, homologación con la facturación prendida no factura; el simulador sigue sin credencial")
    void sinCredencialNoFacturaSalvoElSimulador() throws Exception {
        Ferreteria sinCredencial = ferreteria();
        jdbc.update("insert into config_arca (ferreteria_id, ambiente, punto_venta, facturacion_habilitada, habilitada_en) "
                + "values (?, 'HOMOLOGACION', 3, true, '2026-10-01 12:00:00')", sinCredencial.id());
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);
        jdbc.update("update config_arca set facturacion_habilitada = true, habilitada_en = '2026-10-01 12:00:00' "
                + "where ferreteria_id = ?", simulador.id());

        assertThat(factura(sinCredencial)).containsExactly(false, false);
        assertThat(factura(simulador)).containsExactly(true, true);
    }

    // ---- S6: un id de clave desconocido deja la credencial ILEGIBLE y bloquea solo esa ferretería ----

    @Test
    @DisplayName("una clave cifrada con una clave maestra desconocida deja la credencial ILEGIBLE, con su aviso, y bloquea solo "
            + "esa ferretería")
    void ilegibleBloqueaSoloEsaFerreteria(CapturedOutput salida) throws Exception {
        Ferreteria ilegible = ferreteria();
        activa(ilegible, CasDeArcaDePrueba.HOMOLOGACION);
        facturacionPrendida(ilegible);
        Ferreteria sigue = ferreteria();
        activa(sigue, CasDeArcaDePrueba.HOMOLOGACION);
        facturacionPrendida(sigue);
        String huella = jdbc.queryForObject("select huella from credencial_arca where ferreteria_id = ?", String.class,
                ilegible.id());
        CifradorDeClaves otraClaveMaestra = new CifradorDeClaves(
                Base64.getEncoder().encodeToString("otra-clave-maestra-de-32-bytes!!".getBytes(StandardCharsets.UTF_8)), "");
        byte[] conOtraClave = otraClaveMaestra.cifrar("clave".getBytes(StandardCharsets.UTF_8),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ilegible.id(), huella));
        jdbc.update("update credencial_arca set clave_privada = ? where ferreteria_id = ?", conOtraClave, ilegible.id());

        JsonNode config = configuracion(ilegible);

        assertThat(config.get("credencial").get("estado").asText()).isEqualTo("ILEGIBLE");
        assertThat(config.get("avisos")).singleElement().satisfies(a -> assertThat(a.asText()).isEqualTo(
                "No se puede leer la clave guardada del certificado de ARCA: generá un pedido nuevo y subí el certificado "
                        + "otra vez."));
        assertThat(factura(ilegible)).containsExactly(false, false);
        assertThat(configuracion(sigue).get("credencial").get("estado").asText()).isEqualTo("VIGENTE");
        assertThat(factura(sigue)).containsExactly(true, true);
        assertThat(salida.getAll()).contains("CLAVE_DE_CREDENCIAL de la ferretería " + ilegible.id() + ": CLAVE_DESCONOCIDA")
                .doesNotContain(Base64.getEncoder().encodeToString(conOtraClave));
    }

    // ---- S13: el super admin no habilita homologación ni producción hasta la conexión real ----

    @Test
    @DisplayName("el super admin no prende la facturación con el ambiente del certificado: 422 y nada cambia")
    void elSuperAdminNoHabilitaHomologacionNiProduccion() throws Exception {
        String admin = login("admin@demo.local");
        for (AutoridadDePrueba ca : List.of(CasDeArcaDePrueba.HOMOLOGACION, CasDeArcaDePrueba.PRODUCCION)) {
            Ferreteria f = ferreteria();
            activa(f, ca);
            putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isOk());

            mvc.perform(patch("/api/admin/ferreterias/" + f.id() + "/facturacion").header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"habilitada\": true}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message", is("La facturación con ARCA se habilita cuando esté la conexión real.")));

            assertThat(config(f)).containsEntry("facturacion_habilitada", false);
        }
    }

    // ---- S11: roles y aislamiento ----

    @Test
    @DisplayName("el Vendedor y el super admin reciben 403 en toda la API de facturación, aun con el cuerpo inválido")
    void soloElDueno() throws Exception {
        Ferreteria f = ferreteria();
        pedido(f);
        String admin = login("admin@demo.local");
        List<String> antes = credenciales(f).stream().map(Object::toString).toList();

        for (String token : List.of(f.vendedor(), admin)) {
            getResult(URL + "/configuracion", token).andExpect(status().isForbidden());
            getResult(URL + "/pedido-certificado", token).andExpect(status().isForbidden());
            putJson(URL + "/punto-venta", token, "{\"puntoVenta\": 0}").andExpect(status().isForbidden());
            putJson(URL + "/punto-venta", token, "{\"puntoVenta\": 3}").andExpect(status().isForbidden());
            postJson(URL + "/pedido-certificado", token, "{\"cuit\": \"x\"}").andExpect(status().isForbidden());
            postJson(URL + "/pedido-certificado", token, "").andExpect(status().isForbidden());
            postJson(URL + "/certificado", token, "{\"certificado\": \"\"}").andExpect(status().isForbidden());
            postJson(URL + "/certificado/simulador", token, "").andExpect(status().isForbidden());
        }
        getResult(URL + "/configuracion", "token-invalido").andExpect(status().isUnauthorized());

        assertThat(credenciales(f).stream().map(Object::toString).toList()).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Integer.class, f.id()))
                .isOne();
    }

    @Test
    @DisplayName("otra ferretería no ve la credencial ni el pedido, y no puede activar el certificado de la primera")
    void otraFerreteriaNoVeNada() throws Exception {
        Ferreteria a = ferreteria();
        X509Certificate deA = activa(a, CasDeArcaDePrueba.HOMOLOGACION);
        String pedidoDeA = pedido(a);
        Ferreteria b = ferreteria();

        JsonNode deB = configuracion(b);
        assertThat(deB.get("credencial").isNull()).isTrue();
        assertThat(deB.get("pedido").isNull()).isTrue();
        assertThat(deB.get("ambiente").isNull()).isTrue();
        getResult(URL + "/pedido-certificado", b.dueno()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is(SIN_PEDIDO)));
        subir(b, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedidoDeA, a))).andExpect(status().isConflict());
        pedido(b);
        subir(b, pem(deA)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.certificado", is(
                "El certificado no corresponde al pedido pendiente: subí el que ARCA emitió para el último pedido que "
                        + "generaste.")));

        assertThat(credenciales(b)).extracting(fila -> fila.get("estado")).containsExactly("PENDIENTE");
        assertThat(credenciales(a)).extracting(fila -> fila.get("estado")).containsExactly("ACTIVA", "PENDIENTE");
        assertThat(getJson(URL + "/pedido-certificado", a.dueno()).get("csr").asText()).isEqualTo(pedidoDeA);
    }

    // ---- S7: ningún secreto en las respuestas ni en el log ----

    @Test
    @DisplayName("ni las respuestas ni el log llevan la clave privada ni el dato cifrado: pedido, .crt, simulador y error al descifrar")
    void sinSecretosEnRespuestasNiLog(CapturedOutput salida) throws Exception {
        Ferreteria homologacion = ferreteria();
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);
        List<String> respuestas = new ArrayList<>();

        String csr = cuerpo(postJson(URL + "/pedido-certificado", homologacion.dueno(), "{}")).toString();
        respuestas.add(csr);
        respuestas.add(cuerpo(subir(homologacion, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION,
                objectMapper.readTree(csr).get("csr").asText(), homologacion)))).toString());
        respuestas.add(cuerpo(postJson(URL + "/pedido-certificado", simulador.dueno(), "{}")).toString());
        respuestas.add(cuerpo(postJson(URL + "/certificado/simulador", simulador.dueno(), "")).toString());
        respuestas.add(configuracion(simulador).toString());
        jdbc.update("update credencial_arca set clave_privada = concat(clave_privada, x'00') where ferreteria_id = ?",
                homologacion.id());
        respuestas.add(configuracion(homologacion).toString());

        List<String> cifrados = new ArrayList<>();
        jdbc.queryForList("select clave_privada from credencial_arca where ferreteria_id in (?, ?) union all "
                + "select clave_privada from ca_simulador where ferreteria_id = ?", byte[].class, homologacion.id(),
                simulador.id(), simulador.id()).forEach(dato -> cifrados.add(Base64.getEncoder().encodeToString(dato)));
        assertThat(cifrados).hasSize(3);
        assertThat(salida.getAll()).contains("NO_SE_DESCIFRA");
        for (String texto : concatenar(respuestas, salida.getAll())) {
            assertThat(texto).doesNotContain("PRIVATE KEY").doesNotContainIgnoringCase("clave_privada")
                    .doesNotContainIgnoringCase("clavePrivada");
            for (String cifrado : cifrados) {
                assertThat(texto).doesNotContain(cifrado).doesNotContain(cifrado.substring(0, 40));
            }
        }
    }

    private static List<String> concatenar(List<String> respuestas, String log) {
        List<String> todo = new ArrayList<>(respuestas);
        todo.add(log);
        return todo;
    }
}
