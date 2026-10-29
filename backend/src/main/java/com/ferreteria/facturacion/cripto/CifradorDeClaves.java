package com.ferreteria.facturacion.cripto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra y descifra las claves privadas que guarda {@code facturacion} (S6): la de la credencial de ARCA de cada ferretería
 * y la de su CA del simulador. AES-256-GCM con la clave maestra {@code ARCA_CLAVE_MAESTRA} (32 bytes en Base64), que vive
 * en una variable de entorno y nunca en el repo ni en la base.
 *
 * <p>Formato guardado, byte a byte:
 * <pre>
 *   [0]        versión del formato: 0x01
 *   [1..8]     id de la clave maestra: los primeros 8 bytes del SHA-256 de la clave
 *   [9..20]    nonce de 12 bytes, aleatorio en cada cifrado (SecureRandom)
 *   [21..]     texto cifrado, con el tag de GCM de 128 bits (16 bytes) al final
 * </pre>
 * El AAD son los 9 bytes de la cabecera (versión e id de clave) seguidos del UTF-8 de
 * {@code "<propósito>|<ferreteria_id>|<huella>"} ({@link ContextoDeCifrado}): un dato no se descifra en otra ferretería,
 * con otro propósito ni con la huella de otra clave pública, y la cabecera no se puede cambiar sin que el tag lo note.
 *
 * <p>Rotación: lo nuevo se cifra siempre con {@code ARCA_CLAVE_MAESTRA}; para leer, el id de clave elige entre esa y
 * {@code ARCA_CLAVE_MAESTRA_ANTERIOR} (opcional), así se rota sin migrar los datos. Un id que no es de ninguna de las dos,
 * o un dato que no se descifra, es una {@link DatoIlegibleException}: se loguea sin contenido y deja ilegible solo la
 * credencial de esa ferretería.
 *
 * <p>La clave maestra se valida al construir el bean, o sea al arrancar (molde de {@code JwtService}): sin la variable, o
 * con algo que no es Base64 de 32 bytes, la aplicación no arranca. Ni el log, ni los mensajes, ni {@link #toString()}
 * muestran la clave, el dato cifrado o el dato en claro.
 */
@Component
public class CifradorDeClaves {

    private static final Logger log = LoggerFactory.getLogger(CifradorDeClaves.class);

    private static final byte VERSION = 1;
    private static final int LARGO_ID = 8;
    private static final int LARGO_NONCE = 12;
    private static final int BITS_TAG = 128;
    private static final int LARGO_CABECERA = 1 + LARGO_ID;
    private static final int LARGO_MINIMO = LARGO_CABECERA + LARGO_NONCE + BITS_TAG / 8;
    private static final String TRANSFORMACION = "AES/GCM/NoPadding";

    private final SecureRandom azar = new SecureRandom();
    private final ClaveMaestra actual;
    private final Optional<ClaveMaestra> anterior;

    public CifradorDeClaves(@Value("${app.facturacion.clave-maestra}") String claveMaestra,
            @Value("${app.facturacion.clave-maestra-anterior:}") String claveAnterior) {
        this.actual = ClaveMaestra.leer("ARCA_CLAVE_MAESTRA (app.facturacion.clave-maestra)", claveMaestra);
        this.anterior = claveAnterior == null || claveAnterior.isBlank() ? Optional.empty()
                : Optional.of(ClaveMaestra.leer("ARCA_CLAVE_MAESTRA_ANTERIOR (app.facturacion.clave-maestra-anterior)",
                        claveAnterior));
    }

    /** Cifra {@code claro} con la clave maestra actual, atado a {@code contexto}. */
    public byte[] cifrar(byte[] claro, ContextoDeCifrado contexto) {
        byte[] nonce = new byte[LARGO_NONCE];
        azar.nextBytes(nonce);
        byte[] cabecera = cabecera(actual.id());
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, actual.clave(), new GCMParameterSpec(BITS_TAG, nonce));
            cipher.updateAAD(aad(cabecera, contexto));
            byte[] cifrado = cipher.doFinal(claro);
            return ByteBuffer.allocate(cabecera.length + nonce.length + cifrado.length)
                    .put(cabecera).put(nonce).put(cifrado).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar con AES-GCM.", e);
        }
    }

    /**
     * Descifra un dato guardado por {@link #cifrar}, con el mismo {@code contexto}.
     *
     * @throws DatoIlegibleException si es de otra versión, de una clave maestra desconocida o no se descifra
     */
    public byte[] descifrar(byte[] guardado, ContextoDeCifrado contexto) {
        if (guardado == null || guardado.length < LARGO_MINIMO) {
            throw ilegible(DatoIlegibleException.Motivo.NO_SE_DESCIFRA, contexto);
        }
        if (guardado[0] != VERSION) {
            throw ilegible(DatoIlegibleException.Motivo.VERSION_DESCONOCIDA, contexto);
        }
        byte[] id = Arrays.copyOfRange(guardado, 1, LARGO_CABECERA);
        ClaveMaestra clave = claveDeId(id).orElseThrow(
                () -> ilegible(DatoIlegibleException.Motivo.CLAVE_DESCONOCIDA, contexto));
        byte[] nonce = Arrays.copyOfRange(guardado, LARGO_CABECERA, LARGO_CABECERA + LARGO_NONCE);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, clave.clave(), new GCMParameterSpec(BITS_TAG, nonce));
            cipher.updateAAD(aad(cabecera(id), contexto));
            return cipher.doFinal(guardado, LARGO_CABECERA + LARGO_NONCE,
                    guardado.length - LARGO_CABECERA - LARGO_NONCE);
        } catch (AEADBadTagException e) {
            throw ilegible(DatoIlegibleException.Motivo.NO_SE_DESCIFRA, contexto);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar con AES-GCM.", e);
        }
    }

    private Optional<ClaveMaestra> claveDeId(byte[] id) {
        if (MessageDigest.isEqual(id, actual.id())) {
            return Optional.of(actual);
        }
        return anterior.filter(clave -> MessageDigest.isEqual(id, clave.id()));
    }

    /** Se loguea solo el propósito, la ferretería y el motivo: nunca el dato ni la clave. */
    private static DatoIlegibleException ilegible(DatoIlegibleException.Motivo motivo, ContextoDeCifrado contexto) {
        log.warn("No se pudo leer un dato cifrado {} de la ferretería {}: {}", contexto.proposito(),
                contexto.ferreteriaId(), motivo);
        return new DatoIlegibleException(motivo, contexto);
    }

    private static byte[] cabecera(byte[] id) {
        return ByteBuffer.allocate(LARGO_CABECERA).put(VERSION).put(id).array();
    }

    private static byte[] aad(byte[] cabecera, ContextoDeCifrado contexto) {
        byte[] texto = (contexto.proposito().name() + "|" + contexto.ferreteriaId() + "|" + contexto.huella())
                .getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(cabecera.length + texto.length).put(cabecera).put(texto).array();
    }

    @Override
    public String toString() {
        return "CifradorDeClaves[claveMaestra=***, anterior=" + (anterior.isPresent() ? "***" : "no") + "]";
    }

    /** Una clave maestra con su id; {@code toString} enmascarado. */
    private record ClaveMaestra(SecretKey clave, byte[] id) {

        private static final int LARGO = 32;

        /** Valida y lee la clave; el mensaje de error nunca lleva el valor recibido. */
        static ClaveMaestra leer(String nombre, String base64) {
            String mensaje = nombre + " tiene que ser una clave de 32 bytes en Base64 (por ejemplo, la salida de "
                    + "«openssl rand -base64 32»).";
            if (base64 == null || base64.isBlank()) {
                throw new IllegalStateException(mensaje + " No está definida.");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(mensaje + " No es Base64.");
            }
            if (bytes.length != LARGO) {
                throw new IllegalStateException(mensaje + " Tiene " + bytes.length + " bytes.");
            }
            try {
                byte[] id = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(bytes), LARGO_ID);
                return new ClaveMaestra(new SecretKeySpec(bytes, "AES"), id);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("La JVM no tiene SHA-256.", e);
            }
        }

        @Override
        public String toString() {
            return "ClaveMaestra[***]";
        }
    }
}
