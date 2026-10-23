package com.ferreteria.ferreteria.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfiguracionPreciosServiceTest {

    @Mock
    FerreteriaRepository ferreteriaRepository;

    @InjectMocks
    ConfiguracionPreciosService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(7L);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("actual() devuelve la configuracion de la ferreteria del JWT")
    void devuelveLaConfiguracionDelTenant() {
        when(ferreteriaRepository.findById(7L))
                .thenReturn(Optional.of(new Ferreteria("Tornillo SA", "30-11111111-1", CondicionIva.RESPONSABLE_INSCRIPTO)));

        ConfiguracionPrecios cfg = service.actual();

        assertThat(cfg.margenDefectoPorcentaje()).isEqualByComparingTo(new BigDecimal("30"));
        // Sin redondeo por defecto (H11).
        assertThat(cfg.redondeoMultiplo()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("guardar() cambia margen y multiplo de la ferreteria del JWT y devuelve la configuracion anterior")
    void guardarCambiaLaConfiguracionDelTenant() {
        Ferreteria ferreteria = new Ferreteria("Tornillo SA", "30-11111111-1", CondicionIva.RESPONSABLE_INSCRIPTO);
        when(ferreteriaRepository.findById(7L)).thenReturn(Optional.of(ferreteria));

        ConfiguracionPrecios anterior = service.guardar(new ConfiguracionPrecios(new BigDecimal("45.50"), new BigDecimal("0.50")));

        assertThat(anterior.margenDefectoPorcentaje()).isEqualByComparingTo("30");
        assertThat(anterior.redondeoMultiplo()).isEqualByComparingTo("0");
        assertThat(ferreteria.getMargenDefectoPorcentaje()).isEqualByComparingTo("45.50");
        assertThat(ferreteria.getRedondeoMultiplo()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("si la ferreteria no existe es un error de estado: un token valido siempre la tiene")
    void sinFerreteriaLanzaIllegalState() {
        when(ferreteriaRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actual()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sin tenant en el contexto falla cerrado y no toca la base")
    void sinTenantFallaCerrado() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.actual()).isInstanceOf(MissingTenantException.class);
        verifyNoInteractions(ferreteriaRepository);
    }
}
