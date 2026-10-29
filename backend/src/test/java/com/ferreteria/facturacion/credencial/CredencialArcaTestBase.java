package com.ferreteria.facturacion.credencial;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base de los tests de la credencial de ARCA (rama 5, C1): las CA de ARCA generadas en el momento
 * ({@link CasDeArcaDePrueba}) y el reloj fijo ({@link RelojControlableConfig}), en un mismo contexto para las dos clases.
 * Cada test crea sus ferreterías, con los datos de CU-01 completos, un Dueño y un Vendedor.
 */
@Import({CasDeArcaDePrueba.class, RelojControlableConfig.class})
abstract class CredencialArcaTestBase extends IntegrationTestBase {

    static final String URL = "/api/facturacion";
    static final Instant AHORA = RelojControlableConfig.INICIO;
    static final Duration DIA = Duration.ofDays(1);
    /** Hash de demo1234, el mismo de db/dev-data. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";
    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final AtomicInteger COMPROBANTES = new AtomicInteger(700000);

    @Autowired
    RelojControlable reloj;

    @BeforeEach
    void horaFija() {
        reloj.fijar(AHORA);
    }

    /** Una ferretería de prueba: su id, su CUIT de 11 dígitos, su razón social y los tokens de su Dueño y su Vendedor. */
    record Ferreteria(long id, String cuit, String razonSocial, String dueno, String vendedor) {}

    /** Una ferretería RI con los datos de CU-01 completos, sin configuración de ARCA. */
    Ferreteria ferreteria() throws Exception {
        String cuit = cuitNuevo("30");
        String conGuiones = cuit.substring(0, 2) + "-" + cuit.substring(2, 10) + "-" + cuit.substring(10);
        String razonSocial = "Ferretería " + cuit;
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", razonSocial, conGuiones);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
        String dueno = "dueno-" + cuit + "@credencial.test";
        String vendedor = "vendedor-" + cuit + "@credencial.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, 'Dueño', "
                + "'DUENO')", id, dueno, HASH_DEMO);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, ?, 'Vendedor', "
                + "'VENDEDOR')", id, vendedor, HASH_DEMO);
        return new Ferreteria(id, cuit, razonSocial, login(dueno), login(vendedor));
    }

    /** Un CUIT de 11 dígitos, con el verificador correcto y al azar (los de las demo y de otros tests no chocan). */
    static String cuitNuevo(String prefijo) {
        String base = prefijo + String.format("%08d", ThreadLocalRandom.current().nextInt(50_000_000, 100_000_000));
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (base.charAt(i) - '0') * PESOS[i];
        }
        int dv = 11 - suma % 11;
        if (dv == 10) {
            return cuitNuevo(prefijo);
        }
        return base + (dv == 11 ? 0 : dv);
    }

    /** El simulador asignado por SQL (lo que hace el super admin), con el punto de venta 1. */
    void conSimulador(Ferreteria f) {
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta) values (?, true, 'SIMULADOR', 1)",
                f.id());
    }

    /** La facturación prendida por SQL (hasta la rama 6 el panel no la prende en homologación ni producción, S13). */
    void facturacionPrendida(Ferreteria f) {
        jdbc.update("update config_arca set punto_venta = 3, facturacion_habilitada = true, habilitada_en = "
                + "'2026-10-01 12:00:00' where ferreteria_id = ?", f.id());
    }

    /** PATCH del panel del super admin a {@code /api/admin/ferreterias/{id}/{ruta}} (por ejemplo {@code facturacion}). */
    ResultActions patchAdmin(String admin, Ferreteria f, String ruta, String json) throws Exception {
        return mvc.perform(patch("/api/admin/ferreterias/" + f.id() + "/" + ruta).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ---- la API del Dueño ----

    JsonNode configuracion(Ferreteria f) throws Exception {
        return getJson(URL + "/configuracion", f.dueno());
    }

    /** POST del pedido de certificado; devuelve el CSR en PEM. */
    String pedido(Ferreteria f) throws Exception {
        return cuerpo(postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": null}")
                .andExpect(status().isCreated())).get("csr").asText();
    }

    ResultActions subir(Ferreteria f, String certificado) throws Exception {
        return postJson(URL + "/certificado", f.dueno(),
                objectMapper.writeValueAsString(Map.of("certificado", certificado)));
    }

    JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- certificados ----

    static PKCS10CertificationRequest csr(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return (PKCS10CertificationRequest) parser.readObject();
        }
    }

    static PublicKey publica(String csrPem) throws Exception {
        return new JcaPKCS10CertificationRequest(csr(csrPem)).getPublicKey();
    }

    /** El certificado que ARCA emitiría para el pedido: firmado por esa CA, con ese CUIT, de {@code desde} a {@code hasta}. */
    static X509Certificate emitido(AutoridadDePrueba ca, String csrPem, Ferreteria f, String cuit, Instant desde,
            Instant hasta) throws Exception {
        return ca.emitir(publica(csrPem), AutoridadDePrueba.sujeto(f.razonSocial(), cuit), desde, hasta);
    }

    /** Un certificado de dos años para el pedido, con el CUIT de la ferretería. */
    static X509Certificate emitido(AutoridadDePrueba ca, String csrPem, Ferreteria f) throws Exception {
        return emitido(ca, csrPem, f, f.cuit(), AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(730)));
    }

    /** La huella de la clave pública: SHA-256 de su codificación, en hexa mayúscula y sin separadores (la de la base). */
    static String huella(PublicKey clave) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(clave.getEncoded()));
    }

    static String pem(X509Certificate certificado) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(certificado.getEncoded()) + "\n-----END CERTIFICATE-----\n";
    }

    /** Pide un certificado y sube el que emite esa CA: deja la credencial activa. */
    X509Certificate activa(Ferreteria f, AutoridadDePrueba ca) throws Exception {
        X509Certificate certificado = emitido(ca, pedido(f), f);
        subir(f, pem(certificado)).andExpect(status().isOk());
        return certificado;
    }

    // ---- la base ----

    Map<String, Object> config(Ferreteria f) {
        return jdbc.queryForMap("select ambiente, punto_venta, facturacion_habilitada from config_arca where ferreteria_id = ?",
                f.id());
    }

    List<Map<String, Object>> credenciales(Ferreteria f) {
        return jdbc.queryForList("select estado, huella, ambiente, cuit from credencial_arca where ferreteria_id = ? "
                + "order by estado", f.id());
    }

    /** Un registro fiscal PENDIENTE (abierto) de la ferretería, sobre una venta propia. */
    void registroAbierto(Ferreteria f, String ambiente) {
        int numero = COMPROBANTES.incrementAndGet();
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f.id());
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
                f.id(), numero, unico("credencial"), usuario);
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?",
                Long.class, f.id(), numero);
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, estado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, "
                + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
                + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
                + "values (?, ?, ?, 'FACTURA', 'B', 6, 1, 'PENDIENTE', 99, 0, 5, ?, 'Ferretería', 'RESPONSABLE_INSCRIPTO', "
                + "'Av. Luro 1234', '901-123456-7', '2015-03-01', 121.00, 100.00, 21.00, 0, 0, '2026-10-28 13:00:00')",
                f.id(), comprobante, ambiente, f.cuit());
    }
}
