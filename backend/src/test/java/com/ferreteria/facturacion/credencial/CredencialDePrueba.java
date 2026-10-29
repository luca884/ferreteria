package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Una credencial de ARCA ACTIVA puesta por SQL, para los tests que necesitan firmar (el ticket de acceso, rama 6) sin pasar
 * por el alta del Dueño: una clave RSA 2048 nueva, cifrada con el cifrador del contexto como la guarda el sistema, y su
 * certificado emitido por una {@link AutoridadDePrueba}. Reemplaza a la activa que la ferretería tuviera (como activar otro
 * certificado). Para SIMULADOR, que desde la rama 6 (S6) también exige una credencial vigente, hay dos:
 * <ul>
 *   <li>{@link #delSimulador}: el alta real del Dueño (el pedido de certificado y la firma con la CA del simulador de la
 *       ferretería, que se crea la primera vez); dos claves RSA nuevas por ferretería;</li>
 *   <li>{@link #activaDelSimulador}: por SQL, con una CA del simulador y una clave de la JVM de los tests (más rápida, para los
 *       tests que facturan con el simulador en cada caso). El simulador en el borde la verifica igual: la CA queda como la de
 *       la ferretería.</li>
 * </ul>
 *
 * @param huella la huella de la clave pública (la de {@code credencial_arca.huella})
 * @param cuit la CUIT del certificado
 * @param certificado el certificado de la credencial
 */
public record CredencialDePrueba(String huella, long cuit, X509Certificate certificado) {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    /** La CA del simulador de los tests, una por JVM (la de una ferretería real la crea su primera firma). */
    private static final AutoridadDePrueba CA_DEL_SIMULADOR = AutoridadDePrueba.nueva(FirmaDelSimulador.NOMBRE);

