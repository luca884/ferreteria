package com.ferreteria.facturacion.config;

import com.ferreteria.facturacion.arca.ClienteWsaa;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.ClientesWsfe;
import com.ferreteria.facturacion.arca.PasosDelTicket;
import com.ferreteria.facturacion.arca.PropiedadesArca;
import com.ferreteria.facturacion.arca.TicketsDeAcceso;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.arca.TransporteHttp;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.WsfePorAmbiente;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El cableado del cliente de ARCA (rama 6, S6): los tres ambientes con el mismo cliente SOAP, cada uno con su transporte.
 * <ul>
 *   <li>SIMULADOR va por el simulador en el borde (el bean {@code simuladorEnElBorde}), que no sale de la JVM.</li>
 *   <li>HOMOLOGACION y PRODUCCION van por {@link TransporteHttp}, con los timeouts de C6, cada uno a su URL.</li>
 * </ul>
 * WSAA ({@link ClienteWsaa}) y WSFEv1 ({@link ClienteWsfe}) toman la URL de su ambiente de {@link PropiedadesArca}. El TA lo
 * saca {@link TicketsDeAcceso}, con la credencial activa de la ferretería del ambiente: también en SIMULADOR, que desde esta
 * rama exige una credencial vigente.
 */
@Configuration(proxyBeanMethods = false)
public class ClienteArcaConfig {

    /** El transporte HTTP real a ARCA (homologación y producción). */
    @Bean
    public TransporteHttp transporteHttp(PropiedadesArca propiedades) {
        return new TransporteHttp(propiedades.timeoutConexion(), propiedades.timeoutLectura());
    }

    /** El TA de WSAA de cada ferretería, con un cliente de WSAA por ambiente. */
    @Bean
    public TicketsDeAcceso ticketsDeAcceso(PasosDelTicket pasos, FirmaCms firma, PropiedadesArca propiedades, Clock reloj,
            TransporteHttp transporteHttp, @Qualifier("simuladorEnElBorde") Transporte simulador) {
        List<ClienteWsaa> clientes = Arrays.stream(Ambiente.values())
                .map(ambiente -> new ClienteWsaa(ambiente, transporte(ambiente, transporteHttp, simulador),
                        propiedades.wsaa()))
                .toList();
        return new TicketsDeAcceso(pasos, firma, clientes, propiedades, reloj);
    }

    /**
     * El {@link ClienteWsfe} de cada ambiente, cada uno con su transporte y su URL: los usan el motor (por
     * {@link WsfePorAmbiente}), «Probar conexión» y el chequeo FEDummy del super admin.
     */
    @Bean
    public ClientesWsfe clientesWsfe(PropiedadesArca propiedades, TicketsDeAcceso tickets, TransporteHttp transporteHttp,
            @Qualifier("simuladorEnElBorde") Transporte simulador) {
        return new ClientesWsfe(Arrays.stream(Ambiente.values())
                .map(ambiente -> new ClienteWsfe(ambiente, transporte(ambiente, transporteHttp, simulador),
                        propiedades.wsfe().de(ambiente), tickets))
                .toList());
    }

    /** El adaptador de WSFEv1 de cada ambiente para el motor: los mismos clientes de {@link #clientesWsfe}. */
    @Bean
    public WsfePorAmbiente wsfePorAmbiente(ClientesWsfe clientes) {
        return new WsfePorAmbiente(clientes.porAmbiente());
    }

    private static Transporte transporte(Ambiente ambiente, TransporteHttp http, Transporte simulador) {
        return ambiente == Ambiente.SIMULADOR ? simulador : http;
    }
}
