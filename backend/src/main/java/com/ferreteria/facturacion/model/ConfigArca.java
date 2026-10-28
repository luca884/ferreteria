package com.ferreteria.facturacion.model;

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

/**
 * La configuración de ARCA de una ferretería (tabla {@code config_arca}, 1-1 con la ferretería; CU-01 paso 2, D3). La
 * marca «de prueba», el ambiente, el punto de venta y la facturación habilitada los cambia solo el super admin. La fila
 * nace la primera vez que la toca: una ferretería sin fila está sin marca, sin ambiente y deshabilitada.
 *
 * <p>El cerrojo del ambiente: el simulador solo va a una ferretería marcada «de prueba», sin registros de homologación o
 * producción y sin registros abiertos, y deja el punto de venta en 1 (S3). Homologación y producción los fija el
 * certificado (rama 5): desde acá no se asignan, no se reemplazan ni se quitan. Quitar el ambiente exige la facturación
 * apagada y ningún registro abierto. Los CHECK de V32 sostienen lo mismo en la base.
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
     * ya estaba prendida, no cambia nada (ni la fecha).
     */
    public void habilitar(List<String> datosFaltantes, Instant ahora) {
        if (facturacionHabilitada) {
            return;
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
     * Si esta configuración deja facturar: la facturación habilitada, con ambiente y punto de venta. Es solo la parte de
     * ARCA: la ferretería factura si además tiene completos los datos de CU-01 que la factura imprime, que el Dueño puede
     * vaciar con la facturación prendida. La regla entera, la que miran la decisión de la venta y el alta del registro
     * fiscal, es {@code PasosFiscales.puedeFacturar()}.
     */
    public boolean puedeFacturar() {
        return facturacionHabilitada && ambiente != null && puntoVenta != null;
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
}
