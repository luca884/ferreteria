package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.RubroRequest;
import com.ferreteria.catalogo.dto.RubroResponse;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RubroServiceTest {

    private static final Long FERRETERIA = 3L;
    private static final Long USUARIO = 9L;
    private static final Long RUBRO_ID = 4L;

    @Mock
    RubroRepository rubroRepository;

    @Mock
    RecalculoPreciosService recalculoPreciosService;

    @InjectMocks
    RubroService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private Rubro rubroExistente(String nombre, String margen) throws Exception {
        Rubro rubro = new Rubro(FERRETERIA, nombre, bd(margen));
        Field id = Rubro.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(rubro, RUBRO_ID);
        when(rubroRepository.findScoped(RUBRO_ID)).thenReturn(Optional.of(rubro));
        return rubro;
    }

    @Test
    @DisplayName("actualizar: si el margen cambia, corre la cascada con el rubro ya editado y el usuario")
    void cambioDeMargenCorreLaCascada() throws Exception {
        Rubro rubro = rubroExistente("Buloneria", "35.00");

        RubroResponse respuesta = service.actualizar(RUBRO_ID, new RubroRequest("Buloneria", bd("50.00")), USUARIO);

        assertThat(respuesta.margenPorcentaje()).isEqualByComparingTo("50");
        ArgumentCaptor<Rubro> enCascada = ArgumentCaptor.forClass(Rubro.class);
        verify(recalculoPreciosService).porCambioDeMargenDeRubro(enCascada.capture(), eq(USUARIO));
        // La cascada recibe el rubro con el margen NUEVO: de ahi calcula los precios.
        assertThat(enCascada.getValue()).isSameAs(rubro);
        assertThat(enCascada.getValue().getMargenPorcentaje()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("actualizar: la cascada corre despues de cambiar el margen del rubro")
    void laCascadaCorreDespuesDeEditar() throws Exception {
        Rubro rubro = rubroExistente("Buloneria", "35.00");
        BigDecimal[] margenAlCorrer = new BigDecimal[1];
        doAnswer(inv -> {
            margenAlCorrer[0] = ((Rubro) inv.getArgument(0)).getMargenPorcentaje();
            return null;
        }).when(recalculoPreciosService).porCambioDeMargenDeRubro(any(Rubro.class), any());

        service.actualizar(RUBRO_ID, new RubroRequest("Buloneria", bd("50.00")), USUARIO);

        assertThat(margenAlCorrer[0]).isEqualByComparingTo("50");
        assertThat(rubro.getMargenPorcentaje()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("actualizar: el mismo margen con otra escala (35 vs 35.00) no es un cambio")
    void mismoMargenConOtraEscalaNoCorreLaCascada() throws Exception {
        rubroExistente("Buloneria", "35.00");

        service.actualizar(RUBRO_ID, new RubroRequest("Buloneria", bd("35")), USUARIO);

        verifyNoInteractions(recalculoPreciosService);
    }

    @Test
    @DisplayName("actualizar: solo renombrar no corre la cascada")
    void soloRenombrarNoCorreLaCascada() throws Exception {
        Rubro rubro = rubroExistente("Buloneria", "35.00");

        RubroResponse respuesta = service.actualizar(RUBRO_ID, new RubroRequest("  Tornilleria ", bd("35.00")), USUARIO);

        assertThat(respuesta.nombre()).isEqualTo("Tornilleria");
        assertThat(rubro.getNombre()).isEqualTo("Tornilleria");
        verifyNoInteractions(recalculoPreciosService);
    }

    @Test
    @DisplayName("actualizar: nombre de otro rubro da 409, no cambia el rubro ni corre la cascada")
    void nombreDuplicadoNoCambiaNada() throws Exception {
        Rubro rubro = rubroExistente("Buloneria", "35.00");
        when(rubroRepository.existeOtroConNombreScoped("Electricidad", RUBRO_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(RUBRO_ID, new RubroRequest("Electricidad", bd("50.00")), USUARIO))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un rubro llamado Electricidad");

        assertThat(rubro.getMargenPorcentaje()).isEqualByComparingTo("35");
        verifyNoInteractions(recalculoPreciosService);
    }

    @Test
    @DisplayName("actualizar: rubro ajeno o inexistente da 404 y no corre la cascada")
    void rubroAjeno() {
        when(rubroRepository.findScoped(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(9L, new RubroRequest("X", bd("50.00")), USUARIO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Rubro no encontrado");

        verifyNoInteractions(recalculoPreciosService);
        verify(rubroRepository, never()).save(any());
    }
}
