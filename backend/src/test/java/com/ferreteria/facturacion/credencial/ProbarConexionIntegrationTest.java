package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * «Probar conexión» del Dueño (rama 6, C1, CU-01 paso 4) de punta a punta: el endpoint, los 409, los roles, el aislamiento y
 * el flujo real en SIMULADOR, con el mismo {@code ClienteWsfe} que ARCA y el simulador en el borde (TA firmado con la
 * credencial que firma la CA del simulador de la ferretería). Los cortes de cada paso están en {@code PruebaDeConexionTest}.
 */
class ProbarConexionIntegrationTest extends CredencialArcaTestBase {

    static final String PROBAR = URL + "/probar-conexion";
    static final String SIN_AMBIENTE = "Para probar la conexión hacen falta el ambiente de ARCA y el punto de venta.";
    static final String SIN_CERTIFICADO = "Para probar la conexión hace falta un certificado vigente.";

    private ResultActions probar(String token) throws Exception {
        return postJson(PROBAR, token, "");
    }

    /** Una ferretería en SIMULADOR con la credencial del alta real (pedido y firma con la CA de su simulador). */
    private Ferreteria enElSimulador() throws Exception {
        Ferreteria f = ferreteria();
        conSimulador(f);
        pedido(f);
        postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isOk());
        return f;
    }

    private void bloqueada(Ferreteria f) {
        jdbc.update("update config_arca set bloqueo_codigo = 'coe.notAuthorized', bloqueo_mensaje = 'Computador no "
                + "autorizado a acceder al servicio', bloqueado_en = '2026-10-28 13:10:00' where ferreteria_id = ?", f.id());
    }

    private String bloqueo(Ferreteria f) {
        return jdbc.queryForObject("select bloqueo_codigo from config_arca where ferreteria_id = ?", String.class, f.id());
    }

    private static List<String> pasos(JsonNode prueba) {
        return StreamSupport.stream(prueba.get("pasos").spliterator(), false)
                .map(p -> p.get("paso").asText() + (p.get("ok").asBoolean() ? " OK " : " FALLA ") + p.get("detalle").asText())
                .toList();
    }

    @Test
    @DisplayName("en SIMULADOR los cuatro pasos dan OK contra el simulador en el borde, con el aviso y los últimos A y B")
    void enElSimuladorTodoOk() throws Exception {
        Ferreteria f = enElSimulador();

        JsonNode prueba = cuerpo(probar(f.dueno()).andExpect(status().isOk()));

        assertThat(prueba.get("ambiente").asText()).isEqualTo("SIMULADOR");
        assertThat(prueba.get("ok").asBoolean()).isTrue();
        assertThat(prueba.get("aviso").asText()).isEqualTo("Simulador: no se probó contra ARCA");
        List<String> pasos = pasos(prueba);
        assertThat(pasos).hasSize(4);
        assertThat(pasos.get(0)).isEqualTo("SERVIDORES OK AppServer OK, DbServer OK, AuthServer OK");
        assertThat(pasos.get(1)).matches("AUTENTICACION OK Ticket de acceso vigente hasta \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
        assertThat(pasos.get(2)).isEqualTo("PUNTO_DE_VENTA OK Punto de venta 1: CAE, habilitado");
        assertThat(pasos.get(3)).isEqualTo("ULTIMOS_NUMEROS OK Factura A y Factura B");
        assertThat(prueba.get("ultimos").toString())
                .isEqualTo("[{\"cbteTipo\":1,\"comprobante\":\"Factura A\",\"numero\":0},"
                        + "{\"cbteTipo\":6,\"comprobante\":\"Factura B\",\"numero\":0}]");
        assertThat(prueba.fieldNames()).toIterable().containsExactly("ambiente", "ok", "aviso", "pasos", "ultimos");
        String texto = prueba.toString().toLowerCase();
        assertThat(texto).doesNotContain("token").doesNotContain("sign").doesNotContain("cms");
    }

    @Test
    @DisplayName("una prueba OK levanta el bloqueo de ARCA (C4) de esa ferretería, y el aviso se va; el de otra sigue")
    void okLevantaElBloqueo() throws Exception {
        Ferreteria f = enElSimulador();
        Ferreteria otra = enElSimulador();
        bloqueada(f);
        bloqueada(otra);
        assertThat(configuracion(f).get("avisos").toString()).contains("coe.notAuthorized");

        probar(f.dueno()).andExpect(status().isOk()).andExpect(jsonPath("$.ok", is(true)));

        assertThat(bloqueo(f)).isNull();
        assertThat(configuracion(f).get("avisos").toString()).doesNotContain("coe.notAuthorized");
        assertThat(bloqueo(otra)).isEqualTo("coe.notAuthorized");
    }

    @Test
    @DisplayName("Monotributista pide solo la Factura C")
    void monotributistaPideSoloLaC() throws Exception {
        Ferreteria f = enElSimulador();
        jdbc.update("update ferreteria set condicion_iva = 'MONOTRIBUTISTA' where id = ?", f.id());

        JsonNode prueba = cuerpo(probar(f.dueno()).andExpect(status().isOk()));

        assertThat(prueba.get("ultimos").toString()).isEqualTo("[{\"cbteTipo\":11,\"comprobante\":\"Factura C\",\"numero\":0}]");
    }

    @Test
    @DisplayName("409 sin ambiente o sin punto de venta, y 409 sin un certificado vigente; no se llama a ARCA")
    void losDos409() throws Exception {
        Ferreteria sinConfig = ferreteria();
        probar(sinConfig.dueno()).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(SIN_AMBIENTE)));

        Ferreteria sinPunto = ferreteria();
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente) values (?, false, 'HOMOLOGACION')",
                sinPunto.id());
        probar(sinPunto.dueno()).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(SIN_AMBIENTE)));

        Ferreteria sinCertificado = ferreteria();
        conSimulador(sinCertificado);
        probar(sinCertificado.dueno()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_CERTIFICADO)));

        Ferreteria vencido = enElSimulador();
        reloj.fijar(AHORA.plus(DIA.multipliedBy(800))); // el certificado del simulador vale dos años
        probar(vencido.dueno()).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(SIN_CERTIFICADO)));

        assertThat(jdbc.queryForObject("select count(*) from ticket_acceso where ferreteria_id in (?, ?, ?, ?)",
                Integer.class, sinConfig.id(), sinPunto.id(), sinCertificado.id(), vencido.id())).isZero();
    }

    @Test
    @DisplayName("el Vendedor y el super admin reciben 403")
    void soloElDueno() throws Exception {
        Ferreteria f = enElSimulador();

        probar(f.vendedor()).andExpect(status().isForbidden());
        probar(login("admin@demo.local")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from ticket_acceso where ferreteria_id = ?", Integer.class, f.id()))
                .isZero();
    }

    @Test
    @DisplayName("otra ferretería no ve nada: el Dueño prueba solo la suya, aunque otra esté configurada y bloqueada")
    void otraFerreteriaNoVeNada() throws Exception {
        Ferreteria configurada = enElSimulador();
        bloqueada(configurada);
        Ferreteria sinConfig = ferreteria();

        probar(sinConfig.dueno()).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(SIN_AMBIENTE)));

        assertThat(bloqueo(configurada)).isEqualTo("coe.notAuthorized");
        assertThat(jdbc.queryForObject("select count(*) from ticket_acceso where ferreteria_id = ?", Integer.class,
                configurada.id())).isZero();
    }
}
