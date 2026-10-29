package com.ferreteria.facturacion.arca;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La configuración del cliente de ARCA (C6), {@code app.facturacion.arca} en el {@code application.yml}. Las URL están solo
 * ahí: ningún endpoint las lee ni las cambia. Todas las propiedades son obligatorias; sin una, la aplicación no arranca.
 *
 * @param wsaa las URL de {@code LoginCms} de WSAA
 * @param wsfe las URL de WSFEv1
 * @param timeoutConexion cuánto se espera para conectar, con el handshake TLS
 * @param timeoutLectura cuánto se espera la respuesta
 * @param lapsoTa el lapso preventivo del TA por ambiente (FAQ 10.6 del manual de WSAA)
 * @param margenRenovacion el TA se renueva cuando le falta menos que esto para vencer
 * @param esperaWsaa sin llamar a WSAA después de {@code wsaa.*} o {@code wsn.unavailable}
 * @param esperaTaVigente sin llamar a WSAA después de {@code coe.alreadyAuthenticated}
 * @param esperaErrorSistema sin llamar a WSAA después de un error del sistema
 * @param esperaTicket cuánto espera un pedido mientras otro saca el TA
 * @param leaseTicket cuánto dura la toma del que saca el TA
 */
@ConfigurationProperties("app.facturacion.arca")
public record PropiedadesArca(Urls wsaa, Urls wsfe, Duration timeoutConexion, Duration timeoutLectura, Lapsos lapsoTa,
        Duration margenRenovacion, Duration esperaWsaa, Duration esperaTaVigente, Duration esperaErrorSistema,
        Duration esperaTicket, Duration leaseTicket) {

    private static final String PREFIJO = "app.facturacion.arca.";

    public PropiedadesArca {
        requerida("wsaa", wsaa);
        requerida("wsaa.homologacion", wsaa.homologacion());
        requerida("wsaa.produccion", wsaa.produccion());
        requerida("wsfe", wsfe);
        requerida("wsfe.homologacion", wsfe.homologacion());
        requerida("wsfe.produccion", wsfe.produccion());
        requerida("timeout-conexion", timeoutConexion);
        requerida("timeout-lectura", timeoutLectura);
        requerida("lapso-ta", lapsoTa);
        requerida("lapso-ta.homologacion", lapsoTa.homologacion());
        requerida("lapso-ta.produccion", lapsoTa.produccion());
        requerida("lapso-ta.simulador", lapsoTa.simulador());
        requerida("margen-renovacion", margenRenovacion);
        requerida("espera-wsaa", esperaWsaa);
        requerida("espera-ta-vigente", esperaTaVigente);
        requerida("espera-error-sistema", esperaErrorSistema);
        requerida("espera-ticket", esperaTicket);
        requerida("lease-ticket", leaseTicket);
    }

    /**
     * Lo que se valida al arrancar, contra el presupuesto de una atención del motor:
     * <ul>
     *   <li>{@code 2 × (timeout-conexion + timeout-lectura) + espera-ticket ≤ presupuesto-llamada}: en una atención puede
     *       haber un {@code loginCms} y una llamada a WSFE, más la espera del TA que saca otro pedido;</li>
     *   <li>{@code lease-ticket > timeout-conexion + timeout-lectura}: si la toma del TA venciera con {@code loginCms} en
     *       vuelo, otra instancia pediría otro y ARCA contestaría {@code coe.alreadyAuthenticated}.</li>
     * </ul>
     *
     * @throws IllegalStateException si no se cumple, con los valores en el mensaje
     */
    public void validarContra(Duration presupuestoLlamada) {
        Duration llamada = timeoutConexion.plus(timeoutLectura);
        Duration necesario = llamada.multipliedBy(2).plus(esperaTicket);
        if (necesario.compareTo(presupuestoLlamada) > 0) {
            throw new IllegalStateException("Las llamadas a ARCA no entran en el presupuesto del motor "
                    + "(app.facturacion.motor.presupuesto-llamada = " + texto(presupuestoLlamada) + "): dos llamadas más la "
                    + "espera del ticket, 2 × (" + texto(timeoutConexion) + " + " + texto(timeoutLectura) + ") + "
                    + texto(esperaTicket) + " = " + texto(necesario) + " (" + PREFIJO + "timeout-conexion, "
                    + "timeout-lectura y espera-ticket). Bajá esos tiempos o subí el presupuesto.");
        }
        if (leaseTicket.compareTo(llamada) <= 0) {
            throw new IllegalStateException("La toma del ticket de acceso (" + PREFIJO + "lease-ticket = "
                    + texto(leaseTicket) + ") tiene que durar más que una llamada a ARCA (timeout-conexion + "
                    + "timeout-lectura = " + texto(timeoutConexion) + " + " + texto(timeoutLectura) + " = " + texto(llamada)
                    + "): si venciera con loginCms en vuelo, otra instancia pediría otro ticket.");
        }
    }

    /**
     * Las URL de un servicio por ambiente.
     *
     * @param homologacion la de homologación ({@code *homo.afip.gov.ar})
     * @param produccion la de producción
     */
    public record Urls(URI homologacion, URI produccion) {
    }

    /**
     * El lapso preventivo del TA por ambiente: 10 min en homologación y 2 en producción (FAQ 10.6), y el del simulador.
     *
     * @param homologacion el de homologación
     * @param produccion el de producción
     * @param simulador el que aplica el simulador en el borde
     */
    public record Lapsos(Duration homologacion, Duration produccion, Duration simulador) {
    }

    private static void requerida(String propiedad, Object valor) {
        if (valor == null) {
            throw new IllegalStateException("Falta " + PREFIJO + propiedad + " en la configuración (C6): sin ella la "
                    + "aplicación no arranca.");
        }
    }

    /** Una duración legible: en segundos si es exacta, si no en milisegundos. */
    private static String texto(Duration duracion) {
        return duracion.toMillis() % 1000 == 0 ? duracion.toSeconds() + "s" : duracion.toMillis() + "ms";
    }
}
