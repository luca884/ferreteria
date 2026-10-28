package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * El adaptador sale del ambiente del registro (regla «Nada simulado llega a ARCA real»): SIMULADOR va al simulador, y
 * homologación y producción todavía no tienen con quién hablar (rama 6): un error claro, sin tocar el simulador.
 */
class WsfePorAmbienteTest {

    private final ServicioWsfe simulador = mock(ServicioWsfe.class);
    private final WsfePorAmbiente porAmbiente = new WsfePorAmbiente(simulador);

    @Test
    @DisplayName("SIMULADOR va al simulador")
    void simulador() {
        assertThat(porAmbiente.para(Ambiente.SIMULADOR)).isSameAs(simulador);
    }

    @ParameterizedTest
    @EnumSource(value = Ambiente.class, names = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("homologación y producción dan un error claro hasta la rama 6, sin pasar por el simulador")
    void homologacionYProduccion(Ambiente ambiente) {
        assertThatThrownBy(() -> porAmbiente.para(ambiente))
                .isInstanceOf(AmbienteNoDisponibleException.class)
                .hasMessage("Todavía no hay conexión con ARCA para el ambiente " + ambiente + ": llega con la rama 6 del "
                        + "plan de facturación. El comprobante queda pendiente y no se manda al simulador.");
        verifyNoInteractions(simulador);
    }
}
