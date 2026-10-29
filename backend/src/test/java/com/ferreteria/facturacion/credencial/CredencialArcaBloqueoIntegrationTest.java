package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lo que la credencial de ARCA traba o avisa (rama 5): el aviso 30 días antes del vencimiento y la facturación bloqueada con
 * la credencial vencida o ilegible, solo en homologación o producción y solo en esa ferretería (S6, S8, S12); la
 * habilitación por ambiente (rama 6, S16: homologación sí, producción todavía no); el 403 del Vendedor y del super admin, y
 * otra ferretería que no ve nada (S11); y ningún secreto en el JSON ni en el log (S7).
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

    /** Lo que decide la venta (con lock) y lo que anticipa el mostrador (sin lock, por HTTP): si factura. */
    private List<Boolean> factura(Ferreteria f) throws Exception {
        TenantContext.setCurrentFerreteriaId(f.id());
        try {
            boolean decide = new TransactionTemplate(transacciones)
                    .execute(t -> facturacion.decidir(ReceptorVenta.sinCliente(), reloj.instant()).factura());
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
    @DisplayName("el vencimiento es exacto: a un milisegundo del final factura y está POR_VENCER; en el instante del final no "
            + "factura ni la venta ni el mostrador y está VENCIDA")
    void elVencimientoEsExacto() throws Exception {
        Ferreteria f = ferreteria();
        Instant hasta = AHORA.plus(DIA.multipliedBy(10));
        subir(f, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f, f.cuit(), AHORA.minus(DIA), hasta)))
                .andExpect(status().isOk());
        facturacionPrendida(f);

        reloj.fijar(hasta.minusMillis(1));
        assertThat(factura(f)).containsExactly(true, true);
        assertThat(configuracion(f).get("credencial").get("estado").asText()).isEqualTo("POR_VENCER");

        reloj.fijar(hasta);
        assertThat(factura(f)).containsExactly(false, false);
        assertThat(configuracion(f).get("credencial").get("estado").asText()).isEqualTo("VENCIDA");
    }

    @Test
    @DisplayName("decidir y registrar usan el instante de la venta: con el reloj que avanza entre los dos y la credencial que "
            + "vence en el medio, la venta decidida facturable se registra (rama 6, S15)")
    void decidirYRegistrarUsanElMismoInstante() throws Exception {
        Ferreteria f = ferreteria();
        Instant hasta = AHORA.plus(DIA.multipliedBy(10));
        subir(f, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f, f.cuit(), AHORA.minus(DIA), hasta)))
                .andExpect(status().isOk());
        facturacionPrendida(f);
        long venta = venta(f);
        Instant instante = hasta.minusMillis(1);
        reloj.fijar(instante);

        TenantContext.setCurrentFerreteriaId(f.id());
        long registro = new TransactionTemplate(transacciones).execute(t -> {
            assertThat(facturacion.decidir(ReceptorVenta.sinCliente(), instante).factura()).isTrue();
            reloj.fijar(hasta);
            return facturacion.registrar(venta, ReceptorVenta.sinCliente(),
                    List.of(new RenglonParaFacturar(new BigDecimal("21.00"), new BigDecimal("121.00"))), instante);
        });
        TenantContext.clear();

        // El registro lleva el instante de la venta (en UTC), un milisegundo antes del vencimiento.
        assertThat(jdbc.queryForMap("select comprobante_id, ambiente, estado, date_format(registrado_en, "
                + "'%Y-%m-%d %H:%i:%s.%f') as registrado_en from comprobante_fiscal where id = ?", registro))
                .containsEntry("comprobante_id", venta).containsEntry("ambiente", "HOMOLOGACION")
                .containsEntry("estado", "PENDIENTE").containsEntry("registrado_en", "2026-11-07 13:15:29.999000");
        // Con la hora de ahora (ya vencida) la venta siguiente no factura.
        assertThat(factura(f)).containsExactly(false, false);
    }

    @Test
    @DisplayName("con el ambiente en PRODUCCION (por SQL) y una credencial de HOMOLOGACION, la venta no factura")
    void laCredencialDeOtroAmbienteNoFactura() throws Exception {
        Ferreteria f = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        facturacionPrendida(f);
        assertThat(factura(f)).containsExactly(true, true);

        jdbc.update("update config_arca set ambiente = 'PRODUCCION' where ferreteria_id = ?", f.id());

        assertThat(factura(f)).containsExactly(false, false);
    }

    @Test
    @DisplayName("sin credencial activa no factura ni homologación ni el simulador (rama 6, S6); con la credencial que firma el "
            + "simulador, el simulador factura")
    void sinCredencialNoFacturaNiElSimulador() throws Exception {
        Ferreteria sinCredencial = ferreteria();
        jdbc.update("insert into config_arca (ferreteria_id, ambiente, punto_venta, facturacion_habilitada, habilitada_en) "
                + "values (?, 'HOMOLOGACION', 3, true, '2026-10-01 12:00:00')", sinCredencial.id());
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);
        jdbc.update("update config_arca set facturacion_habilitada = true, habilitada_en = '2026-10-01 12:00:00' "
                + "where ferreteria_id = ?", simulador.id());

        assertThat(factura(sinCredencial)).containsExactly(false, false);
        assertThat(factura(simulador)).containsExactly(false, false);

        pedido(simulador);
        postJson(URL + "/certificado/simulador", simulador.dueno(), "").andExpect(status().isOk());

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

    // ---- S16 de la rama 6: el super admin habilita homologación; producción sigue trabada hasta la rama 11 ----

    @Test
    @DisplayName("el super admin prende la facturación en homologación (200, y la venta factura con la credencial); en "
            + "producción, 422 con el mensaje y nada cambia")
    void elSuperAdminHabilitaHomologacionYNoProduccion() throws Exception {
        String admin = login("admin@demo.local");
        Ferreteria homologacion = ferreteria();
        activa(homologacion, CasDeArcaDePrueba.HOMOLOGACION);
        Ferreteria produccion = ferreteria();
        activa(produccion, CasDeArcaDePrueba.PRODUCCION);
        for (Ferreteria f : List.of(homologacion, produccion)) {
            putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isOk());
        }

        patchAdmin(admin, homologacion, "facturacion", "{\"habilitada\": true}").andExpect(status().isOk());
        patchAdmin(admin, produccion, "facturacion", "{\"habilitada\": true}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is("La facturación en producción todavía no está disponible.")));

        assertThat(config(homologacion)).containsEntry("facturacion_habilitada", true);
        assertThat(factura(homologacion)).containsExactly(true, true);
        assertThat(config(produccion)).containsEntry("facturacion_habilitada", false);
        assertThat(factura(produccion)).containsExactly(false, false);
    }

    @Test
    @DisplayName("prender, apagar y volver a prender en homologación: ya prendida no cambia nada, apagarla anda y la vuelta "
            + "la prende otra vez")
    void apagarYVolverAPrenderEnHomologacion() throws Exception {
        String admin = login("admin@demo.local");
        Ferreteria f = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        facturacionPrendida(f);

        patchAdmin(admin, f, "facturacion", "{\"habilitada\": true}").andExpect(status().isOk());
        assertThat(config(f)).containsEntry("facturacion_habilitada", true);
        patchAdmin(admin, f, "facturacion", "{\"habilitada\": false}").andExpect(status().isOk());
        assertThat(config(f)).containsEntry("facturacion_habilitada", false);
        patchAdmin(admin, f, "facturacion", "{\"habilitada\": true}").andExpect(status().isOk());

        assertThat(config(f)).containsEntry("facturacion_habilitada", true);
        assertThat(factura(f)).containsExactly(true, true);
    }

    @Test
    @DisplayName("el simulador se habilita sin credencial: el super admin lo prende (200), y la venta factura recién con la "
            + "credencial del simulador (rama 6, S6)")
    void elSimuladorSeHabilitaSinCredencial() throws Exception {
        Ferreteria f = ferreteria();
        conSimulador(f);

        patchAdmin(login("admin@demo.local"), f, "facturacion", "{\"habilitada\": true}").andExpect(status().isOk());

        assertThat(config(f)).containsEntry("facturacion_habilitada", true);
        assertThat(factura(f)).containsExactly(false, false);
        pedido(f);
        postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isOk());
        assertThat(factura(f)).containsExactly(true, true);
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
    @DisplayName("el Vendedor y el super admin reciben 403 también con DELETE y en una ruta que no existe; sin token, 401 y no "
            + "se crea ninguna fila")
    void tambienLosMetodosYLasRutasQueNoExisten() throws Exception {
        Ferreteria f = ferreteria();
        String admin = login("admin@demo.local");
        int filas = jdbc.queryForObject("select count(*) from credencial_arca", Integer.class);

        for (String token : List.of(f.vendedor(), admin)) {
            delete(URL + "/certificado", token).andExpect(status().isForbidden());
            getResult(URL + "/otra-cosa", token).andExpect(status().isForbidden());
        }
        mvc.perform(post(URL + "/pedido-certificado").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(credenciales(f)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from credencial_arca", Integer.class)).isEqualTo(filas);
        assertThat(jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Integer.class, f.id()))
                .isZero();
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

    @Test
    @DisplayName("ni el eco de una clave pegada, de un JSON roto o del CUIT, ni el dato cifrado ni el PKCS#8 descifrado (en "
            + "Base64, hex o HEX), en las respuestas ni en el log")
    void sinSecretosNiEnLosEcos(CapturedOutput salida) throws Exception {
        Ferreteria homologacion = ferreteria();
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);
        List<String> respuestas = new ArrayList<>();

        // Una clave privada de verdad, pegada de todas las formas: en PEM, en Base64 con saltos y en una línea.
        byte[] pkcs8 = AutoridadDePrueba.rsa(2048).getPrivate().getEncoded();
        String enUnaLinea = Base64.getEncoder().encodeToString(pkcs8);
        String conSaltos = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs8);
        String pegada = "-----BEGIN PRIVATE KEY-----\n" + conSaltos + "\n-----END PRIVATE KEY-----\n";
        String marca = enUnaLinea.substring(200, 260);

        String delPedido = texto(postJson(URL + "/pedido-certificado", homologacion.dueno(), "{}"));
        respuestas.add(delPedido);
        String csr = objectMapper.readTree(delPedido).get("csr").asText();
        respuestas.add(texto(getResult(URL + "/pedido-certificado", homologacion.dueno())));
        respuestas.add(texto(getResult(URL + "/configuracion", homologacion.dueno())));
        respuestas.add(texto(subir(homologacion, pegada)));
        respuestas.add(texto(subir(homologacion, conSaltos)));
        respuestas.add(texto(subir(homologacion, enUnaLinea)));
        // JSON roto (sin cerrar, con la clave adentro), JSON bien escapado pero cortado, tipos que no son, y el CUIT con la clave.
        respuestas.add(texto(postJson(URL + "/certificado", homologacion.dueno(), "{\"certificado\": \"" + pegada + "\"")));
        respuestas.add(texto(postJson(URL + "/certificado", homologacion.dueno(),
                objectMapper.writeValueAsString(Map.of("certificado", pegada)).replaceFirst("}$", ""))));
        respuestas.add(texto(postJson(URL + "/certificado", homologacion.dueno(),
                "{\"certificado\": 12, \"x\": \"" + enUnaLinea + "\"}")));
        respuestas.add(texto(postJson(URL + "/pedido-certificado", homologacion.dueno(), "{\"cuit\": \"" + marca + "\"}")));
        // Certificados que no sirven y el que sí.
        respuestas.add(texto(subir(homologacion, pem(CasDeArcaDePrueba.AJENA.certificado()))));
        respuestas.add(texto(subir(homologacion,
                "-----BEGIN CERTIFICATE-----\n" + marca + "\n-----END CERTIFICATE-----\n")));
        respuestas.add(texto(subir(homologacion, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, csr, homologacion)))));
        // El simulador: el pedido, la firma (que crea la CA) y un pedido nuevo.
        respuestas.add(texto(postJson(URL + "/pedido-certificado", simulador.dueno(), "{}")));
        respuestas.add(texto(postJson(URL + "/certificado/simulador", simulador.dueno(), "")));
        respuestas.add(texto(postJson(URL + "/pedido-certificado", simulador.dueno(), "{}")));

        // Los secretos: lo cifrado y lo descifrado de cada clave guardada (dos credenciales, un pedido y la CA del simulador).
        List<String> secretos = new ArrayList<>(List.of(marca));
        int guardadas = 0;
        for (Map<String, Object> fila : jdbc.queryForList("select ferreteria_id, huella, clave_privada, 'C' as origen from "
                + "credencial_arca where ferreteria_id in (?, ?) union all select ferreteria_id, huella, clave_privada, 'S' "
                + "from ca_simulador where ferreteria_id = ?", homologacion.id(), simulador.id(), simulador.id())) {
            byte[] cifrado = (byte[]) fila.get("clave_privada");
            Proposito proposito = "C".equals(fila.get("origen")) ? Proposito.CLAVE_DE_CREDENCIAL : Proposito.CLAVE_DE_CA_SIMULADOR;
            byte[] claro = cifrador.descifrar(cifrado, new ContextoDeCifrado(proposito,
                    ((Number) fila.get("ferreteria_id")).longValue(), (String) fila.get("huella")));
            secretos.addAll(rodajas(cifrado));
            secretos.addAll(rodajas(claro));
            guardadas++;
        }
        assertThat(guardadas).isEqualTo(4);
        // Con las claves ya guardadas ilegibles: el error al descifrar tampoco las filtra.
        jdbc.update("update ca_simulador set clave_privada = concat(clave_privada, x'00') where ferreteria_id = ?",
                simulador.id());
        respuestas.add(texto(postJson(URL + "/certificado/simulador", simulador.dueno(), "")));
        jdbc.update("update credencial_arca set clave_privada = concat(clave_privada, x'00') where ferreteria_id = ? "
                + "and estado = 'ACTIVA'", homologacion.id());
        respuestas.add(texto(getResult(URL + "/configuracion", homologacion.dueno())));

        assertThat(salida.getAll()).contains("NO_SE_DESCIFRA");
        List<String> textos = concatenar(respuestas, salida.getAll());
        for (int i = 0; i < textos.size(); i++) {
            // Sin volcar el texto entero (el log es largo): solo dónde está lo que no tendría que estar.
            String donde = i < respuestas.size() ? "la respuesta " + i : "el log";
            assertThat(textos.get(i).contains("PRIVATE KEY")).as("«PRIVATE KEY» en " + donde).isFalse();
            // Sin espacios ni saltos (ni \n escapados de un JSON): un secreto partido en líneas también se encuentra.
            String corrido = textos.get(i).replaceAll("\\\\[nr]|\\s", "");
            for (String secreto : secretos) {
                assertThat(corrido.contains(secreto)).as("«" + secreto + "» en " + donde).isFalse();
            }
        }
    }

    /** El cuerpo de la respuesta, tal cual (UTF-8). */
    private static String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Dos rodajas de 60 caracteres del dato en Base64, en hex y en HEX: lo que se buscaría si alguien lo filtrara. */
    private static List<String> rodajas(byte[] dato) {
        List<String> rodajas = new ArrayList<>();
        for (String forma : List.of(Base64.getEncoder().encodeToString(dato), HexFormat.of().formatHex(dato),
                HexFormat.of().withUpperCase().formatHex(dato))) {
            rodajas.add(forma.substring(forma.length() / 4, forma.length() / 4 + 60));
            rodajas.add(forma.substring(forma.length() * 3 / 4, forma.length() * 3 / 4 + 60));
        }
        return rodajas;
    }

    private static List<String> concatenar(List<String> respuestas, String log) {
        List<String> todo = new ArrayList<>(respuestas);
        todo.add(log);
        return todo;
    }
}