    /** La activa de la ferretería en ese ambiente, con la CA de {@link CasDeArcaDePrueba} de ese ambiente. */
    public static CredencialDePrueba activa(JdbcTemplate jdbc, CifradorDeClaves cifrador, long ferreteriaId,
            Ambiente ambiente, long cuit, Instant ahora) {
        AutoridadDePrueba ca = switch (ambiente) {
            case HOMOLOGACION -> CasDeArcaDePrueba.HOMOLOGACION;
            case PRODUCCION -> CasDeArcaDePrueba.PRODUCCION;
            case SIMULADOR -> throw new IllegalArgumentException("La credencial del simulador la firma su CA (rama 5).");
        };
        try {
            KeyPair claves = AutoridadDePrueba.rsa(2048);
            String huella = Certificados.huella(claves.getPublic());
            byte[] pkcs8 = claves.getPrivate().getEncoded();
            byte[] cifrada = cifrador.cifrar(pkcs8, new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ferreteriaId, huella));
            Arrays.fill(pkcs8, (byte) 0);
            Instant desde = ahora.minus(Duration.ofDays(1));
            Instant hasta = ahora.plus(Duration.ofDays(730));
            X509Certificate certificado = ca.emitir(claves.getPublic(),
                    AutoridadDePrueba.sujeto("Ferretería de prueba", String.format("%011d", cuit)), desde, hasta);
            jdbc.update("delete from credencial_arca where ferreteria_id = ? and estado = 'ACTIVA'", ferreteriaId);
            jdbc.update("insert into credencial_arca (ferreteria_id, estado, cuit, huella, clave_privada, pedido, creada_en, "
                    + "certificado, ambiente, emisor, vigente_desde, vigente_hasta, activada_en) values (?, 'ACTIVA', ?, ?, ?, "
                    + "'CSR de prueba', ?, ?, ?, ?, ?, ?, ?)", ferreteriaId, cuit, huella, cifrada, utc(ahora),
                    Certificados.pem("CERTIFICATE", certificado.getEncoded()), ambiente.name(), ca.nombre(), utc(desde),
                    utc(hasta), utc(ahora));
            return new CredencialDePrueba(huella, cuit, certificado);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo armar la credencial de prueba.", e);
        }
    }

    /**
     * La activa del simulador de la ferretería, por SQL: la CA del simulador de los tests queda como la de la ferretería (si no
     * tiene una) y firma el certificado, de un día antes de {@code ahora} a dos años después. La clave es una por JVM
     * ({@link ClaveDeLaJvm}), cifrada para esta ferretería.
     *
     * @throws IllegalStateException si la ferretería ya tiene otra CA del simulador (la de un alta real): esa credencial no la
     *     podría firmar; usá {@link #delSimulador}
     */
    public static CredencialDePrueba activaDelSimulador(JdbcTemplate jdbc, CifradorDeClaves cifrador, long ferreteriaId,
            long cuit, Instant ahora) {
        try {
            long caId = caDelSimulador(jdbc, cifrador, ferreteriaId, ahora);
            KeyPair claves = ClaveDeLaJvm.CLAVES;
            String huella = Certificados.huella(claves.getPublic());
            byte[] pkcs8 = claves.getPrivate().getEncoded();
            byte[] cifrada = cifrador.cifrar(pkcs8, new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ferreteriaId, huella));
            Arrays.fill(pkcs8, (byte) 0);
            Instant desde = ahora.minus(Duration.ofDays(1));
            Instant hasta = ahora.plus(Duration.ofDays(730));
            X509Certificate certificado = CA_DEL_SIMULADOR.emitir(claves.getPublic(),
                    AutoridadDePrueba.sujeto("Ferretería de prueba", String.format("%011d", cuit)), desde, hasta);
            jdbc.update("delete from credencial_arca where ferreteria_id = ? and estado = 'ACTIVA'", ferreteriaId);
            jdbc.update("insert into credencial_arca (ferreteria_id, estado, cuit, huella, clave_privada, pedido, creada_en, "
                    + "certificado, ambiente, emisor, vigente_desde, vigente_hasta, activada_en, ca_simulador_id) values (?, "
                    + "'ACTIVA', ?, ?, ?, 'CSR de prueba', ?, ?, 'SIMULADOR', ?, ?, ?, ?, ?)", ferreteriaId, cuit, huella,
                    cifrada, utc(ahora), Certificados.pem("CERTIFICATE", certificado.getEncoded()),
                    CA_DEL_SIMULADOR.nombre(), utc(desde), utc(hasta), utc(ahora), caId);
            return new CredencialDePrueba(huella, cuit, certificado);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo armar la credencial de prueba del simulador.", e);
        }
    }

    /** La CA del simulador de la ferretería: la de los tests, que se guarda si no tiene una. Su id. */
    private static long caDelSimulador(JdbcTemplate jdbc, CifradorDeClaves cifrador, long ferreteriaId, Instant ahora)
            throws Exception {
        String huellaCa = Certificados.huella(CA_DEL_SIMULADOR.certificado().getPublicKey());
        Map<String, Object> existente = jdbc.queryForList("select id, huella from ca_simulador where ferreteria_id = ?",
                ferreteriaId).stream().findFirst().orElse(null);
        if (existente != null) {
            if (!huellaCa.equals(existente.get("huella"))) {
                throw new IllegalStateException("La ferretería " + ferreteriaId + " ya tiene otra CA del simulador.");
            }
            return ((Number) existente.get("id")).longValue();
        }
        byte[] pkcs8 = CA_DEL_SIMULADOR.privada().getEncoded();
        byte[] cifrada = cifrador.cifrar(pkcs8, new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, ferreteriaId,
                huellaCa));
        Arrays.fill(pkcs8, (byte) 0);
        jdbc.update("insert into ca_simulador (ferreteria_id, certificado, huella, clave_privada, creada_en) values (?, ?, ?, "
                + "?, ?)", ferreteriaId, Certificados.pem("CERTIFICATE", CA_DEL_SIMULADOR.certificado().getEncoded()),
                huellaCa, cifrada, utc(ahora));
        return jdbc.queryForObject("select id from ca_simulador where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    /**
     * La credencial activa del simulador de la ferretería, por el alta real (rama 5): el pedido de certificado y la firma con
     * la CA del simulador de la ferretería (que se crea la primera vez), con la hora del {@code Clock} del contexto. La
     * ferretería tiene que tener {@code config_arca} en SIMULADOR. Fija y restituye el {@code TenantContext}.
     */
    public static CredencialDePrueba delSimulador(CredencialArcaService servicio, JdbcTemplate jdbc, long ferreteriaId) {
        Optional<Long> antes = TenantContext.currentFerreteriaId();
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            servicio.generarPedido(null);
            servicio.firmarConElSimulador();
        } finally {
            antes.ifPresentOrElse(TenantContext::setCurrentFerreteriaId, TenantContext::clear);
        }
        Map<String, Object> activa = jdbc.queryForMap("select huella, cuit, certificado from credencial_arca where "
                + "ferreteria_id = ? and estado = 'ACTIVA'", ferreteriaId);
        try {
            X509Certificate certificado = Certificados.leer(((String) activa.get("certificado"))
                    .getBytes(StandardCharsets.US_ASCII));
            return new CredencialDePrueba((String) activa.get("huella"), ((Number) activa.get("cuit")).longValue(),
                    certificado);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer la credencial del simulador.", e);
        }
    }

    /** La clave de las credenciales del simulador por SQL: una RSA 2048 por JVM, generada la primera vez que se usa. */
    private static final class ClaveDeLaJvm {

        static final KeyPair CLAVES = generar();

        private static KeyPair generar() {
            try {
                return AutoridadDePrueba.rsa(2048);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Una hora como la guarda Hibernate ({@code jdbc.time_zone: UTC}): la de UTC, como texto. */
    private static String utc(Instant instante) {
        return HORA.format(LocalDateTime.ofInstant(instante, ZoneOffset.UTC));
    }
}
