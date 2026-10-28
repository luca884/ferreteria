package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * El adaptador de WSFEv1 de cada ambiente (regla «Nada simulado llega a ARCA real»). Se elige por el ambiente del registro
 * (que se fija al registrarlo y no cambia), nunca por la configuración actual de la ferretería: SIMULADOR va al simulador,
 * y homologación y producción todavía no tienen cliente (rama 6), así que dan {@link AmbienteNoDisponibleException} sin
 * pasar por ningún otro lado.
 *
 * <p>El simulador llega como {@link ServicioWsfe}, por el nombre de su bean: el motor no conoce la clase del simulador
 * (regla de {@code ModulosArchTest}).
 */
@Component
public class WsfePorAmbiente {

    private final ServicioWsfe simulador;

    public WsfePorAmbiente(@Qualifier("simuladorWsfe") ServicioWsfe simulador) {
        this.simulador = simulador;
    }

    /** El puerto que atiende ese ambiente. */
    public ServicioWsfe para(Ambiente ambiente) {
        return switch (ambiente) {
            case SIMULADOR -> simulador;
            case HOMOLOGACION, PRODUCCION -> throw new AmbienteNoDisponibleException(ambiente);
        };
    }
}
