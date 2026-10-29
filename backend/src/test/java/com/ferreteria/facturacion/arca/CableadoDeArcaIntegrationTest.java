package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.WsfePorAmbiente;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * El cableado real de WSFEv1 (rama 6, S6): SIMULADOR, HOMOLOGACION y PRODUCCION usan el mismo {@link ClienteWsfe}, cada uno
 * con su transporte (el simulador en el borde o el HTTP real) y la URL de su ambiente de la configuración (C6).
 */
class CableadoDeArcaIntegrationTest extends IntegrationTestBase {

    @Autowired
    WsfePorAmbiente wsfePorAmbiente;
    @Autowired
    @Qualifier("simuladorEnElBorde")
    Transporte simulador;
    @Autowired
    TransporteHttp transporteHttp;
    @Autowired
    PropiedadesArca propiedades;

    @Test
    @DisplayName("los tres ambientes van por ClienteWsfe: SIMULADOR con el simulador en el borde, homologación y producción "
            + "con el HTTP real, cada uno a su URL")
    void losTresAmbientesUsanElMismoCliente() {
        for (Ambiente ambiente : Ambiente.values()) {
            assertThat(wsfePorAmbiente.para(ambiente)).isInstanceOfSatisfying(ClienteWsfe.class, cliente -> {
                assertThat(cliente.ambiente()).isEqualTo(ambiente);
                assertThat(cliente.url()).isEqualTo(propiedades.wsfe().de(ambiente));
                assertThat(cliente.transporte()).isSameAs(ambiente == Ambiente.SIMULADOR ? simulador : transporteHttp);
            });
        }
        assertThat(propiedades.wsfe().de(Ambiente.SIMULADOR)).isEqualTo(PropiedadesArca.WSFE_SIMULADOR);
        assertThat(propiedades.wsfe().de(Ambiente.PRODUCCION).getHost()).isEqualTo("servicios1.afip.gov.ar");
    }
}
