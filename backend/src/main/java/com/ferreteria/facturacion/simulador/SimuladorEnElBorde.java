package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.arca.PropiedadesArca;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import java.net.URI;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * El simulador de ARCA en el borde del transporte (rama 6, S6, L4): el {@link Transporte} del ambiente SIMULADOR. Desde esta
 * rama, SIMULADOR usa el mismo cliente SOAP que homologación y producción ({@code ClienteWsaa} y {@code ClienteWsfe}, con el
 * TRA, el CMS y el XML reales); lo único que cambia es por dónde van los pedidos: este transporte no sale de la JVM y contesta
 * como ARCA.
 * <ul>
 *   <li>A la URL de WSAA del simulador ({@code app.facturacion.arca.wsaa.simulador}) contesta {@link WsaaDelSimulador}.</li>
 *   <li>A la de WSFEv1 ({@code app.facturacion.arca.wsfe.simulador}), {@link WsfeDelSimulador}, con el núcleo de reglas de la
 *       rama 2 ({@link SimuladorWsfe}).</li>
 *   <li>Cualquier otro destino es como un host que no existe: {@link PedidoNoLlegoException}. Así, un cliente de homologación o
 *       producción mal cableado con este transporte no recibe respuestas simuladas.</li>
 * </ul>
 * En el perfil dev, {@link CaidaSimulada} puede dejar a WSFEv1 sin contestar (rama 7).
 * Todo es de la ferretería del request ({@code TenantContext}, la que fija el ejecutor fiscal). El bean se pide por su nombre,
 * {@value #BEAN}: hay más de un {@link Transporte}.
 */
@Component(SimuladorEnElBorde.BEAN)
public class SimuladorEnElBorde implements Transporte {

    /** El nombre del bean. */
    public static final String BEAN = "simuladorEnElBorde";

    private final URI wsaa;
    private final URI wsfe;
    private final WsaaDelSimulador wsaaDelSimulador;
    private final WsfeDelSimulador wsfeDelSimulador;
    private final Optional<CaidaSimulada> caida;

    SimuladorEnElBorde(PropiedadesArca propiedades, WsaaDelSimulador wsaaDelSimulador, WsfeDelSimulador wsfeDelSimulador,
            Optional<CaidaSimulada> caida) {
        this.wsaa = propiedades.wsaa().de(Ambiente.SIMULADOR);
        this.wsfe = propiedades.wsfe().de(Ambiente.SIMULADOR);
        this.wsaaDelSimulador = wsaaDelSimulador;
        this.wsfeDelSimulador = wsfeDelSimulador;
        this.caida = caida;
    }

    @Override
    public Respuesta enviar(Pedido pedido) {
        if (pedido.destino().equals(wsaa)) {
            return wsaaDelSimulador.loginCms(pedido.sobre());
        }
        if (pedido.destino().equals(wsfe)) {
            if (caida.isPresent() && caida.get().caida()) {
                throw new PedidoNoLlegoException("ARCA simulada caída (perfil dev): no se mandó nada.");
            }
            return wsfeDelSimulador.atender(pedido.soapAction(), pedido.sobre());
        }
        throw new PedidoNoLlegoException("El simulador de ARCA no atiende " + pedido.destino() + ": no se mandó nada.");
    }
}
