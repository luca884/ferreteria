package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Una credencial de ARCA ACTIVA puesta por SQL, para los tests que necesitan firmar (el ticket de acceso, rama 6) sin pasar
 * por el alta del Dueño: una clave RSA 2048 nueva, cifrada con el cifrador del contexto como la guarda el sistema, y su
 * certificado emitido por una {@link AutoridadDePrueba}. Reemplaza a la activa que la ferretería tuviera (como activar otro
 * certificado). No sirve para SIMULADOR: esa credencial apunta a la CA del simulador de la ferretería.
 *
 * @param huella la huella de la clave pública (la de {@code credencial_arca.huella})
 * @param cuit la CUIT del certificado
 * @param certificado el certificado de la credencial
 */
public record CredencialDePrueba(String huella, long cuit, X509Certificate certificado) {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

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

    /** Una hora como la guarda Hibernate ({@code jdbc.time_zone: UTC}): la de UTC, como texto. */
    private static String utc(Instant instante) {
        return HORA.format(LocalDateTime.ofInstant(instante, ZoneOffset.UTC));
    }
}
