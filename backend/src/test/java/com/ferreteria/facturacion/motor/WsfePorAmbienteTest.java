package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * El adaptador sale del ambiente del registro (regla «Nada simulado llega a ARCA real»): cada ambiente va al suyo, y uno sin
 * adaptador da un error claro, sin tocar a los otros. El cableado real (los tres con {@code ClienteWsfe}) lo prueba
 * {@code CableadoDeArcaIntegrationTest}.
 */
class WsfePorAmbienteTest {

    private final ServicioWsfe simulador = mock(ServicioWsfe.class);
    private final ServicioWsfe homologacion = mock(ServicioWsfe.class);
    private final ServicioWsfe produccion = mock(ServicioWsfe.class);

    @Test
    @DisplayName("cada ambiente va a su adaptador")
    void cadaAmbienteASuAdaptador() {
        WsfePorAmbiente porAmbiente = new WsfePorAmbiente(Map.of(Ambiente.SIMULADOR, simulador,
                Ambiente.HOMOLOGACION, homologacion, Ambiente.PRODUCCION, produccion));

        assertThat(porAmbiente.para(Ambiente.SIMULADOR)).isSameAs(simulador);
        assertThat(porAmbiente.para(Ambiente.HOMOLOGACION)).isSameAs(homologacion);
        assertThat(porAmbiente.para(Ambiente.PRODUCCION)).isSameAs(produccion);
    }

    @ParameterizedTest
    @EnumSource(value = Ambiente.class, names = {"HOMOLOGACION", "PRODUCCION"})
    @DisplayName("un ambiente sin adaptador da un error claro, sin pasar por el simulador")
    void unAmbienteSinAdaptador(Ambiente ambiente) {
        WsfePorAmbiente soloSimulador = new WsfePorAmbiente(Map.of(Ambiente.SIMULADOR, simulador));

        assertThatThrownBy(() -> soloSimulador.para(ambiente))
                .isInstanceOf(AmbienteNoDisponibleException.class)
                .hasMessage("No hay conexión con ARCA para el ambiente " + ambiente + ": el comprobante queda pendiente y "
                        + "no se manda a ningún otro lado.");
        verifyNoInteractions(simulador);
    }
}
