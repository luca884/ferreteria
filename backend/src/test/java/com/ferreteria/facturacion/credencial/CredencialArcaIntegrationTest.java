package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * La credencial de ARCA del Dueño por HTTP (CU-01 pasos 2 y 3, rama 5, C1): el punto de venta (S2), el pedido de
 * certificado generado en el servidor (S3), el .crt validado contra la CA del ambiente (S4, S14), la firma con la CA del
 * simulador (S5), una sola activa y un solo pendiente (S15) y el ambiente que fija el certificado (S16).
 */
class CredencialArcaIntegrationTest extends CredencialArcaTestBase {

    private static final String SIN_PEDIDO = "No hay un pedido de certificado pendiente.";

    @Autowired
    CifradorDeClaves cifrador;

    // ---- S2: la configuración y el punto de venta ----

    @Test
    @DisplayName("una ferretería sin configuración: sin ambiente ni punto de venta, editable, sin credencial ni pedido, y "
            + "leerla no crea la fila")
    void unaFerreteriaNuevaNoTieneNada() throws Exception {
        Ferreteria f = ferreteria();

        JsonNode config = configuracion(f);

        assertThat(config.get("ambiente").isNull()).isTrue();
        assertThat(config.get("puntoVenta").isNull()).isTrue();
        assertThat(config.get("puntoVentaEditable").asBoolean()).isTrue();
        assertThat(config.get("motivoPuntoVentaTrabado").isNull()).isTrue();
        assertThat(config.get("facturacionHabilitada").asBoolean()).isFalse();
        assertThat(config.get("simulador").asBoolean()).isFalse();
        assertThat(config.get("credencial").isNull()).isTrue();
        assertThat(config.get("pedido").isNull()).isTrue();
        assertThat(config.get("avisos")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Integer.class, f.id()))
                .isZero();
    }

    @Test
    @DisplayName("el Dueño carga el punto de venta de 1 a 99998; fuera de rango, 400 en «puntoVenta» y nada cambia")
    void elDuenoCargaElPuntoDeVenta() throws Exception {
        Ferreteria f = ferreteria();

        putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.puntoVenta", is(3))).andExpect(jsonPath("$.puntoVentaEditable", is(true)));
        assertThat(config(f)).containsEntry("punto_venta", 3).containsEntry("ambiente", null);
        for (String invalido : List.of("0", "99999", "-1", "null")) {
            putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": " + invalido + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.puntoVenta").exists());
        }
        putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 99998}").andExpect(status().isOk());

        assertThat(configuracion(f).get("puntoVenta").asInt()).isEqualTo(99998);
    }

    @Test
    @DisplayName("el punto de venta se traba con el simulador o con registros abiertos: 409 con el motivo, que el GET muestra")
    void elPuntoDeVentaSeTraba() throws Exception {
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);
        Ferreteria conAbiertos = ferreteria();
        putJson(URL + "/punto-venta", conAbiertos.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isOk());
        registroAbierto(conAbiertos, "SIMULADOR");

        putJson(URL + "/punto-venta", simulador.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Con el simulador el punto de venta es 1.")));
        putJson(URL + "/punto-venta", conAbiertos.dueno(), "{\"puntoVenta\": 4}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Hay facturas en trámite con ARCA: el punto de venta no se puede cambiar.")));

        JsonNode deSimulador = configuracion(simulador);
        assertThat(deSimulador.get("puntoVenta").asInt()).isEqualTo(1);
        assertThat(deSimulador.get("puntoVentaEditable").asBoolean()).isFalse();
        assertThat(deSimulador.get("motivoPuntoVentaTrabado").asText()).isEqualTo("Con el simulador el punto de venta es 1.");
        assertThat(deSimulador.get("simulador").asBoolean()).isTrue();
        assertThat(deSimulador.get("ambiente").asText()).isEqualTo("SIMULADOR");
        JsonNode deAbiertos = configuracion(conAbiertos);
        assertThat(deAbiertos.get("puntoVenta").asInt()).isEqualTo(3);
        assertThat(deAbiertos.get("puntoVentaEditable").asBoolean()).isFalse();
        assertThat(deAbiertos.get("motivoPuntoVentaTrabado").asText())
                .isEqualTo("Hay facturas en trámite con ARCA: el punto de venta no se puede cambiar.");
    }

    // ---- S3: el pedido de certificado ----

    @Test
    @DisplayName("genera el pedido: CSR PKCS#10 con el sujeto exacto, firmado con SHA256withRSA y clave RSA 2048; la clave "
            + "privada queda cifrada y corresponde al CSR")
    void generaElPedidoDeCertificado() throws Exception {
        Ferreteria f = ferreteria();

        JsonNode respuesta = cuerpo(postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": null}")
                .andExpect(status().isCreated()));

        String pem = respuesta.get("csr").asText();
        assertThat(pem).startsWith("-----BEGIN CERTIFICATE REQUEST-----\n").endsWith("-----END CERTIFICATE REQUEST-----\n");
        assertThat(respuesta.get("archivo").asText()).isEqualTo("pedido-certificado-" + f.cuit() + ".csr");
        PKCS10CertificationRequest csr = csr(pem);
        RDN[] rdns = csr.getSubject().getRDNs();
        assertThat(Stream.of(rdns).map(rdn -> rdn.getFirst().getType()).toList())
                .containsExactly(BCStyle.C, BCStyle.O, BCStyle.CN, BCStyle.SERIALNUMBER);
        assertThat(valor(csr.getSubject(), BCStyle.C)).isEqualTo("AR");
        assertThat(valor(csr.getSubject(), BCStyle.O)).isEqualTo(f.razonSocial());
        assertThat(valor(csr.getSubject(), BCStyle.CN)).isEqualTo("Ferreteria SaaS");
        assertThat(valor(csr.getSubject(), BCStyle.SERIALNUMBER)).isEqualTo("CUIT " + f.cuit());
        assertThat(csr.getSignatureAlgorithm().getAlgorithm().getId()).isEqualTo("1.2.840.113549.1.1.11");
        PublicKey publica = publica(pem);
        assertThat(csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publica))).isTrue();
        assertThat(((RSAPublicKey) publica).getModulus().bitLength()).isEqualTo(2048);

        // El mismo pedido se vuelve a bajar, y la configuración lo muestra con su huella.
        JsonNode otraVez = getJson(URL + "/pedido-certificado", f.dueno());
        assertThat(otraVez.get("csr").asText()).isEqualTo(pem);
        assertThat(otraVez.get("archivo").asText()).isEqualTo("pedido-certificado-" + f.cuit() + ".csr");
        JsonNode pedido = configuracion(f).get("pedido");
        String huella = huella(publica);
        assertThat(pedido.get("huella").asText()).isEqualTo(conDosPuntos(huella));
        assertThat(pedido.get("cuit").asText()).isEqualTo(f.cuit());
        assertThat(Instant.parse(pedido.get("creadoEn").asText())).isEqualTo(AHORA);

        // En la base, la clave está cifrada (no es el PKCS#8 en claro) y es la privada del CSR.
        Map<String, Object> fila = jdbc.queryForMap("select estado, huella, clave_privada, pedido from credencial_arca "
                + "where ferreteria_id = ?", f.id());
        assertThat(fila).containsEntry("estado", "PENDIENTE").containsEntry("huella", huella).containsEntry("pedido", pem);
        byte[] cifrada = (byte[]) fila.get("clave_privada");
        byte[] claro = cifrador.descifrar(cifrada, new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, f.id(), huella));
        RSAPrivateCrtKey privada = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(claro));
        assertThat(privada.getModulus()).isEqualTo(((RSAPublicKey) publica).getModulus());
        assertThat(HexFormat.of().formatHex(cifrada)).doesNotContain(HexFormat.of().formatHex(claro).substring(0, 64));
    }

    @Test
    @DisplayName("con una razón social de 150 caracteres, el CSR se parsea, su firma verifica y la O lleva los primeros 64 "
            + "(code points, sin el espacio que quede al final del recorte)")
    void laRazonSocialLargaSeRecortaEnLaO() throws Exception {
        record Caso(String razonSocial, String o, int largo) {}
        List<Caso> casos = List.of(
                // Una letra fuera del plano básico (dos char de UTF-16) entre los primeros 64: se cuentan code points.
                new Caso("Ferretería 𝔐ar del Plata " + "Ferretería Mar del Plata ".repeat(5),
                        "Ferretería 𝔐ar del Plata Ferretería Mar del Plata Ferretería Mar", 64),
                // El code point 64 es el espacio después de «Sur»: el recorte termina en él y strip() lo saca.
                new Caso("Ferretería Mar del Plata ".repeat(2) + "Bulonería Sur " + "Ferretería Mar del Plata ".repeat(3)
                        + "Tornillería", "Ferretería Mar del Plata Ferretería Mar del Plata Bulonería Sur", 63));

        for (Caso caso : casos) {
            assertThat(caso.razonSocial().codePointCount(0, caso.razonSocial().length())).isEqualTo(150);
            Ferreteria f = ferreteria();
            jdbc.update("update ferreteria set razon_social = ? where id = ?", caso.razonSocial(), f.id());

            String pem = pedido(f);

            PKCS10CertificationRequest csr = csr(pem);
            assertThat(csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publica(pem)))).isTrue();
            RDN[] organizacion = csr.getSubject().getRDNs(BCStyle.O);
            assertThat(organizacion).hasSize(1);
            String o = ((ASN1String) organizacion[0].getFirst().getValue()).getString();
            assertThat(o).isEqualTo(caso.o());
            assertThat(o.codePointCount(0, o.length())).isEqualTo(caso.largo());
            assertThat(valor(csr.getSubject(), BCStyle.SERIALNUMBER)).isEqualTo("CUIT " + f.cuit());
        }
    }

    @Test
    @DisplayName("un pedido nuevo reemplaza al pendiente y deja la credencial activa como estaba")
    void unPedidoNuevoReemplazaAlPendienteYDejaLaActiva() throws Exception {
        Ferreteria f = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        String primero = pedido(f);
        List<Map<String, Object>> antes = credenciales(f);

        String segundo = pedido(f);

        assertThat(segundo).isNotEqualTo(primero);
        List<Map<String, Object>> despues = credenciales(f);
        assertThat(despues).hasSize(2);
        assertThat(despues.get(0)).isEqualTo(antes.get(0)).containsEntry("estado", "ACTIVA");
        assertThat(despues.get(1)).containsEntry("estado", "PENDIENTE").containsEntry("huella", huella(publica(segundo)));
        assertThat(getJson(URL + "/pedido-certificado", f.dueno()).get("csr").asText()).isEqualTo(segundo);
    }

    @Test
    @DisplayName("sin pedido pendiente: bajarlo da 404, subir el .crt da 409 y firmar con el simulador da 409")
    void sinPedidoPendiente() throws Exception {
        Ferreteria f = ferreteria();
        conSimulador(f);

        getResult(URL + "/pedido-certificado", f.dueno()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is(SIN_PEDIDO)));
        X509Certificate certificado = CasDeArcaDePrueba.HOMOLOGACION.emitir(AutoridadDePrueba.rsa(2048).getPublic(),
                AutoridadDePrueba.sujeto(f.razonSocial(), f.cuit()), AHORA.minus(DIA), AHORA.plus(DIA));
        subir(f, pem(certificado)).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(SIN_PEDIDO)));
        postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_PEDIDO)));

        assertThat(credenciales(f)).isEmpty();
    }

    // ---- S14: el CUIT editable del pedido ----

    @Test
    @DisplayName("el CUIT del pedido se edita fuera de producción (la persona física de homologación); en producción, 400")
    void elCuitDelPedidoSeEditaSoloFueraDeProduccion() throws Exception {
        Ferreteria f = ferreteria();
        String deLaPersona = "20123456786";

        JsonNode sinAmbiente = cuerpo(postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": \"20-12345678-6\"}")
                .andExpect(status().isCreated()));
        assertThat(valor(csr(sinAmbiente.get("csr").asText()).getSubject(), BCStyle.SERIALNUMBER))
                .isEqualTo("CUIT " + deLaPersona);
        assertThat(sinAmbiente.get("archivo").asText()).isEqualTo("pedido-certificado-" + deLaPersona + ".csr");
        assertThat(configuracion(f).get("pedido").get("cuit").asText()).isEqualTo(deLaPersona);
        postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": \"20-12345678-5\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cuit", is("El CUIT no es válido: revisá los números, el último no coincide.")));

        activa(f, CasDeArcaDePrueba.PRODUCCION);
        List<Map<String, Object>> antes = credenciales(f);
        postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": \"" + deLaPersona + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cuit", is("En producción el certificado lleva el CUIT de la ferretería.")));
        assertThat(credenciales(f)).isEqualTo(antes);
        // El de la ferretería sí, y sin cuerpo también.
        postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": \"" + f.cuit() + "\"}").andExpect(status().isCreated());
        postJson(URL + "/pedido-certificado", f.dueno(), "").andExpect(status().isCreated());
    }

    // ---- S4: el .crt validado ----

    @Test
    @DisplayName("sube el .crt de homologación: fija el ambiente, activa la credencial sin secretos y no habilita la facturación")
    void activaUnCertificadoDeHomologacion() throws Exception {
        Ferreteria f = ferreteria();
        String csr = pedido(f);
        X509Certificate certificado = emitido(CasDeArcaDePrueba.HOMOLOGACION, csr, f, f.cuit(),
                Instant.parse("2026-10-28T00:00:00Z"), Instant.parse("2028-10-27T23:00:00Z"));

        JsonNode config = cuerpo(subir(f, pem(certificado)).andExpect(status().isOk()));

        assertThat(config.get("ambiente").asText()).isEqualTo("HOMOLOGACION");
        assertThat(config.get("facturacionHabilitada").asBoolean()).isFalse();
        assertThat(config.get("pedido").isNull()).isTrue();
        assertThat(config.get("avisos")).isEmpty();
        JsonNode credencial = config.get("credencial");
        assertThat(credencial.get("cuit").asText()).isEqualTo(f.cuit());
        assertThat(credencial.get("emisor").asText()).isEqualTo("Computadores Test");
        assertThat(credencial.get("ambiente").asText()).isEqualTo("HOMOLOGACION");
        assertThat(credencial.get("vigenteDesde").asText()).isEqualTo("2026-10-27");
        assertThat(credencial.get("vigenteHasta").asText()).isEqualTo("2028-10-27");
        assertThat(credencial.get("huella").asText()).isEqualTo(conDosPuntos(huella(publica(csr))));
        assertThat(credencial.get("estado").asText()).isEqualTo("VIGENTE");
        assertThat(credencial.get("diasParaVencer").asLong()).isEqualTo(730);
        assertThat(configuracion(f)).isEqualTo(config);
        assertThat(config(f)).containsEntry("ambiente", "HOMOLOGACION").containsEntry("facturacion_habilitada", false);
        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila)
                .containsEntry("estado", "ACTIVA").containsEntry("ambiente", "HOMOLOGACION"));
        assertThat(jdbc.queryForObject("select certificado from credencial_arca where ferreteria_id = ?", String.class,
                f.id())).isEqualTo(pem(certificado));
    }

    @Test
    @DisplayName("el .crt entra en DER en Base64 (en una línea o con saltos MIME), y en PEM con fines de línea de Windows "
            + "(CRLF) o de Mac viejo (CR)")
    void aceptaDerEnBase64YFinesDeLinea() throws Exception {
        for (String formato : List.of("DER", "DER_MIME", "CRLF", "CR")) {
            Ferreteria f = ferreteria();
            X509Certificate certificado = emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f);
            String texto = switch (formato) {
                case "DER" -> Base64.getEncoder().encodeToString(certificado.getEncoded());
                case "DER_MIME" -> Base64.getMimeEncoder().encodeToString(certificado.getEncoded());
                case "CRLF" -> pem(certificado).replace("\n", "\r\n");
                default -> pem(certificado).replace("\n", "\r");
            };

            subir(f, texto).andExpect(status().isOk()).andExpect(jsonPath("$.credencial.estado", is("VIGENTE")));
            assertThat(jdbc.queryForObject("select certificado from credencial_arca where ferreteria_id = ?", String.class,
                    f.id())).as(formato).isEqualTo(pem(certificado));
        }
    }

    static Stream<Arguments> certificadosRechazados() {
        return Stream.of(
                Arguments.of("no corresponde a la clave del pedido",
                        "El certificado no corresponde al pedido pendiente: subí el que ARCA emitió para el último pedido "
                                + "que generaste."),
                Arguments.of("de otro CUIT en producción",
                        "El certificado es de otra CUIT (20123456786): en producción tiene que ser la de la ferretería."),
                Arguments.of("vencido", "El certificado venció el 27/10/2026: generá un pedido nuevo y pedí otro en ARCA."),
                Arguments.of("todavía no vigente", "El certificado empieza a valer el 29/10/2026: subilo desde ese día."),
                Arguments.of("con clave de 1024 bits", "La clave del certificado tiene que ser RSA de 2048 bits o más."),
                Arguments.of("con clave EC firmada por la CA de ARCA",
                        "La clave del certificado tiene que ser RSA de 2048 bits o más."),
                Arguments.of("de producción sin serialNumber",
                        "El certificado no dice de qué CUIT es: en producción tiene que ser la de la ferretería."),
                Arguments.of("de producción con el CUIT con guiones en el serialNumber",
                        "El certificado no dice de qué CUIT es: en producción tiene que ser la de la ferretería."),
                Arguments.of("que vence en este mismo instante",
                        "El certificado venció el 28/10/2026: generá un pedido nuevo y pedí otro en ARCA."),
                Arguments.of("de más de 16 KB", "El certificado pesa más de 16 KB: no parece un .crt de ARCA."),
                Arguments.of("mal formado",
                        "No se pudo leer el certificado: subí el .crt que bajaste de ARCA (PEM, o DER en Base64)."),
                Arguments.of("firmado por otra CA",
                        "El certificado no lo firmó ARCA: subí el .crt que bajaste de ARCA, de homologación o de producción."),
                Arguments.of("una clave privada pegada",
                        "Eso es una clave privada: subí solo el certificado (.crt). La clave nunca sale del sistema."),
                Arguments.of("vacío", "Pegá el certificado (.crt) que te dio ARCA."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("certificadosRechazados")
    @DisplayName("rechaza con un mensaje claro en «certificado» (400), y el pedido sigue pendiente sin credencial activa")
    void rechazaElCertificado(String caso, String mensaje) throws Exception {
        Ferreteria f = ferreteria();
        String csr = pedido(f);
        PublicKey publica = publica(csr);
        AutoridadDePrueba homologacion = CasDeArcaDePrueba.HOMOLOGACION;
        String texto = switch (caso) {
            case "no corresponde a la clave del pedido" -> pem(homologacion.emitir(AutoridadDePrueba.rsa(2048).getPublic(),
                    AutoridadDePrueba.sujeto(f.razonSocial(), f.cuit()), AHORA.minus(DIA), AHORA.plus(DIA)));
            case "de otro CUIT en producción" -> pem(emitido(CasDeArcaDePrueba.PRODUCCION, csr, f, "20123456786",
                    AHORA.minus(DIA), AHORA.plus(DIA)));
            case "vencido" -> pem(emitido(homologacion, csr, f, f.cuit(), AHORA.minus(DIA.multipliedBy(10)),
                    AHORA.minus(DIA)));
            case "todavía no vigente" -> pem(emitido(homologacion, csr, f, f.cuit(), AHORA.plus(DIA),
                    AHORA.plus(DIA.multipliedBy(10))));
            case "con clave de 1024 bits" -> pem(homologacion.emitir(AutoridadDePrueba.rsa(1024).getPublic(),
                    AutoridadDePrueba.sujeto(f.razonSocial(), f.cuit()), AHORA.minus(DIA), AHORA.plus(DIA)));
            case "con clave EC firmada por la CA de ARCA" -> pem(homologacion.emitir(claveEc(),
                    AutoridadDePrueba.sujeto(f.razonSocial(), f.cuit()), AHORA.minus(DIA), AHORA.plus(DIA)));
            case "de producción sin serialNumber" -> pem(CasDeArcaDePrueba.PRODUCCION.emitir(publica,
                    sinSerialNumber(f), AHORA.minus(DIA), AHORA.plus(DIA)));
            case "de producción con el CUIT con guiones en el serialNumber" -> pem(CasDeArcaDePrueba.PRODUCCION.emitir(
                    publica, AutoridadDePrueba.sujeto(f.razonSocial(), conGuiones(f.cuit())), AHORA.minus(DIA),
                    AHORA.plus(DIA)));
            case "que vence en este mismo instante" -> pem(emitido(homologacion, csr, f, f.cuit(), AHORA.minus(DIA), AHORA));
            case "de más de 16 KB" -> pem(emitido(homologacion, csr, f)) + "\n".repeat(16 * 1024);
            case "mal formado" -> "-----BEGIN CERTIFICATE-----\nnoesuncertificado\n-----END CERTIFICATE-----\n";
            case "firmado por otra CA" -> pem(emitido(CasDeArcaDePrueba.AJENA, csr, f));
            case "una clave privada pegada" -> "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n"
                    + "-----END PRIVATE KEY-----\n";
            default -> "  ";
        };
        assertThat(publica).isNotNull();

        subir(f, texto).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.certificado", is(mensaje)));

        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "PENDIENTE"));
        assertThat(configuracion(f).get("ambiente").isNull()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"seguido de la CA (cadena)", "vigente desde este mismo instante",
            "de homologación sin serialNumber"})
    @DisplayName("entra, queda activa y se guarda solo el certificado")
    void aceptaElCertificado(String caso) throws Exception {
        Ferreteria f = ferreteria();
        String csr = pedido(f);
        AutoridadDePrueba homologacion = CasDeArcaDePrueba.HOMOLOGACION;
        X509Certificate certificado = switch (caso) {
            case "vigente desde este mismo instante" -> emitido(homologacion, csr, f, f.cuit(), AHORA,
                    AHORA.plus(DIA.multipliedBy(730)));
            case "de homologación sin serialNumber" -> homologacion.emitir(publica(csr), sinSerialNumber(f),
                    AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(730)));
            default -> emitido(homologacion, csr, f);
        };
        String texto = caso.contains("cadena") ? pem(certificado) + pem(homologacion.certificado()) : pem(certificado);

        subir(f, texto).andExpect(status().isOk()).andExpect(jsonPath("$.ambiente", is("HOMOLOGACION")))
                .andExpect(jsonPath("$.credencial.estado", is("VIGENTE")));

        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "ACTIVA"));
        assertThat(jdbc.queryForObject("select certificado from credencial_arca where ferreteria_id = ?", String.class,
                f.id())).isEqualTo(pem(certificado));
    }

    @Test
    @DisplayName("el tope de 16 KB es exacto: con 16384 bytes entra y con 16385 da 400, y el pedido sigue pendiente")
    void elTopeDe16KbEsExacto() throws Exception {
        Ferreteria f = ferreteria();
        String pem = pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f));
        String justo = pem + "\n".repeat(16 * 1024 - pem.length());
        String pasado = justo + "\n";
        assertThat(justo.getBytes(StandardCharsets.UTF_8)).hasSize(16 * 1024);

        subir(f, pasado).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.certificado",
                is("El certificado pesa más de 16 KB: no parece un .crt de ARCA.")));
        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "PENDIENTE"));
        subir(f, justo).andExpect(status().isOk()).andExpect(jsonPath("$.credencial.estado", is("VIGENTE")));
    }

    @Test
    @DisplayName("homologación y producción salen de la firma verificada, no del texto del emisor")
    void elAmbienteSaleDeLaFirma() throws Exception {
        Ferreteria f = ferreteria();
        String csr = pedido(f);
        X500Name comoProduccion = X500Name.getInstance(
                CasDeArcaDePrueba.PRODUCCION.certificado().getSubjectX500Principal().getEncoded());
        // Lo firma la CA de homologación, pero dice que lo emitió la de producción.
        X509Certificate certificado = CasDeArcaDePrueba.HOMOLOGACION.emitir(publica(csr),
                AutoridadDePrueba.sujeto(f.razonSocial(), f.cuit()), AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(30)),
                comoProduccion);
        assertThat(certificado.getIssuerX500Principal().getName()).contains("CN=Computadores,");

        subir(f, pem(certificado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ambiente", is("HOMOLOGACION")))
                .andExpect(jsonPath("$.credencial.emisor", is("Computadores Test")));
    }

    @Test
    @DisplayName("un .crt de homologación con otra CUIT se acepta con el aviso; el aviso sigue en la configuración")
    void otraCuitEnHomologacionAvisa() throws Exception {
        Ferreteria f = ferreteria();
        X509Certificate certificado = emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f, "20123456786",
                AHORA.minus(DIA), AHORA.plus(DIA.multipliedBy(730)));
        String aviso = "El certificado es de otra CUIT (20123456786): sirve solo para homologación.";

        subir(f, pem(certificado)).andExpect(status().isOk()).andExpect(jsonPath("$.avisos[0]", is(aviso)))
                .andExpect(jsonPath("$.credencial.cuit", is("20123456786")));

        assertThat(configuracion(f).get("avisos")).singleElement().satisfies(a -> assertThat(a.asText()).isEqualTo(aviso));
    }

    // ---- S16 y S15: el ambiente que fija el certificado, y una sola activa ----

    @Test
    @DisplayName("de homologación a producción: sin registros abiertos pasa y la activa anterior se borra con su clave; con "
            + "abiertos, 409")
    void deHomologacionAProduccion() throws Exception {
        Ferreteria f = ferreteria();
        X509Certificate deHomologacion = activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        String vieja = huella(deHomologacion.getPublicKey());
        registroAbierto(f, "HOMOLOGACION");
        X509Certificate deProduccion = emitido(CasDeArcaDePrueba.PRODUCCION, pedido(f), f);

        subir(f, pem(deProduccion)).andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(
                "La ferretería tiene comprobantes fiscales sin terminar: el ambiente no se puede cambiar hasta que se "
                        + "resuelvan.")));
        assertThat(config(f)).containsEntry("ambiente", "HOMOLOGACION");

        jdbc.update("update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R' where ferreteria_id = ?", f.id());
        subir(f, pem(deProduccion)).andExpect(status().isOk()).andExpect(jsonPath("$.ambiente", is("PRODUCCION")))
                .andExpect(jsonPath("$.credencial.emisor", is("Computadores")));

        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "ACTIVA")
                .containsEntry("ambiente", "PRODUCCION").containsEntry("huella", huella(deProduccion.getPublicKey())));
        assertThat(jdbc.queryForObject("select count(*) from credencial_arca where huella = ?", Integer.class, vieja))
                .isZero();
    }

    @Test
    @DisplayName("con la facturación habilitada en homologación, activar un certificado de producción deja PRODUCCION y la "
            + "deshabilita; renovar el de homologación la deja prendida (rama 6, S14)")
    void elCertificadoDeProduccionDeshabilitaLaFacturacion() throws Exception {
        String admin = login("admin@demo.local");
        Ferreteria aProduccion = ferreteria();
        Ferreteria renueva = ferreteria();
        for (Ferreteria f : List.of(aProduccion, renueva)) {
            activa(f, CasDeArcaDePrueba.HOMOLOGACION);
            putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 3}").andExpect(status().isOk());
            patchAdmin(admin, f, "facturacion", "{\"habilitada\": true}").andExpect(status().isOk());
        }

        subir(aProduccion, pem(emitido(CasDeArcaDePrueba.PRODUCCION, pedido(aProduccion), aProduccion)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ambiente", is("PRODUCCION")));
        activa(renueva, CasDeArcaDePrueba.HOMOLOGACION);

        assertThat(config(aProduccion)).containsEntry("ambiente", "PRODUCCION").containsEntry("punto_venta", 3)
                .containsEntry("facturacion_habilitada", false);
        assertThat(jdbc.queryForObject("select habilitada_en from config_arca where ferreteria_id = ?", Instant.class,
                aProduccion.id())).isNull();
        assertThat(config(renueva)).containsEntry("ambiente", "HOMOLOGACION").containsEntry("facturacion_habilitada", true);
    }

    @Test
    @DisplayName("desde producción no se vuelve a homologación, y con el simulador no entra un .crt de ARCA: 400 en «certificado»")
    void elCertificadoNoVuelveNiReemplazaAlSimulador() throws Exception {
        Ferreteria produccion = ferreteria();
        activa(produccion, CasDeArcaDePrueba.PRODUCCION);
        Ferreteria simulador = ferreteria();
        conSimulador(simulador);

        subir(produccion, pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(produccion), produccion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.certificado", is("Desde producción no se vuelve a homologación.")));
        for (AutoridadDePrueba deArca : List.of(CasDeArcaDePrueba.HOMOLOGACION, CasDeArcaDePrueba.PRODUCCION)) {
            subir(simulador, pem(emitido(deArca, pedido(simulador), simulador))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.certificado",
                            is("Con el simulador, el certificado lo firma el simulador: usá «Firmar con el simulador».")));
        }

        assertThat(config(produccion)).containsEntry("ambiente", "PRODUCCION");
        assertThat(credenciales(produccion)).extracting(fila -> fila.get("estado")).containsExactly("ACTIVA", "PENDIENTE");
        assertThat(config(simulador)).containsEntry("ambiente", "SIMULADOR");
        assertThat(credenciales(simulador)).extracting(fila -> fila.get("estado")).containsExactly("PENDIENTE");
    }

    // ---- S5: la CA del simulador ----

    @Test
    @DisplayName("en SIMULADOR, «Firmar con el simulador» emite y activa la credencial; la CA se crea una vez, con su clave cifrada")
    void elSimuladorFirmaConUnClic() throws Exception {
        Ferreteria f = ferreteria();
        conSimulador(f);
        String csr = pedido(f);

        JsonNode config = cuerpo(postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isOk()));

        assertThat(config.get("ambiente").asText()).isEqualTo("SIMULADOR");
        JsonNode credencial = config.get("credencial");
        assertThat(credencial.get("emisor").asText()).isEqualTo("Simulador de ARCA");
        assertThat(credencial.get("ambiente").asText()).isEqualTo("SIMULADOR");
        assertThat(credencial.get("cuit").asText()).isEqualTo(f.cuit());
        assertThat(credencial.get("estado").asText()).isEqualTo("VIGENTE");
        assertThat(credencial.get("huella").asText()).isEqualTo(conDosPuntos(huella(publica(csr))));
        assertThat(config.get("pedido").isNull()).isTrue();
        Map<String, Object> ca = jdbc.queryForMap("select id, huella, clave_privada, certificado from ca_simulador "
                + "where ferreteria_id = ?", f.id());
        byte[] claveDeLaCa = cifrador.descifrar((byte[]) ca.get("clave_privada"),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, f.id(), (String) ca.get("huella")));
        assertThat(claveDeLaCa).isNotEmpty();
        assertThat(jdbc.queryForObject("select ca_simulador_id from credencial_arca where ferreteria_id = ?", Long.class,
                f.id())).isEqualTo(ca.get("id"));

        // Un segundo certificado lo firma la misma CA, y reemplaza al anterior.
        pedido(f);
        postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isOk());
        assertThat(jdbc.queryForList("select id from ca_simulador where ferreteria_id = ?", Long.class, f.id()))
                .containsExactly((Long) ca.get("id"));
        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "ACTIVA"));
    }

    @Test
    @DisplayName("fuera de SIMULADOR, «Firmar con el simulador» no existe: 409 y el pedido sigue pendiente")
    void firmarConElSimuladorFueraDelSimulador() throws Exception {
        Ferreteria sinAmbiente = ferreteria();
        pedido(sinAmbiente);
        Ferreteria homologacion = ferreteria();
        activa(homologacion, CasDeArcaDePrueba.HOMOLOGACION);
        pedido(homologacion);

        for (Ferreteria f : List.of(sinAmbiente, homologacion)) {
            postJson(URL + "/certificado/simulador", f.dueno(), "").andExpect(status().isConflict()).andExpect(jsonPath(
                    "$.message", is("«Firmar con el simulador» es solo para la ferretería de prueba que usa el simulador "
                            + "de ARCA.")));
        }

        assertThat(credenciales(sinAmbiente)).extracting(fila -> fila.get("estado")).containsExactly("PENDIENTE");
        assertThat(jdbc.queryForObject("select count(*) from ca_simulador where ferreteria_id in (?, ?)", Integer.class,
                sinAmbiente.id(), homologacion.id())).isZero();
    }

    @Test
    @DisplayName("la CA del simulador es una por ferretería: dos ferreterías tienen CA con huellas distintas y cada credencial "
            + "apunta a la suya")
    void cadaFerreteriaTieneSuCaDelSimulador() throws Exception {
        Ferreteria a = ferreteria();
        conSimulador(a);
        pedido(a);
        postJson(URL + "/certificado/simulador", a.dueno(), "").andExpect(status().isOk());
        Ferreteria b = ferreteria();
        conSimulador(b);
        pedido(b);
        postJson(URL + "/certificado/simulador", b.dueno(), "").andExpect(status().isOk());

        Map<String, Object> caDeA = caDelSimulador(a);
        Map<String, Object> caDeB = caDelSimulador(b);

        assertThat(caDeA.get("id")).isNotEqualTo(caDeB.get("id"));
        assertThat(caDeA.get("huella")).isNotEqualTo(caDeB.get("huella"));
        assertThat(caDeA.get("certificado")).isNotEqualTo(caDeB.get("certificado"));
        assertThat(jdbc.queryForObject("select ca_simulador_id from credencial_arca where ferreteria_id = ?", Long.class,
                a.id())).isEqualTo(caDeA.get("id"));
        assertThat(jdbc.queryForObject("select ca_simulador_id from credencial_arca where ferreteria_id = ?", Long.class,
                b.id())).isEqualTo(caDeB.get("id"));
    }

    @Test
    @DisplayName("el .crt de la CA del simulador no es de ARCA: sin ambiente y en homologación da 400, y sin simulador «Firmar "
            + "con el simulador» da 409")
    void elCertificadoDeLaCaDelSimuladorNoEsDeArca() throws Exception {
        Ferreteria a = ferreteria();
        conSimulador(a);
        pedido(a);
        postJson(URL + "/certificado/simulador", a.dueno(), "").andExpect(status().isOk());
        // El super admin le saca el simulador: queda sin ambiente, con su CA y con la credencial que esa CA firmó.
        patchAdmin(login("admin@demo.local"), a, "simulador", "{\"simulador\": false}").andExpect(status().isOk());
        assertThat(config(a)).containsEntry("ambiente", null);
        Ferreteria homologacion = ferreteria();
        activa(homologacion, CasDeArcaDePrueba.HOMOLOGACION);
        String noLoFirmoArca = "El certificado no lo firmó ARCA: subí el .crt que bajaste de ARCA, de homologación o de "
                + "producción.";

        subir(a, pem(firmadoPorLaCaDe(a, pedido(a)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.certificado", is(noLoFirmoArca)));
        postJson(URL + "/certificado/simulador", a.dueno(), "").andExpect(status().isConflict()).andExpect(jsonPath(
                "$.message", is("«Firmar con el simulador» es solo para la ferretería de prueba que usa el simulador de "
                        + "ARCA.")));
        subir(homologacion, pem(firmadoPorLaCaDe(a, pedido(homologacion)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.certificado", is(noLoFirmoArca)));

        assertThat(config(a)).containsEntry("ambiente", null);
        assertThat(credenciales(a)).extracting(fila -> fila.get("estado")).containsExactly("ACTIVA", "PENDIENTE");
        assertThat(config(homologacion)).containsEntry("ambiente", "HOMOLOGACION");
        assertThat(credenciales(homologacion)).extracting(fila -> fila.get("estado")).containsExactly("ACTIVA", "PENDIENTE");
    }

    // ---- ayudas ----

    /** La CA del simulador de la ferretería, con su clave cifrada. */
    private Map<String, Object> caDelSimulador(Ferreteria f) {
        return jdbc.queryForMap("select id, huella, clave_privada, certificado from ca_simulador where ferreteria_id = ?",
                f.id());
    }

    /** El certificado del pedido, firmado con la CA del simulador de esa ferretería (la clave la descifra el test). */
    private X509Certificate firmadoPorLaCaDe(Ferreteria deLaCa, String csr) throws Exception {
        Map<String, Object> ca = caDelSimulador(deLaCa);
        byte[] pkcs8 = cifrador.descifrar((byte[]) ca.get("clave_privada"),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, deLaCa.id(), (String) ca.get("huella")));
        PrivateKey clave = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        X509Certificate certificadoDeLaCa = Certificados.leer(((String) ca.get("certificado"))
                .getBytes(StandardCharsets.US_ASCII));
        return FirmaDelSimulador.firmar(csr, certificadoDeLaCa, clave, AHORA);
    }

    private static PublicKey claveEc() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
        generador.initialize(new ECGenParameterSpec("secp256r1"));
        return generador.generateKeyPair().getPublic();
    }

    /** El sujeto de un certificado de ARCA, pero sin el serialNumber (sin la CUIT). */
    private static X500Name sinSerialNumber(Ferreteria f) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.C, "AR").addRDN(BCStyle.O, f.razonSocial())
                .addRDN(BCStyle.CN, "Ferreteria SaaS").build();
    }

    private static String conGuiones(String cuit) {
        return cuit.substring(0, 2) + "-" + cuit.substring(2, 10) + "-" + cuit.substring(10);
    }

    private static String valor(X500Name nombre, ASN1ObjectIdentifier tipo) {
        RDN[] rdns = nombre.getRDNs(tipo);
        assertThat(rdns).hasSize(1);
        return IETFUtils.valueToString(rdns[0].getFirst().getValue());
    }

    static String conDosPuntos(String huella) {
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(HexFormat.of().parseHex(huella));
    }
}
