package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.URI;
import java.util.Objects;

/**
 * Cómo llega un sobre SOAP a ARCA y vuelve la respuesta (S5). Manda el pedido y devuelve el status y el cuerpo, sin
 * interpretar el SOAP: eso lo hacen {@link ClienteWsfe} y el cliente de WSAA. Es intercambiable: el real es
 * {@link TransporteHttp}; desde la rama 6, el simulador en el borde implementa otro que no sale de la JVM.
 *
 * <p>Una falla del transporte dice si el pedido pudo haber llegado:
 * <ul>
 *   <li>{@link PedidoNoLlegoException}: seguro no llegó (conexión rechazada, host desconocido, timeout de conexión o
 *       handshake TLS fallido), así que ARCA no autorizó nada;</li>
 *   <li>{@link ResultadoInciertoException}: salió y no se sabe si ARCA lo procesó (timeout de lectura, la conexión cortada
 *       después de enviar).</li>
 * </ul>
 * Una respuesta HTTP, sea cual sea el status, no es una falla del transporte: vuelve en {@link Respuesta}.
 */
public interface Transporte {

    /**
     * Manda el pedido y espera la respuesta.
     *
     * @throws PedidoNoLlegoException si seguro no llegó
     * @throws ResultadoInciertoException si salió y no se sabe si se procesó
     */
    Respuesta enviar(Pedido pedido);

    /**
     * Un sobre SOAP 1.1 para ARCA. El sobre lleva el token y el sign del TA (y la respuesta de WSAA, el TA entero), así que
     * {@link #toString()} no lo muestra: solo el destino, la acción y el largo.
     *
     * @param destino la URL del servicio, de la configuración ({@code app.facturacion.arca.*})
     * @param soapAction la acción SOAP sin comillas ({@code http://ar.gov.afip.dif.FEV1/FEDummy}, o vacía para WSAA); el
     *     transporte la manda entre comillas, como pide SOAP 1.1
     * @param sobre el XML del sobre
     */
    record Pedido(URI destino, String soapAction, String sobre) {

        public Pedido {
            Objects.requireNonNull(destino, "el destino");
            Objects.requireNonNull(soapAction, "la acción SOAP");
            Objects.requireNonNull(sobre, "el sobre");
        }

        @Override
        public String toString() {
            return "Pedido[destino=" + destino + ", soapAction=" + soapAction + ", sobre=*** (" + sobre.length()
                    + " caracteres)]";
        }
    }

    /**
     * Lo que contestó el servidor. {@link #toString()} no muestra el cuerpo (la respuesta de WSAA trae el TA).
     *
     * @param status el status HTTP
     * @param cuerpo el cuerpo, como texto
     */
    record Respuesta(int status, String cuerpo) {

        public Respuesta {
            Objects.requireNonNull(cuerpo, "el cuerpo");
        }

        @Override
        public String toString() {
            return "Respuesta[status=" + status + ", cuerpo=*** (" + cuerpo.length() + " caracteres)]";
        }
    }
}
