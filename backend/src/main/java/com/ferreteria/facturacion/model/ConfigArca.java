package com.ferreteria.facturacion.model;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La configuración de ARCA de una ferretería (tabla {@code config_arca}, 1-1 con la ferretería; CU-01 paso 2, D3). La
 * marca «de prueba», el simulador y la facturación habilitada los cambia solo el super admin; el punto de venta, el Dueño
 * (rama 5, S2), salvo con el simulador; homologación o producción, el certificado que valida el Dueño (S16). La fila nace
 * la primera vez que alguno la toca: una ferretería sin fila está sin marca, sin ambiente y deshabilitada.
 *
 * <p>El cerrojo del ambiente: el simulador solo va a una ferretería marcada «de prueba», sin registros de homologación o
 * producción y sin registros abiertos, y deja el punto de venta en 1 (S3). Homologación y producción los fija el
 * certificado ({@link #fijarAmbienteDelCertificado}): desde el panel no se asignan, no se reemplazan ni se quitan, y de
 * producción no se vuelve. Quitar el ambiente exige la facturación apagada y ningún registro abierto; cambiarlo o cambiar
 * el punto de venta, ningún registro abierto. La facturación se prende con el simulador o en homologación; en producción,
 * recién con la rama 11 (rama 6, S16). Los CHECK de V32 sostienen lo mismo en la base.
 *
 * <p>El bloqueo de ARCA (rama 6, C4, columnas de V35): cuando WSAA rechaza el certificado ({@code cms.cert.*} o
 * {@code coe.notAuthorized}), la facturación de la ferretería queda frenada con el código, el texto de ARCA y la hora, sin
 * tocar la facturación habilitada: {@link #puedeFacturar()} da false y no se vuelve a llamar a WSAA. Lo levantan activar otro
 * certificado o «Probar conexión» OK ({@link #levantarBloqueo()}).
 */
@Entity
@Table(name = "config_arca")
public class ConfigArca {

    /** Con el simulador el punto de venta queda fijo en 1, sin pantalla para cambiarlo (S3). */
    public static final int PUNTO_VENTA_SIMULADOR = 1;

    static final String MARCA_CON_SIMULADOR =
            "No se puede sacar la marca «de prueba» mientras la ferretería use el simulador: quitale el simulador primero.";
    static final String SIMULADOR_SIN_MARCA = "El simulador solo se asigna a una ferretería marcada «de prueba».";
    static final String CON_REGISTROS_REALES =
            "La ferretería ya tiene comprobantes de homologación o producción: no se le puede asignar el simulador.";
    static final String CON_REGISTROS_ABIERTOS =
            "La ferretería tiene comprobantes fiscales sin terminar: el ambiente no se puede cambiar hasta que se resuelvan.";
    static final String AMBIENTE_DEL_CERTIFICADO =
            "El ambiente de homologación o producción lo fija el certificado: no se cambia desde el panel.";
    static final String QUITAR_CON_FACTURACION =
            "Primero deshabilitá la facturación: no se puede quitar el ambiente mientras esté habilitada.";
    static final String HABILITAR_SIN_AMBIENTE =
            "Para habilitar la facturación, la ferretería necesita un ambiente de ARCA y un punto de venta.";
    /**
     * Producción espera a la rama 11 (la homologación con la piloto, y antes las ramas 7 y 8 y el contador): ninguna
     * ferretería real factura en producción hasta entonces (rama 6, S16).
     */
    static final String PRODUCCION_NO_DISPONIBLE = "La facturación en producción todavía no está disponible.";

    /** El largo de {@code config_arca.bloqueo_codigo} y {@code bloqueo_mensaje} (V35). */
    static final int LARGO_BLOQUEO_CODIGO = 64;
    static final int LARGO_BLOQUEO_MENSAJE = 500;

    /** El punto de venta más alto que carga el Dueño (S2). */
    public static final int PUNTO_VENTA_MAXIMO = 99998;
    static final String PUNTO_VENTA_DEL_SIMULADOR = "Con el simulador el punto de venta es 1.";
    static final String PUNTO_VENTA_CON_REGISTROS_ABIERTOS =
            "Hay facturas en trámite con ARCA: el punto de venta no se puede cambiar.";
    static final String CERTIFICADO_CON_SIMULADOR =
            "Con el simulador, el certificado lo firma el simulador: usá «Firmar con el simulador».";
    static final String DESDE_PRODUCCION = "Desde producción no se vuelve a homologación.";
    /** «Firmar con el simulador» fuera del simulador (S5): esa acción no existe ahí. */
    public static final String FIRMA_SOLO_CON_SIMULADOR =
            "«Firmar con el simulador» es solo para la ferretería de prueba que usa el simulador de ARCA.";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ferreteria_id", nullable = false, updatable = false)
    private Long ferreteriaId;

    @Column(name = "de_prueba", nullable = false)
    private boolean dePrueba;

    /** null mientras no tenga ninguno. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private Ambiente ambiente;

    @Column(name = "punto_venta")
    private Integer puntoVenta;

    @Column(name = "facturacion_habilitada", nullable = false)
    private boolean facturacionHabilitada;

    /** Cuándo se prendió la facturación (UTC); null mientras esté apagada. */
    @Column(name = "habilitada_en")
    private Instant habilitadaEn;

    /** El código de WSAA que frenó la facturación (C4); null sin bloqueo. */
    @Column(name = "bloqueo_codigo", length = LARGO_BLOQUEO_CODIGO)
    private String bloqueoCodigo;

    /** El {@code faultstring} de ese rechazo; null sin bloqueo (o si ARCA no mandó texto). */
    @Column(name = "bloqueo_mensaje", length = LARGO_BLOQUEO_MENSAJE)
    private String bloqueoMensaje;

    /** Cuándo se frenó (UTC); null sin bloqueo. */
    @Column(name = "bloqueado_en")
    private Instant bloqueadoEn;

    protected ConfigArca() {}

    /** La de una ferretería que el super admin todavía no tocó: sin marca, sin ambiente y deshabilitada. */
    public static ConfigArca nueva(long ferreteriaId) {
        ConfigArca config = new ConfigArca();
        config.ferreteriaId = ferreteriaId;
        return config;
    }

    /** Pone o saca la marca «de prueba». No se saca mientras la ferretería use el simulador. */
    public void cambiarDePrueba(boolean dePrueba) {
        if (!dePrueba && ambiente == Ambiente.SIMULADOR) {
            throw new ReglaDeNegocioException(MARCA_CON_SIMULADOR);
        }
        this.dePrueba = dePrueba;
    }

    /** Asigna el simulador con el punto de venta 1. Si ya lo tiene, no cambia nada. */
    public void asignarSimulador(RegistrosFiscales registros) {
        if (ambiente == Ambiente.SIMULADOR) {
            return;
        }
        if (ambiente != null) {
            throw new ReglaDeNegocioException(AMBIENTE_DEL_CERTIFICADO);
        }
        if (!dePrueba) {
            throw new ReglaDeNegocioException(SIMULADOR_SIN_MARCA);
        }
        if (registros.hayDeHomologacionOProduccion()) {
            throw new ReglaDeNegocioException(CON_REGISTROS_REALES);
        }
        if (registros.hayAbiertos()) {
            throw new ReglaDeNegocioException(CON_REGISTROS_ABIERTOS);
        }
        this.ambiente = Ambiente.SIMULADOR;
        this.puntoVenta = PUNTO_VENTA_SIMULADOR;
    }

    /** Quita el simulador, y con él el punto de venta. Sin ambiente no cambia nada. */
    public void quitarAmbiente(RegistrosFiscales registros) {
        if (ambiente == null) {
            return;
        }
        if (ambiente != Ambiente.SIMULADOR) {
            throw new ReglaDeNegocioException(AMBIENTE_DEL_CERTIFICADO);
        }
        if (facturacionHabilitada) {
            throw new ReglaDeNegocioException(QUITAR_CON_FACTURACION);
        }
        if (registros.hayAbiertos()) {
            throw new ReglaDeNegocioException(CON_REGISTROS_ABIERTOS);
        }
        this.ambiente = null;
        this.puntoVenta = null;
    }

    /**
     * Prende la facturación y guarda {@code ahora} como su fecha de activación. Exige ambiente, punto de venta y los datos
     * de CU-01 completos: {@code datosFaltantes} son los nombres de los que faltan, como se le muestran al super admin. Si
     * ya estaba prendida, no cambia nada (ni la fecha). Se prende con el simulador o en homologación; en producción no,
     * hasta la rama 11 ({@link #PRODUCCION_NO_DISPONIBLE}, rama 6, S16).
     */
    public void habilitar(List<String> datosFaltantes, Instant ahora) {
        if (facturacionHabilitada) {
            return;
        }
        if (ambiente == Ambiente.PRODUCCION) {
            throw new ReglaDeNegocioException(PRODUCCION_NO_DISPONIBLE);
        }
        if (ambiente == null || puntoVenta == null) {
            throw new ReglaDeNegocioException(HABILITAR_SIN_AMBIENTE);
        }
        if (!datosFaltantes.isEmpty()) {
            throw new ReglaDeNegocioException(faltanDatos(datosFaltantes));
        }
        this.facturacionHabilitada = true;
        this.habilitadaEn = ahora;
    }

    /** Apagarla siempre se puede; la fecha de activación se borra. */
    public void deshabilitar() {
        this.facturacionHabilitada = false;
        this.habilitadaEn = null;
    }

    /**
     * Si esta configuración deja facturar: la facturación habilitada, con ambiente y punto de venta, y sin el bloqueo de ARCA
     * (rama 6, C4). Es solo la parte de ARCA: la ferretería factura si además tiene completos los datos de CU-01 que la
     * factura imprime, que el Dueño puede vaciar con la facturación prendida. La regla entera, la que miran la decisión de la
     * venta y el alta del registro fiscal, es {@code PasosFiscales.puedeFacturar(Instant)}.
     */
    public boolean puedeFacturar() {
        return facturacionHabilitada && ambiente != null && puntoVenta != null && !estaBloqueada();
    }

    /**
     * Frena la facturación porque WSAA rechazó el certificado (C4): guarda el código, el texto de ARCA y la hora, recortados
     * a lo que entra en {@code config_arca}. No toca la facturación habilitada. Otro rechazo reemplaza al anterior.
     *
     * @param codigo el código de WSAA, sin prefijo ({@code cms.cert.expired}, {@code coe.notAuthorized}…)
     * @param mensaje el {@code faultstring}; null o vacío si no vino
     * @param ahora la hora del {@code Clock} de quien llama
     */
    public void bloquear(String codigo, String mensaje, Instant ahora) {
        if (codigo == null || codigo.isBlank() || ahora == null) {
            throw new IllegalArgumentException("El bloqueo de ARCA lleva el código y la hora.");
        }
        this.bloqueoCodigo = recortar(codigo.strip(), LARGO_BLOQUEO_CODIGO);
        this.bloqueoMensaje = mensaje == null || mensaje.isBlank() ? null : recortar(mensaje.strip(), LARGO_BLOQUEO_MENSAJE);
        this.bloqueadoEn = ahora;
    }

    /** Levanta el bloqueo de ARCA (otro certificado activado, o «Probar conexión» OK). Sin bloqueo no cambia nada. */
    public void levantarBloqueo() {
        this.bloqueoCodigo = null;
        this.bloqueoMensaje = null;
        this.bloqueadoEn = null;
    }

    /** Si la facturación está frenada por un rechazo del certificado en WSAA (C4). */
    public boolean estaBloqueada() {
        return bloqueoCodigo != null;
    }

    /**
     * Por qué el Dueño no puede cambiar el punto de venta (rama 5, S2), o null si puede: con el simulador queda fijo en 1, y
     * mientras haya registros fiscales abiertos ({@code ConfigArcaRepository.registrosAbiertos}) no cambia.
     */
    public String motivoPuntoVentaTrabado(boolean registrosAbiertos) {
        if (ambiente == Ambiente.SIMULADOR) {
            return PUNTO_VENTA_DEL_SIMULADOR;
        }
        if (registrosAbiertos) {
            return PUNTO_VENTA_CON_REGISTROS_ABIERTOS;
        }
        return null;
    }

    /**
     * El Dueño carga el punto de venta habilitado en ARCA (CU-01 paso 2, rama 5): de 1 a {@link #PUNTO_VENTA_MAXIMO}, con
     * o sin ambiente. Trabado ({@link #motivoPuntoVentaTrabado}): 409 con el motivo. El mismo número no cambia nada.
     */
    public void cambiarPuntoVenta(int nuevo, boolean registrosAbiertos) {
        if (nuevo < 1 || nuevo > PUNTO_VENTA_MAXIMO) {
            throw new IllegalArgumentException("El punto de venta va de 1 a " + PUNTO_VENTA_MAXIMO + ".");
        }
        if (puntoVenta != null && puntoVenta == nuevo) {
            return;
        }
        String trabado = motivoPuntoVentaTrabado(registrosAbiertos);
        if (trabado != null) {
            throw new ConflictException(trabado);
        }
        this.puntoVenta = nuevo;
    }

    /**
     * El ambiente que fija un certificado validado (rama 5, S16): el de la CA que lo firmó. Sin ambiente, toma el de la
     * CA; de homologación pasa a producción solo sin registros abiertos (409); de producción no se vuelve (400 en
     * {@code certificado}); con el simulador solo vale la CA del simulador (400 en {@code certificado}). El mismo ambiente
     * no cambia nada (renovar el certificado deja la facturación como estaba). No toca el punto de venta, y un certificado
     * validado nunca habilita la facturación; si el ambiente cambia de uno a otro (de homologación a producción), la
     * deshabilita ({@link #deshabilitar()}): la vuelve a prender el super admin (D3; rama 6, S14).
     */
    public void fijarAmbienteDelCertificado(Ambiente deLaCa, RegistrosFiscales registros) {
        if (ambiente == deLaCa) {
            return;
        }
        if (ambiente == Ambiente.SIMULADOR) {
            throw new DatosInvalidosException(Map.of("certificado", CERTIFICADO_CON_SIMULADOR));
        }
        if (deLaCa == Ambiente.SIMULADOR) {
            throw new ConflictException(FIRMA_SOLO_CON_SIMULADOR);
        }
        if (ambiente == Ambiente.PRODUCCION) {
            throw new DatosInvalidosException(Map.of("certificado", DESDE_PRODUCCION));
        }
        if (registros.hayAbiertos()) {
            throw new ConflictException(CON_REGISTROS_ABIERTOS);
        }
        if (ambiente != null) {
            deshabilitar();
        }
        this.ambiente = deLaCa;
    }

    /** Los primeros {@code largo} caracteres (code points: una letra fuera del plano básico no se parte). */
    private static String recortar(String texto, int largo) {
        if (texto.codePointCount(0, texto.length()) <= largo) {
            return texto;
        }
        return texto.substring(0, texto.offsetByCodePoints(0, largo));
    }

    private static String faltanDatos(List<String> faltantes) {
        if (faltantes.size() == 1) {
            return "Para habilitar la facturación falta un dato de la ferretería: " + faltantes.get(0)
                    + ". Lo carga el Dueño en «Datos de la ferretería».";
        }
        String ultimo = faltantes.get(faltantes.size() - 1);
        // «inicio de actividades» e «Ingresos Brutos» van con «e»: «y» delante de «i» suena «ii».
        String conector = ultimo.toLowerCase(Locale.ROOT).startsWith("i") ? " e " : " y ";
        return "Para habilitar la facturación faltan datos de la ferretería: "
                + String.join(", ", faltantes.subList(0, faltantes.size() - 1)) + conector + ultimo
                + ". Los carga el Dueño en «Datos de la ferretería».";
    }

    public Long getId() { return id; }
    public Long getFerreteriaId() { return ferreteriaId; }
    public boolean isDePrueba() { return dePrueba; }
    public Ambiente getAmbiente() { return ambiente; }
    public Integer getPuntoVenta() { return puntoVenta; }
    public boolean isFacturacionHabilitada() { return facturacionHabilitada; }
    public Instant getHabilitadaEn() { return habilitadaEn; }
    public String getBloqueoCodigo() { return bloqueoCodigo; }
    public String getBloqueoMensaje() { return bloqueoMensaje; }
    public Instant getBloqueadoEn() { return bloqueadoEn; }
}
