package com.ferreteria.facturacion.arca;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaseComprobante;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.EncuadreFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Los cuatro pasos de «Probar conexión» (rama 6, C1, CU-01 paso 4) contra el {@link ClienteWsfe} del ambiente de la
 * ferretería, en orden; el primero que falla corta los siguientes, que no figuran:
 * <ol>
 *   <li>{@link PasoPrueba#SERVIDORES}: {@code FEDummy}, los tres servidores OK;</li>
 *   <li>{@link PasoPrueba#AUTENTICACION}: el TA de WSAA, con el {@link Autenticador} ({@link TicketsDeAcceso#probar}, que
 *       llama a WSAA aunque la facturación esté frenada por C4);</li>
 *   <li>{@link PasoPrueba#PUNTO_DE_VENTA}: {@code FEParamGetPtosVenta}: el punto existe, es de tipo CAE, no está bloqueado
 *       ni dado de baja;</li>
 *   <li>{@link PasoPrueba#ULTIMOS_NUMEROS}: {@code FECompUltimoAutorizado} de cada factura que emite la ferretería según su
 *       condición frente al IVA (la tabla de la rama 2, {@link EncuadreFiscal}: A y B para RI, C para Monotributista y
 *       Exento).</li>
 * </ol>
 * No toca la base ni el bloqueo: eso lo hace quien la corre, sin una transacción abierta (las llamadas a ARCA nunca van
 * dentro de una). Ningún detalle lleva el token ni el sign.
 */
public class PruebaDeConexion {

    /** El aviso de C1 cuando el ambiente es el simulador: la prueba no salió de la JVM. */
    public static final String AVISO_SIMULADOR = "Simulador: no se probó contra ARCA";

    static final String SERVIDORES_OK = "AppServer OK, DbServer OK, AuthServer OK";
    static final String TICKET_VIGENTE = "Ticket de acceso vigente hasta %s";
    static final String RECHAZO_DEL_CERTIFICADO = "ARCA rechazó el certificado";
    static final String RECHAZO_DEL_TICKET = "ARCA no dio el ticket de acceso";
    static final String PUNTO_OK = "Punto de venta %d: CAE, habilitado";
    static final String PUNTO_SIN_ALTA = "El punto de venta %d no está dado de alta en ARCA.";
    static final String PUNTO_CAEA = "El punto de venta %d es de tipo %s: tiene que ser CAE.";
    static final String PUNTO_BLOQUEADO = "El punto de venta %d está bloqueado en ARCA.";
    static final String PUNTO_DE_BAJA = "El punto de venta %d está dado de baja en ARCA desde %s.";
    static final String SIN_CONDICION_IVA =
            "Falta la condición frente al IVA de la ferretería: cargala en «Datos de la ferretería».";

    /** El código de WSFEv1 «Sin Resultados» de {@code FEParamGetPtosVenta}: la CUIT no tiene puntos de venta. */
    static final int SIN_RESULTADOS = 602;

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_Y_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** De dónde sale el TA del paso AUTENTICACION. */
    @FunctionalInterface
    public interface Autenticador {

        /**
         * El TA de {@value ClienteWsfe#SERVICIO} en ese ambiente, con su vencimiento.
         *
         * @throws PedidoNoLlegoException si no se pudo; con un fault de WSAA, su causa es la {@link FaultWsaaException}
         */
        TicketsDeAcceso.Probado autenticar(Ambiente ambiente);
    }

    /** Los pasos, en el orden en que se corren. */
    public enum PasoPrueba {
        SERVIDORES,
        AUTENTICACION,
        PUNTO_DE_VENTA,
        ULTIMOS_NUMEROS
    }

    /**
     * Lo que se prueba: la ferretería de la prueba.
     *
     * @param ambiente el de su configuración de ARCA
     * @param puntoVenta el de su configuración
     * @param cuit la suya, del emisor (va en {@code Auth.Cuit}, rama 6 S17)
     * @param condicionIva la suya frente al IVA; null si no la tiene cargada
     */
    public record Datos(Ambiente ambiente, int puntoVenta, long cuit, CondicionIvaEmisor condicionIva) {

        public Datos {
            Objects.requireNonNull(ambiente, "el ambiente");
        }
    }

    private final ClienteWsfe cliente;
    private final Autenticador autenticador;

    /**
     * @param cliente el cliente de WSFEv1 del ambiente de la ferretería
     * @param autenticador de dónde sale el TA
     */
    public PruebaDeConexion(ClienteWsfe cliente, Autenticador autenticador) {
        this.cliente = Objects.requireNonNull(cliente, "el cliente de WSFEv1");
        this.autenticador = Objects.requireNonNull(autenticador, "el autenticador");
    }

    /** Corre los pasos para esa ferretería, hasta el primero que falla. */
    public PruebaConexion correr(Datos datos) {
        Objects.requireNonNull(datos, "los datos");
        if (datos.ambiente() != cliente.ambiente()) {
            throw new IllegalArgumentException("El cliente de WSFEv1 es de " + cliente.ambiente() + " y la prueba, de "
                    + datos.ambiente() + ".");
        }
        String aviso = datos.ambiente() == Ambiente.SIMULADOR ? AVISO_SIMULADOR : null;
        List<PruebaConexion.Paso> pasos = new ArrayList<>();
        Auth auth = new Auth(datos.ambiente(), datos.cuit());

        PruebaConexion.Paso servidores = conFallaDeTransporte(PasoPrueba.SERVIDORES, this::servidores);
        pasos.add(servidores);
        if (!servidores.ok()) {
            return new PruebaConexion(datos.ambiente().name(), false, aviso, pasos, List.of());
        }
        PruebaConexion.Paso autenticacion = autenticacion(datos.ambiente());
        pasos.add(autenticacion);
        if (!autenticacion.ok()) {
            return new PruebaConexion(datos.ambiente().name(), false, aviso, pasos, List.of());
        }
        PruebaConexion.Paso punto = conFallaDeTransporte(PasoPrueba.PUNTO_DE_VENTA,
                () -> puntoDeVenta(auth, datos.puntoVenta()));
        pasos.add(punto);
        if (!punto.ok()) {
            return new PruebaConexion(datos.ambiente().name(), false, aviso, pasos, List.of());
        }
        List<PruebaConexion.Ultimo> ultimos = new ArrayList<>();
        PruebaConexion.Paso numeros = conFallaDeTransporte(PasoPrueba.ULTIMOS_NUMEROS,
                () -> ultimosNumeros(auth, datos.puntoVenta(), datos.condicionIva(), ultimos));
        pasos.add(numeros);
        return new PruebaConexion(datos.ambiente().name(), numeros.ok(), aviso, pasos,
                numeros.ok() ? List.copyOf(ultimos) : List.of());
    }

    // ---- los pasos ----

    private PruebaConexion.Paso servidores() {
        FeDummyResponse dummy = cliente.feDummy();
        if (dummy.todoOk()) {
            return ok(PasoPrueba.SERVIDORES, SERVIDORES_OK);
        }
        List<String> caidos = new ArrayList<>();
        agregarSiNoEstaOk(caidos, "AppServer", dummy.appServer());
        agregarSiNoEstaOk(caidos, "DbServer", dummy.dbServer());
        agregarSiNoEstaOk(caidos, "AuthServer", dummy.authServer());
        return falla(PasoPrueba.SERVIDORES, String.join(", ", caidos));
    }

    private static void agregarSiNoEstaOk(List<String> caidos, String servidor, String estado) {
        if (!"OK".equals(estado)) {
            caidos.add(servidor + " " + (estado == null || estado.isBlank() ? "sin respuesta" : estado));
        }
    }

    private PruebaConexion.Paso autenticacion(Ambiente ambiente) {
        try {
            TicketsDeAcceso.Probado probado = autenticador.autenticar(ambiente);
            return ok(PasoPrueba.AUTENTICACION, String.format(TICKET_VIGENTE, fechaYHora(probado.vence())));
        } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
            return falla(PasoPrueba.AUTENTICACION, fault(e).map(PruebaDeConexion::rechazo).orElse(e.getMessage()));
        }
    }

    private PruebaConexion.Paso puntoDeVenta(Auth auth, int numero) {
        FeParamGetPtosVentaResponse respuesta = cliente.feParamGetPtosVenta(auth);
        Optional<PtoVenta> punto = respuesta.resultGet().stream().filter(p -> p.nro() == numero).findFirst();
        if (punto.isEmpty()) {
            Optional<Err> error = respuesta.errors().stream().filter(e -> e.code() != SIN_RESULTADOS).findFirst();
            return falla(PasoPrueba.PUNTO_DE_VENTA, error.map(PruebaDeConexion::errorDeArca)
                    .orElse(String.format(PUNTO_SIN_ALTA, numero)));
        }
        PtoVenta p = punto.get();
        if (!"CAE".equalsIgnoreCase(p.emisionTipo())) {
            return falla(PasoPrueba.PUNTO_DE_VENTA, String.format(PUNTO_CAEA, numero,
                    p.emisionTipo() == null ? "desconocido" : p.emisionTipo()));
        }
        if (p.bloqueado()) {
            return falla(PasoPrueba.PUNTO_DE_VENTA, String.format(PUNTO_BLOQUEADO, numero));
        }
        if (p.fchBaja() != null) {
            return falla(PasoPrueba.PUNTO_DE_VENTA, String.format(PUNTO_DE_BAJA, numero, FECHA.format(p.fchBaja())));
        }
        return ok(PasoPrueba.PUNTO_DE_VENTA, String.format(PUNTO_OK, numero));
    }

    private PruebaConexion.Paso ultimosNumeros(Auth auth, int puntoVenta, CondicionIvaEmisor condicion,
            List<PruebaConexion.Ultimo> ultimos) {
        if (condicion == null) {
            return falla(PasoPrueba.ULTIMOS_NUMEROS, SIN_CONDICION_IVA);
        }
        for (Letra letra : letras(condicion)) {
            int cbteTipo = letra.cbteTipo(ClaseComprobante.FACTURA);
            String comprobante = "Factura " + letra.name();
            FeCompUltimoAutorizadoResponse respuesta = cliente.feCompUltimoAutorizado(auth, puntoVenta, cbteTipo);
            if (!respuesta.errors().isEmpty()) {
                return falla(PasoPrueba.ULTIMOS_NUMEROS, comprobante + ": " + errorDeArca(respuesta.errors().get(0)));
            }
            ultimos.add(new PruebaConexion.Ultimo(cbteTipo, comprobante, respuesta.cbteNro()));
        }
        return ok(PasoPrueba.ULTIMOS_NUMEROS,
                ultimos.stream().map(PruebaConexion.Ultimo::comprobante).collect(Collectors.joining(" y ")));
    }

    /** Las letras de las facturas que emite la ferretería: las de la tabla de la rama 2, para todo receptor. */
    static List<Letra> letras(CondicionIvaEmisor condicion) {
        return Arrays.stream(CondicionIvaReceptor.values())
                .map(receptor -> EncuadreFiscal.de(condicion, receptor).letra())
                .distinct().sorted().toList();
    }

    // ---- ayudas ----

    /** Un paso que llama a ARCA: una falla del transporte (no llegó o incierto) es el paso fallido, con su motivo. */
    private static PruebaConexion.Paso conFallaDeTransporte(PasoPrueba paso,
            Supplier<PruebaConexion.Paso> llamada) {
        try {
            return llamada.get();
        } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
            return falla(paso, e.getMessage());
        }
    }

    private static Optional<FaultWsaaException> fault(Throwable falla) {
        for (Throwable t = falla; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof FaultWsaaException fault) {
                return Optional.of(fault);
            }
        }
        return Optional.empty();
    }

    /** El fault de WSAA con su código: «ARCA rechazó el certificado: Certificado expirado (cms.cert.expired)». */
    private static String rechazo(FaultWsaaException fault) {
        String quien = FallaWsaa.de(fault.codigo()) == FallaWsaa.CREDENCIAL ? RECHAZO_DEL_CERTIFICADO : RECHAZO_DEL_TICKET;
        String texto = fault.faultstring() == null || fault.faultstring().isBlank() ? "" : ": " + fault.faultstring();
        return quien + texto + " (" + fault.codigo() + ")";
    }

    private static String errorDeArca(Err error) {
        return "ARCA respondió " + error.code() + (error.msg() == null || error.msg().isBlank() ? "" : ": " + error.msg());
    }

    private static String fechaYHora(Instant instante) {
        return FECHA_Y_HORA.format(instante.atZone(HoraArgentina.ZONA));
    }

    private static PruebaConexion.Paso ok(PasoPrueba paso, String detalle) {
        return new PruebaConexion.Paso(paso, true, detalle);
    }

    private static PruebaConexion.Paso falla(PasoPrueba paso, String detalle) {
        return new PruebaConexion.Paso(paso, false, detalle);
    }
}
