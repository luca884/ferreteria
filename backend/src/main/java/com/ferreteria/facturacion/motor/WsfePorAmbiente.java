package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * El adaptador de WSFEv1 de cada ambiente (regla «Nada simulado llega a ARCA real»). Se elige por el ambiente del registro
 * (que se fija al registrarlo y no cambia), nunca por la configuración actual de la ferretería.
 *
 * <p>Desde la rama 6 (S6), en el cableado real ({@code facturacion.config.ClienteArcaConfig}) los tres ambientes tienen el
 * mismo cliente SOAP ({@code ClienteWsfe}), cada uno con su transporte: SIMULADOR el simulador en el borde, que no sale de la
 * JVM, y homologación y producción el HTTP real, cada uno a su URL. Un ambiente sin adaptador da
 * {@link AmbienteNoDisponibleException} sin pasar por ningún otro lado.
 *
 * <p>El motor lo ve solo como el puerto {@link ServicioWsfe}: no conoce el cliente ni el simulador (regla de
 * {@code ModulosArchTest}).
 */
public class WsfePorAmbiente {

    private final Map<Ambiente, ServicioWsfe> porAmbiente = new EnumMap<>(Ambiente.class);

    /** @param porAmbiente el adaptador de cada ambiente que tiene uno */
    public WsfePorAmbiente(Map<Ambiente, ? extends ServicioWsfe> porAmbiente) {
        porAmbiente.forEach((ambiente, servicio) -> this.porAmbiente.put(Objects.requireNonNull(ambiente, "el ambiente"),
                Objects.requireNonNull(servicio, "el adaptador de " + ambiente)));
    }

    /**
     * El puerto que atiende ese ambiente.
     *
     * @throws AmbienteNoDisponibleException si ese ambiente no tiene adaptador
     */
    public ServicioWsfe para(Ambiente ambiente) {
        ServicioWsfe servicio = porAmbiente.get(ambiente);
        if (servicio == null) {
            throw new AmbienteNoDisponibleException(ambiente);
        }
        return servicio;
    }
}
