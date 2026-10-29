package com.ferreteria.facturacion.credencial;

import com.ferreteria.common.Cuit;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.credencial.CasDeArca.CaDeArca;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.DatoIlegibleException;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.RegistrosFiscales;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La credencial de ARCA de la ferretería del request, para su Dueño (CU-01 pasos 2 y 3, rama 5, C1): el punto de venta, el
 * pedido de certificado generado en el servidor, el .crt validado contra la CA del ambiente y la firma con la CA del
 * simulador. La ferretería sale solo del JWT ({@code TenantContext}).
 *
 * <p>La clave privada nace acá, se cifra enseguida ({@link CifradorDeClaves}) y no sale nunca: ni en una respuesta ni en un
 * log (D8, S7). No hay endpoint para subir una clave privada.
 *
 * <p>Todo cambio empieza tomando el lock exclusivo de la fila de {@code config_arca} de la ferretería, con el mismo protocolo
 * que el panel del super admin ({@link ConfigArcaRepository#bloquearScoped()}): dos cambios a la vez (del Dueño o del panel)
 * se serializan, el ambiente y el punto de venta no cambian mientras una venta da de alta su registro fiscal, y el segundo
 * decide sobre lo que dejó el primero.
 */
@Service
public class CredencialArcaService {

    private static final Logger log = LoggerFactory.getLogger(CredencialArcaService.class);

    static final String SIN_PEDIDO = "No hay un pedido de certificado pendiente.";
    static final String CUIT_EN_PRODUCCION = "En producción el certificado lleva el CUIT de la ferretería.";
    static final String CERTIFICADO_VACIO = "Pegá el certificado (.crt) que te dio ARCA.";
    static final String CERTIFICADO_GRANDE = "El certificado pesa más de 16 KB: no parece un .crt de ARCA.";
    static final String CLAVE_PRIVADA_PEGADA =
            "Eso es una clave privada: subí solo el certificado (.crt). La clave nunca sale del sistema.";
    static final String CERTIFICADO_ILEGIBLE =
            "No se pudo leer el certificado: subí el .crt que bajaste de ARCA (PEM, o DER en Base64).";
    static final String OTRA_CA =
            "El certificado no lo firmó ARCA: subí el .crt que bajaste de ARCA, de homologación o de producción.";
    static final String CA_NO_VIGENTE = "La autoridad de ARCA que firmó el certificado no está vigente.";
    static final String VENCIDO = "El certificado venció el %s: generá un pedido nuevo y pedí otro en ARCA.";
    static final String TODAVIA_NO_VIGENTE = "El certificado empieza a valer el %s: subilo desde ese día.";
    static final String CLAVE_CORTA = "La clave del certificado tiene que ser RSA de 2048 bits o más.";
    static final String NO_CORRESPONDE = "El certificado no corresponde al pedido pendiente: subí el que ARCA emitió para "
            + "el último pedido que generaste.";
    static final String OTRA_CUIT_EN_PRODUCCION =
            "El certificado es de otra CUIT (%s): en producción tiene que ser la de la ferretería.";
    static final String SIN_CUIT_EN_PRODUCCION =
            "El certificado no dice de qué CUIT es: en producción tiene que ser la de la ferretería.";
    static final String CA_DEL_SIMULADOR_ILEGIBLE =
            "No se puede leer la clave de la CA del simulador: la firma con el simulador no está disponible.";
    static final String AVISO_OTRA_CUIT = "El certificado es de otra CUIT (%s): sirve solo para homologación.";
    static final String AVISO_POR_VENCER = "El certificado de ARCA vence el %s: renovalo en Facturación.";
    static final String AVISO_VENCIDO =
            "El certificado de ARCA venció el %s: generá un pedido nuevo y subí el certificado renovado.";
    static final String AVISO_ILEGIBLE = "No se puede leer la clave guardada del certificado de ARCA: generá un pedido "
            + "nuevo y subí el certificado otra vez.";

    /** Lo más que pesa el .crt que se acepta, como texto, antes de leerlo (S4). */
    static final int MAXIMO_BYTES = 16 * 1024;
    private static final int BITS_MINIMOS = 2048;
    private static final Pattern SERIAL_DE_ARCA = Pattern.compile("CUIT (\\d{11})");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ConfigArcaRepository configs;
    private final CredencialArcaRepository credenciales;
    private final CaSimuladorRepository casDelSimulador;
    private final FerreteriaRepository ferreterias;
    private final CifradorDeClaves cifrador;
    private final CasDeArca casDeArca;
    private final RevisionDeCredencial revision;
    private final Clock reloj;

    public CredencialArcaService(ConfigArcaRepository configs, CredencialArcaRepository credenciales,
            CaSimuladorRepository casDelSimulador, FerreteriaRepository ferreterias, CifradorDeClaves cifrador,
            CasDeArca casDeArca, RevisionDeCredencial revision, Clock reloj) {
        this.configs = configs;
        this.credenciales = credenciales;
        this.casDelSimulador = casDelSimulador;
        this.ferreterias = ferreterias;
        this.cifrador = cifrador;
        this.casDeArca = casDeArca;
        this.revision = revision;
        this.reloj = reloj;
    }

    /** La configuración de la ferretería, como la ve el Dueño. Sin fila de {@code config_arca}, la de una nueva (no la crea). */
    @Transactional(readOnly = true)
    public ConfiguracionFacturacion configuracion() {
        ConfigArca config = configs.deFerreteriaScoped()
                .orElseGet(() -> ConfigArca.nueva(TenantContext.requireFerreteriaId()));
        return vista(config, configs.registrosScoped());
    }

    /** El Dueño cambia el punto de venta (S2): 409 con el simulador o con registros abiertos. */
    @Transactional
    public ConfiguracionFacturacion cambiarPuntoVenta(int puntoVenta) {
        ConfigArca config = bloquear();
        RegistrosFiscales registros = configs.registrosScoped();
        config.cambiarPuntoVenta(puntoVenta, registros.hayAbiertos());
        configs.saveAndFlush(config);
        return vista(config, registros);
    }

    /**
     * Genera la clave y el pedido de certificado (S3): la clave queda cifrada como PENDIENTE y reemplaza al pedido pendiente
     * que hubiera, sin tocar la credencial activa. El CUIT del pedido es el de la ferretería, o el que escribe el Dueño
     * (la persona física de homologación) si el ambiente no es PRODUCCION (S14).
     *
     * @param cuitEscrito null o vacío: el de la ferretería
     */
    @Transactional
    public PedidoCertificado generarPedido(String cuitEscrito) {
        ConfigArca config = bloquear();
        DatosFiscales ferreteria = ferreteria();
        String cuit = cuitDelPedido(cuitEscrito, digitos(ferreteria.cuit()), config.getAmbiente());

        PedidoDeCertificado.Generado generado = PedidoDeCertificado.generar(ferreteria.razonSocial(), cuit);
        String huella = Certificados.huella(generado.publica());
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        byte[] cifrada = cifrar(generado.privada(),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ferreteriaId, huella));

        credenciales.borrarPendienteScoped();
        credenciales.saveAndFlush(CredencialArca.pendiente(ferreteriaId, Long.parseLong(cuit), huella, cifrada,
                generado.pem(), ahora()));
        log.info("Pedido de certificado de ARCA generado para la ferretería {} (huella {})", ferreteriaId, huella);
        return new PedidoCertificado(generado.pem(), archivo(cuit));
    }

    /** El pedido pendiente, para volver a bajarlo; 404 si no hay. */
    @Transactional(readOnly = true)
    public PedidoCertificado pedidoPendiente() {
        CredencialArca pendiente = credenciales.pendienteScoped()
                .orElseThrow(() -> new ResourceNotFoundException(SIN_PEDIDO));
        return new PedidoCertificado(pendiente.getPedido(), archivo(onceDigitos(pendiente.getCuit())));
    }

    /**
     * Valida el .crt que el Dueño sube para el pedido pendiente y lo activa (S4, S14, S15, S16): hasta 16 KB, PEM o DER en
     * Base64; firmado por la CA de ARCA de homologación o de producción (de esa firma sale el ambiente), vigentes los dos;
     * RSA de 2048 bits o más; con la clave pública del pedido; con la CUIT de la ferretería en producción (en homologación,
     * otra CUIT avisa). Activarlo reemplaza a la credencial activa anterior y fija el ambiente; no habilita la facturación.
     * Cada rechazo es un 400 en {@code certificado} con el motivo; sin pedido pendiente, 409.
     */
    @Transactional
    public ConfiguracionFacturacion subirCertificado(String texto) {
        X509Certificate certificado = leerCertificado(texto);
        ConfigArca config = bloquear();
        CredencialArca pendiente = credenciales.pendienteScoped().orElseThrow(() -> new ConflictException(SIN_PEDIDO));
        Instant ahora = ahora();

        CaDeArca ca = casDeArca.laQueFirmo(certificado).orElseThrow(() -> invalido(OTRA_CA));
        if (!vigente(ca.certificado(), ahora)) {
            throw invalido(CA_NO_VIGENTE);
        }
        Instant desde = certificado.getNotBefore().toInstant();
        Instant hasta = certificado.getNotAfter().toInstant();
        if (ahora.isBefore(desde)) {
            throw invalido(String.format(TODAVIA_NO_VIGENTE, fecha(desde)));
        }
        if (!ahora.isBefore(hasta)) {
            throw invalido(String.format(VENCIDO, fecha(hasta)));
        }
        if (!(certificado.getPublicKey() instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < BITS_MINIMOS) {
            throw invalido(CLAVE_CORTA);
        }
        if (!esLaDelPedido(rsa, pendiente)) {
            throw invalido(NO_CORRESPONDE);
        }
        Optional<String> cuit = cuitDelCertificado(certificado);
        String deLaFerreteria = digitos(ferreteria().cuit());
        if (ca.ambiente() == Ambiente.PRODUCCION && !cuit.equals(Optional.of(deLaFerreteria))) {
            throw invalido(cuit.map(otra -> String.format(OTRA_CUIT_EN_PRODUCCION, otra)).orElse(SIN_CUIT_EN_PRODUCCION));
        }

        activar(config, pendiente, certificado, ca.ambiente(), ca.nombre(), null,
                cuit.map(Long::parseLong).orElse(pendiente.getCuit()), ahora);
        return vista(config, configs.registrosScoped());
    }

    /**
     * En SIMULADOR, firma el pedido pendiente con la CA del simulador de la ferretería y lo activa (S5). La CA se crea la
     * primera vez, con su clave cifrada. Fuera de SIMULADOR, o sin pedido pendiente, 409.
     */
    @Transactional
    public ConfiguracionFacturacion firmarConElSimulador() {
        ConfigArca config = bloquear();
        if (config.getAmbiente() != Ambiente.SIMULADOR) {
            throw new ConflictException(ConfigArca.FIRMA_SOLO_CON_SIMULADOR);
        }
        CredencialArca pendiente = credenciales.pendienteScoped().orElseThrow(() -> new ConflictException(SIN_PEDIDO));
        Instant ahora = ahora();
        CaSimulador ca = casDelSimulador.deLaFerreteriaScoped().orElseGet(() -> crearCaDelSimulador(ahora));
        X509Certificate certificadoDeLaCa = leerGuardado(ca.getCertificado());

        X509Certificate certificado = FirmaDelSimulador.firmar(pendiente.getPedido(), certificadoDeLaCa,
                claveDeLaCa(ca), ahora);

        activar(config, pendiente, certificado, Ambiente.SIMULADOR, Certificados.nombreComun(certificadoDeLaCa), ca.getId(),
                pendiente.getCuit(), ahora);
        return vista(config, configs.registrosScoped());
    }

    // ---- activar ----

    /**
     * Fija el ambiente del certificado en la configuración (S16), borra la credencial activa anterior con su clave (S15) y
     * convierte el pedido pendiente en la activa. Todo con el lock de {@code config_arca} tomado.
     */
    private void activar(ConfigArca config, CredencialArca pendiente, X509Certificate certificado, Ambiente ambiente,
            String emisor, Long caDelSimulador, long cuit, Instant ahora) {
        config.fijarAmbienteDelCertificado(ambiente, configs.registrosScoped());
        configs.save(config);
        credenciales.borrarActivaScoped();
        pendiente.activar(Certificados.pem("CERTIFICATE", encoded(certificado)), cuit, ambiente, emisor,
                certificado.getNotBefore().toInstant(), certificado.getNotAfter().toInstant(), caDelSimulador, ahora);
        credenciales.saveAndFlush(pendiente);
        log.info("Certificado de ARCA activado para la ferretería {}: {}, emitido por {}, vence {} (huella {})",
                pendiente.getFerreteriaId(), ambiente, emisor, pendiente.getVigenteHasta(), pendiente.getHuella());
    }

    private CaSimulador crearCaDelSimulador(Instant ahora) {
        FirmaDelSimulador.CaNueva nueva = FirmaDelSimulador.crearCa(ahora);
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        String huella = Certificados.huella(nueva.certificado().getPublicKey());
        byte[] cifrada = cifrar(nueva.privada(),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, ferreteriaId, huella));
        CaSimulador ca = casDelSimulador.saveAndFlush(CaSimulador.nueva(ferreteriaId,
                Certificados.pem("CERTIFICATE", encoded(nueva.certificado())), huella, cifrada, ahora));
        log.info("CA del simulador creada para la ferretería {} (huella {})", ferreteriaId, huella);
        return ca;
    }

    /** La clave privada de la CA del simulador, descifrada solo para firmar. */
    private PrivateKey claveDeLaCa(CaSimulador ca) {
        byte[] pkcs8 = null;
        try {
            pkcs8 = cifrador.descifrar(ca.getClavePrivada(), ca.contexto());
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (DatoIlegibleException e) {
            throw new ConflictException(CA_DEL_SIMULADOR_ILEGIBLE);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La clave de la CA del simulador no es RSA.", e);
        } finally {
            if (pkcs8 != null) {
                Arrays.fill(pkcs8, (byte) 0);
            }
        }
    }

    // ---- el .crt ----

    /** Lee el .crt antes de tomar ningún lock: vacío, de más de 16 KB, una clave privada o ilegible, 400. */
    private static X509Certificate leerCertificado(String texto) {
        if (texto == null || texto.isBlank()) {
            throw invalido(CERTIFICADO_VACIO);
        }
        if (texto.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES) {
            throw invalido(CERTIFICADO_GRANDE);
        }
        if (texto.contains("PRIVATE KEY")) {
            throw invalido(CLAVE_PRIVADA_PEGADA);
        }
        String normalizado = texto.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            byte[] bytes = normalizado.startsWith("-----BEGIN")
                    ? (normalizado + "\n").getBytes(StandardCharsets.US_ASCII)
                    : Base64.getMimeDecoder().decode(normalizado);
            return Certificados.leer(bytes);
        } catch (CertificateException | IllegalArgumentException e) {
            throw invalido(CERTIFICADO_ILEGIBLE);
        }
    }

    private static X509Certificate leerGuardado(String pem) {
        try {
            return Certificados.leer(pem.getBytes(StandardCharsets.US_ASCII));
        } catch (CertificateException e) {
            throw new IllegalStateException("El certificado guardado de la CA del simulador no se puede leer.", e);
        }
    }

    private static boolean vigente(X509Certificate ca, Instant ahora) {
        try {
            ca.checkValidity(Date.from(ahora));
            return true;
        } catch (CertificateException e) {
            return false;
        }
    }

    /** Si la clave pública del certificado es la del pedido: el mismo módulo y el mismo exponente RSA. */
    private static boolean esLaDelPedido(RSAPublicKey delCertificado, CredencialArca pendiente) {
        try {
            RSAPublicKey delPedido = (RSAPublicKey) PedidoDeCertificado.leer(pendiente.getPedido()).getPublicKey();
            return delPedido.getModulus().equals(delCertificado.getModulus())
                    && delPedido.getPublicExponent().equals(delCertificado.getPublicExponent());
        } catch (GeneralSecurityException | ClassCastException e) {
            return false;
        }
    }

    /** Los 11 dígitos del serialNumber del sujeto («CUIT 30999999995»), leído como atributo; vacío si no lo trae así. */
    private static Optional<String> cuitDelCertificado(X509Certificate certificado) {
        return Certificados.numeroDeSerieDelSujeto(certificado).map(SERIAL_DE_ARCA::matcher).filter(Matcher::matches)
                .map(m -> m.group(1));
    }

    // ---- la configuración que ve el Dueño ----

    private ConfiguracionFacturacion vista(ConfigArca config, RegistrosFiscales registros) {
        Instant ahora = ahora();
        LocalDate hoy = ahora.atZone(HoraArgentina.ZONA).toLocalDate();
        String deLaFerreteria = digitos(ferreteria().cuit());
        List<String> avisos = new ArrayList<>();
        ConfiguracionFacturacion.Credencial credencial = credenciales.activaScoped().map(activa -> {
            EstadoCredencial estado = revision.estado(activa, ahora);
            LocalDate hasta = fechaArgentina(activa.getVigenteHasta());
            switch (estado) {
                case POR_VENCER -> avisos.add(String.format(AVISO_POR_VENCER, FECHA.format(hasta)));
                case VENCIDA -> avisos.add(String.format(AVISO_VENCIDO, FECHA.format(hasta)));
                case ILEGIBLE -> avisos.add(AVISO_ILEGIBLE);
                case VIGENTE -> { }
            }
            String cuit = onceDigitos(activa.getCuit());
            if (activa.getAmbiente() == Ambiente.HOMOLOGACION && !cuit.equals(deLaFerreteria)) {
                avisos.add(String.format(AVISO_OTRA_CUIT, cuit));
            }
            return new ConfiguracionFacturacion.Credencial(cuit, activa.getEmisor(), activa.getAmbiente().name(),
                    fechaArgentina(activa.getVigenteDesde()), hasta, Certificados.huellaParaMostrar(activa.getHuella()),
                    estado, ChronoUnit.DAYS.between(hoy, hasta));
        }).orElse(null);
        ConfiguracionFacturacion.Pedido pedido = credenciales.pendienteScoped()
                .map(p -> new ConfiguracionFacturacion.Pedido(p.getCreadaEn(), onceDigitos(p.getCuit()),
                        Certificados.huellaParaMostrar(p.getHuella())))
                .orElse(null);
        String motivo = config.motivoPuntoVentaTrabado(registros.hayAbiertos());
        return new ConfiguracionFacturacion(config.getAmbiente() == null ? null : config.getAmbiente().name(),
                config.getPuntoVenta(), motivo == null, motivo, config.isFacturacionHabilitada(),
                config.getAmbiente() == Ambiente.SIMULADOR, credencial, pedido, List.copyOf(avisos));
    }

    // ---- ayudas ----

    private ConfigArca bloquear() {
        return configs.bloquearScoped().orElseThrow(() -> new ResourceNotFoundException("Ferretería no encontrada"));
    }

    private DatosFiscales ferreteria() {
        // findById es valido: la ferreteria es la raiz del tenant, no un repositorio de tenant.
        return ferreterias.findById(TenantContext.requireFerreteriaId()).map(DatosFiscales::de)
                .orElseThrow(() -> new ResourceNotFoundException("Ferretería no encontrada"));
    }

    /** El CUIT del pedido: el de la ferretería, o el escrito si no es PRODUCCION (S14). 400 en {@code cuit} si no vale. */
    private static String cuitDelPedido(String escrito, String deLaFerreteria, Ambiente ambiente) {
        if (escrito == null || escrito.isBlank()) {
            return deLaFerreteria;
        }
        String cuit;
        try {
            cuit = digitos(Cuit.normalizar(escrito));
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(Map.of("cuit", e.getMessage()));
        }
        if (ambiente == Ambiente.PRODUCCION && !cuit.equals(deLaFerreteria)) {
            throw new DatosInvalidosException(Map.of("cuit", CUIT_EN_PRODUCCION));
        }
        return cuit;
    }

    /** Cifra la clave privada (PKCS#8) y borra la copia en claro. */
    private byte[] cifrar(PrivateKey privada, ContextoDeCifrado contexto) {
        byte[] pkcs8 = privada.getEncoded();
        try {
            return cifrador.cifrar(pkcs8, contexto);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    private static byte[] encoded(X509Certificate certificado) {
        try {
            return certificado.getEncoded();
        } catch (CertificateException e) {
            throw new IllegalStateException("No se pudo codificar el certificado.", e);
        }
    }

    private static DatosInvalidosException invalido(String mensaje) {
        return new DatosInvalidosException(Map.of("certificado", mensaje));
    }

    private Instant ahora() {
        return Instant.now(reloj);
    }

    private static LocalDate fechaArgentina(Instant instante) {
        return instante.atZone(HoraArgentina.ZONA).toLocalDate();
    }

    private static String fecha(Instant instante) {
        return FECHA.format(fechaArgentina(instante));
    }

    private static String digitos(String cuit) {
        return cuit.replaceAll("\\D", "");
    }

    private static String onceDigitos(long cuit) {
        return String.format("%011d", cuit);
    }

    private static String archivo(String cuit) {
        return "pedido-certificado-" + cuit + ".csr";
    }
}
