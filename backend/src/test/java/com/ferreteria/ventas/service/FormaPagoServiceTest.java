package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RC-001 y RC-008: las reglas de las formas de pago. Unitario: el repositorio es un mock. */
@ExtendWith(MockitoExtension.class)
class FormaPagoServiceTest {

    private static final Long FERRETERIA = 3L;

    @Mock
    FormaPagoRepository repositorio;

    @InjectMocks
    FormaPagoService service;

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

    private static FormaPago forma(long id, String nombre, MedioPago medio, String recargo, boolean activa) {
        FormaPago forma = FormaPago.de(FERRETERIA, nombre, medio, bd(recargo), activa, (int) id);
        try {
            Field campo = FormaPago.class.getDeclaredField("id");
            campo.setAccessible(true);
            campo.set(forma, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return forma;
    }

    private static DatosFormaPago datos(String nombre, MedioPago medio, String recargo, Boolean activa, Integer orden) {
        return new DatosFormaPago(nombre, medio, recargo == null ? null : bd(recargo), activa, orden);
    }

    @Test
    @DisplayName("listar: una ferreteria sin formas recibe las cuatro de siempre, con recargo 0, y las devuelve")
    void listarSiembraLasCuatroSiNoHayNinguna() {
        when(repositorio.contarScoped()).thenReturn(0L);
        List<FormaPago> sembradas = List.of(forma(1, "Efectivo", MedioPago.EFECTIVO, "0", true));
        when(repositorio.listarScoped()).thenReturn(sembradas);

        assertThat(service.listar(false)).isEqualTo(sembradas);

        verify(repositorio).sembrarScoped("Efectivo", MedioPago.EFECTIVO, 1);
        verify(repositorio).sembrarScoped("Débito", MedioPago.DEBITO, 2);
        verify(repositorio).sembrarScoped("Crédito", MedioPago.CREDITO, 3);
        verify(repositorio).sembrarScoped("Transferencia", MedioPago.TRANSFERENCIA, 4);
    }

    @Test
    @DisplayName("siembra con la fila de la ferreteria bloqueada: dos pedidos a la vez no se pisan (deadlock del INSERT IGNORE)")
    void siembraConLaFerreteriaBloqueada() {
        when(repositorio.contarScoped()).thenReturn(0L);
        when(repositorio.listarScoped()).thenReturn(List.of());

        service.listar(false);

        org.mockito.InOrder orden = org.mockito.Mockito.inOrder(repositorio);
        orden.verify(repositorio).bloquearFerreteriaScoped();
        orden.verify(repositorio, times(4)).sembrarScoped(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("si mientras esperaba el candado otro pedido ya sembro, no siembra de nuevo")
    void siOtroYaSembroNoSiembra() {
        when(repositorio.contarScoped()).thenReturn(0L, 4L);
        when(repositorio.listarScoped()).thenReturn(List.of());

        service.listar(false);

        verify(repositorio).bloquearFerreteriaScoped();
        verify(repositorio, never()).sembrarScoped(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("listar: con formas ya cargadas no siembra nada, aunque el Dueno haya desactivado o renombrado las de siempre")
    void listarNoSiembraSiYaHay() {
        when(repositorio.contarScoped()).thenReturn(2L);
        when(repositorio.listarScoped()).thenReturn(List.of());

        service.listar(false);

        verify(repositorio, never()).sembrarScoped(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("listar y crear leen lo ultimo confirmado (READ COMMITTED): con la foto de REPEATABLE READ un pedido que espero el candado no veria lo que sembro el otro")
    void leenLoUltimoConfirmado() throws NoSuchMethodException {
        for (String metodo : List.of("listar", "crear")) {
            java.lang.reflect.Method m = java.util.Arrays.stream(FormaPagoService.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(metodo)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class).isolation())
                    .as(metodo).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
        }
    }

    @Test
    @DisplayName("listar solo activas trae las activas")
    void listarSoloActivas() {
        when(repositorio.contarScoped()).thenReturn(1L);
        List<FormaPago> activas = List.of(forma(1, "Efectivo", MedioPago.EFECTIVO, "0", true));
        when(repositorio.listarActivasScoped()).thenReturn(activas);

        assertThat(service.listar(true)).isEqualTo(activas);

        verify(repositorio, never()).listarScoped();
    }

    @Test
    @DisplayName("crear: guarda con el nombre sin espacios sobrantes, activa y con el orden siguiente al ultimo")
    void crearConOrdenSiguiente() {
        when(repositorio.contarScoped()).thenReturn(4L);
        when(repositorio.existeNombreScoped("Visa 6 cuotas")).thenReturn(false);
        when(repositorio.maxOrdenScoped()).thenReturn(4);
        when(repositorio.save(any(FormaPago.class))).thenAnswer(i -> i.getArgument(0));

        FormaPago creada = service.crear(datos("  Visa 6 cuotas ", MedioPago.CREDITO, "12.50", null, null));

        assertThat(creada.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(creada.getNombre()).isEqualTo("Visa 6 cuotas");
        assertThat(creada.getMedio()).isEqualTo(MedioPago.CREDITO);
        assertThat(creada.getRecargoPorcentaje()).isEqualByComparingTo("12.50");
        assertThat(creada.isActiva()).isTrue();
        assertThat(creada.getOrden()).isEqualTo(5);
    }

    @Test
    @DisplayName("crear: sin recargo vale 0 y con orden o activa explicitos los respeta")
    void crearConDefectosYExplicitos() {
        when(repositorio.contarScoped()).thenReturn(4L);
        when(repositorio.save(any(FormaPago.class))).thenAnswer(i -> i.getArgument(0));

        FormaPago sinRecargo = service.crear(datos("Mercado Pago", MedioPago.TRANSFERENCIA, null, false, 7));

        assertThat(sinRecargo.getRecargoPorcentaje()).isEqualByComparingTo("0");
        assertThat(sinRecargo.isActiva()).isFalse();
        assertThat(sinRecargo.getOrden()).isEqualTo(7);
    }

    @Test
    @DisplayName("crear: un nombre repetido en la ferreteria es 409")
    void crearNombreRepetido() {
        when(repositorio.contarScoped()).thenReturn(4L);
        when(repositorio.existeNombreScoped("Efectivo")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(datos("Efectivo", MedioPago.EFECTIVO, "0", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya hay una forma de pago con ese nombre.");

        verify(repositorio, never()).save(any());
    }

    @Test
    @DisplayName("actualizar: cambia nombre, medio, recargo, activa y orden")
    void actualizar() {
        FormaPago visa = forma(10, "Visa", MedioPago.CREDITO, "5.00", true);
        when(repositorio.findScoped(10L)).thenReturn(Optional.of(visa));
        when(repositorio.existeOtroConNombreScoped("Visa 6 cuotas", 10L)).thenReturn(false);

        FormaPago resultado = service.actualizar(10L, datos(" Visa 6 cuotas ", MedioPago.CREDITO, "15.00", false, 8));

        assertThat(resultado.getNombre()).isEqualTo("Visa 6 cuotas");
        assertThat(resultado.getRecargoPorcentaje()).isEqualByComparingTo("15.00");
        assertThat(resultado.isActiva()).isFalse();
        assertThat(resultado.getOrden()).isEqualTo(8);
    }

    @Test
    @DisplayName("actualizar: sin recargo, activa u orden en el pedido conserva los que tenia")
    void actualizarConservaLoQueNoVa() {
        FormaPago visa = forma(10, "Visa", MedioPago.CREDITO, "5.00", true);
        when(repositorio.findScoped(10L)).thenReturn(Optional.of(visa));

        FormaPago resultado = service.actualizar(10L, datos("Visa", MedioPago.CREDITO, null, null, null));

        assertThat(resultado.getRecargoPorcentaje()).isEqualByComparingTo("5.00");
        assertThat(resultado.isActiva()).isTrue();
        assertThat(resultado.getOrden()).isEqualTo(10);
    }

    @Test
    @DisplayName("actualizar: una forma de otra ferreteria o inexistente es 404")
    void actualizarInexistente() {
        when(repositorio.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(99L, datos("X", MedioPago.CREDITO, "0", true, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("actualizar: el nombre de otra forma de la misma ferreteria es 409; el propio nombre no")
    void actualizarNombreRepetido() {
        FormaPago visa = forma(10, "Visa", MedioPago.CREDITO, "5.00", true);
        when(repositorio.findScoped(10L)).thenReturn(Optional.of(visa));
        when(repositorio.existeOtroConNombreScoped("Efectivo", 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(10L, datos("Efectivo", MedioPago.CREDITO, "5.00", true, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya hay una forma de pago con ese nombre.");
    }

    @Test
    @DisplayName("actualizar: desactivar la ultima forma activa en efectivo es 422")
    void noSeDesactivaLaUltimaEnEfectivo() {
        FormaPago efectivo = forma(1, "Efectivo", MedioPago.EFECTIVO, "0", true);
        when(repositorio.findScoped(1L)).thenReturn(Optional.of(efectivo));
        when(repositorio.existeOtraActivaDeMedioScoped(MedioPago.EFECTIVO, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.actualizar(1L, datos("Efectivo", MedioPago.EFECTIVO, "0", false, null)))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("Tiene que quedar al menos una forma de pago en efectivo.");
        assertThat(efectivo.isActiva()).isTrue();
    }

    @Test
    @DisplayName("actualizar: cambiarle el medio a la ultima forma en efectivo tambien es 422")
    void noSeLeCambiaElMedioALaUltimaEnEfectivo() {
        FormaPago efectivo = forma(1, "Efectivo", MedioPago.EFECTIVO, "0", true);
        when(repositorio.findScoped(1L)).thenReturn(Optional.of(efectivo));
        when(repositorio.existeOtraActivaDeMedioScoped(MedioPago.EFECTIVO, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.actualizar(1L, datos("Efectivo", MedioPago.DEBITO, "0", true, null)))
                .isInstanceOf(ReglaDeNegocioException.class);
    }

    @Test
    @DisplayName("actualizar: con otra forma activa en efectivo, la primera se puede desactivar")
    void conOtraEnEfectivoSePuedeDesactivar() {
        FormaPago efectivo = forma(1, "Efectivo", MedioPago.EFECTIVO, "0", true);
        when(repositorio.findScoped(1L)).thenReturn(Optional.of(efectivo));
        when(repositorio.existeOtraActivaDeMedioScoped(MedioPago.EFECTIVO, 1L)).thenReturn(true);

        FormaPago resultado = service.actualizar(1L, datos("Efectivo", MedioPago.EFECTIVO, "0", false, null));

        assertThat(resultado.isActiva()).isFalse();
    }

    @Test
    @DisplayName("actualizar: una forma de otro medio, o ya inactiva, no dispara la regla del efectivo")
    void laReglaSoloMiraElEfectivoActivo() {
        FormaPago visa = forma(10, "Visa", MedioPago.CREDITO, "5.00", true);
        when(repositorio.findScoped(10L)).thenReturn(Optional.of(visa));

        service.actualizar(10L, datos("Visa", MedioPago.CREDITO, "5.00", false, null));

        verify(repositorio, never()).existeOtraActivaDeMedioScoped(any(), any());

        FormaPago efectivoInactivo = forma(2, "Efectivo viejo", MedioPago.EFECTIVO, "0", false);
        when(repositorio.findScoped(2L)).thenReturn(Optional.of(efectivoInactivo));

        service.actualizar(2L, datos("Efectivo viejo", MedioPago.DEBITO, "0", false, null));

        verify(repositorio, times(0)).existeOtraActivaDeMedioScoped(eq(MedioPago.EFECTIVO), eq(2L));
    }
}
