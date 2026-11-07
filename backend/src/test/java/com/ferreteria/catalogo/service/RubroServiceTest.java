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
import com.ferreteria.catalogo.dto.VistaPreviaMargenRubroResponse;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.catalogo.service.RecalculoPreciosService.PrecioQueCambia;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    private static ValidatorFactory fabricaDeValidadores;

    RubroService service;

    @BeforeAll
    static void abrirValidador() {
        fabricaDeValidadores = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void cerrarValidador() {
        fabricaDeValidadores.close();
    }

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        service = new RubroService(rubroRepository, recalculoPreciosService, fabricaDeValidadores.getValidator());
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
                .hasMessage("Ya existe un rubro llamado Electricidad.");

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

    // ---- vista previa del margen nuevo (mejoras-rubros, S1) ----

    private static List<PrecioQueCambia> preciosQueCambian(long cuantos) {
        return LongStream.rangeClosed(1, cuantos)
                .mapToObj(i -> new PrecioQueCambia(i, "COD-" + i, "Articulo " + i, bd("170.00"), bd("190.00")))
                .toList();
    }

    @Test
    @DisplayName("vista previa del margen: cantidad es el total y la lista llega hasta el tope del masivo (1.000); pasado el tope, se recorta y lo avisa")
    void vistaPreviaDelMargenConElTopeDelMasivo() throws Exception {
        Rubro rubro = rubroExistente("Buloneria", "35.00");
        int tope = ActualizacionMasivaService.MAXIMO_DETALLE;
        when(recalculoPreciosService.preciosQueCambianPorMargenDeRubro(rubro, bd("50.00")))
                .thenReturn(preciosQueCambian(tope), preciosQueCambian(tope + 1L));

        VistaPreviaMargenRubroResponse justo = service.vistaPreviaMargen(RUBRO_ID, bd("50.00"));
        VistaPreviaMargenRubroResponse pasado = service.vistaPreviaMargen(RUBRO_ID, bd("50.00"));

        assertThat(justo.cantidad()).isEqualTo(1_000);
        assertThat(justo.articulos()).hasSize(1_000);
        assertThat(justo.recortado()).isFalse();
        assertThat(pasado.cantidad()).isEqualTo(1_001);
        assertThat(pasado.articulos()).hasSize(1_000);
        assertThat(pasado.articulos().get(0))
                .isEqualTo(new VistaPreviaMargenRubroResponse.Fila(1L, "COD-1", "Articulo 1", bd("170.00"), bd("190.00")));
        assertThat(pasado.articulos().get(999).id()).isEqualTo(1_000L);
        assertThat(pasado.recortado()).isTrue();
        // Es una vista previa: el rubro sigue con su margen y no corre la cascada.
        assertThat(rubro.getMargenPorcentaje()).isEqualByComparingTo("35");
        verify(recalculoPreciosService, never()).porCambioDeMargenDeRubro(any(), any());
    }
}
