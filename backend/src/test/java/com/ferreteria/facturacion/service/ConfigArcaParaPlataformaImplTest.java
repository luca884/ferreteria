package com.ferreteria.facturacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.facturacion.api.ConfigArcaParaPlataforma.ResumenArca;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.RegistrosFiscales;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigArcaParaPlataformaImplTest {

    private static final Instant AHORA = Instant.parse("2026-10-28T13:00:00Z");

    @Test
    @DisplayName("habilitar guarda la hora del Clock inyectado y lee los datos de CU-01 de la ferretería")
    void habilitarUsaElReloj() {
        ConfigArcaRepository configs = mock(ConfigArcaRepository.class);
        FerreteriaRepository ferreterias = mock(FerreteriaRepository.class);
        ConfigArca config = ConfigArca.nueva(5L);
        config.cambiarDePrueba(true);
        config.asignarSimulador(RegistrosFiscales.NINGUNO);
        when(configs.bloquearDeFerreteria(5L)).thenReturn(Optional.of(config));
        when(configs.saveAndFlush(any(ConfigArca.class))).thenAnswer(i -> i.getArgument(0));
        Ferreteria ferreteria = new Ferreteria("Arco SA", "30-00000001-5", CondicionIva.RESPONSABLE_INSCRIPTO);
        ferreteria.actualizarDatosFiscales("Arco SA", CondicionIva.RESPONSABLE_INSCRIPTO, "Av. Luro 1",
                LocalDate.of(2015, 3, 1), "901-123456-7");
        when(ferreterias.findById(5L)).thenReturn(Optional.of(ferreteria));
        ConfigArcaParaPlataformaImpl impl =
                new ConfigArcaParaPlataformaImpl(configs, ferreterias, Clock.fixed(AHORA, ZoneOffset.UTC));

        ResumenArca resumen = impl.cambiarFacturacionHabilitada(5L, true);

        assertThat(resumen).isEqualTo(new ResumenArca(true, "SIMULADOR", 1, true, AHORA));
        assertThat(config.getHabilitadaEn()).isEqualTo(AHORA);
    }
}
