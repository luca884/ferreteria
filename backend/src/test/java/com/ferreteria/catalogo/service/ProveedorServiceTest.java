package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.ProveedorRequest;
import com.ferreteria.catalogo.dto.ProveedorResponse;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
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
class ProveedorServiceTest {

    private static final Long FERRETERIA = 3L;

    @Mock
    ProveedorRepository proveedorRepository;

    @InjectMocks
    ProveedorService service;

    @BeforeEach
    void tenant() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    // ---- crear ----

    @Test
    @DisplayName("crear guarda el nombre sin espacios sobrantes, en la ferreteria del contexto y activo")
    void crearNormalizaYGuarda() {
        when(proveedorRepository.existeNombreScoped("Sinteplast")).thenReturn(false);
        when(proveedorRepository.save(any(Proveedor.class))).thenAnswer(inv -> inv.getArgument(0));

        ProveedorResponse respuesta = service.crear(new ProveedorRequest("  Sinteplast ", null, null));

        ArgumentCaptor<Proveedor> guardado = ArgumentCaptor.forClass(Proveedor.class);
        verify(proveedorRepository).save(guardado.capture());
        assertThat(guardado.getValue().getNombre()).isEqualTo("Sinteplast");
        assertThat(guardado.getValue().getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(guardado.getValue().isActivo()).isTrue();
        assertThat(respuesta.nombre()).isEqualTo("Sinteplast");
        assertThat(respuesta.cuit()).isNull();
        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    @DisplayName("crear con activo=false lo deja inactivo")
    void crearInactivo() {
        when(proveedorRepository.existeNombreScoped("Tersuave")).thenReturn(false);
        when(proveedorRepository.save(any(Proveedor.class))).thenAnswer(inv -> inv.getArgument(0));

        ProveedorResponse respuesta = service.crear(new ProveedorRequest("Tersuave", "30-12345678-9", false));

        assertThat(respuesta.activo()).isFalse();
        assertThat(respuesta.cuit()).isEqualTo("30-12345678-9");
    }

    @Test
    @DisplayName("crear con un nombre repetido da 409 con el mensaje del spec y no guarda")
    void crearDuplicadoDa409() {
        when(proveedorRepository.existeNombreScoped("Sinteplast")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(new ProveedorRequest("  Sinteplast ", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un proveedor llamado Sinteplast.");
        verify(proveedorRepository, never()).save(any());
    }

    // ---- obtener / listar ----

    @Test
    @DisplayName("obtener devuelve el proveedor propio")
    void obtenerPropio() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Sinteplast", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));

        assertThat(service.obtener(5L).nombre()).isEqualTo("Sinteplast");
    }

    @Test
    @DisplayName("obtener un id ajeno o inexistente da 404 \"Proveedor no encontrado\"")
    void obtenerAjenoDa404() {
        when(proveedorRepository.findScoped(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(7L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");
    }

    @Test
    @DisplayName("listar devuelve lo que el repositorio trae scoped (solo activos)")
    void listarSoloActivos() {
        when(proveedorRepository.listarScoped()).thenReturn(List.of(
                new Proveedor(FERRETERIA, "Pinturas Atlantico", null),
                new Proveedor(FERRETERIA, "Sinteplast", null)));

        List<ProveedorResponse> lista = service.listar();

        assertThat(lista).extracting(ProveedorResponse::nombre).containsExactly("Pinturas Atlantico", "Sinteplast");
    }

    // ---- actualizar ----

    @Test
    @DisplayName("actualizar cambia nombre, CUIT y activo del proveedor propio")
    void actualizarPropio() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Sinteplast", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Sinteplast SA", 5L)).thenReturn(false);

        ProveedorResponse respuesta = service.actualizar(5L, new ProveedorRequest(" Sinteplast SA ", "30-12345678-9", false));

        assertThat(proveedor.getNombre()).isEqualTo("Sinteplast SA");
        assertThat(proveedor.getCuit()).isEqualTo("30-12345678-9");
        assertThat(proveedor.isActivo()).isFalse();
        assertThat(respuesta.nombre()).isEqualTo("Sinteplast SA");
    }

    @Test
    @DisplayName("actualizar sin activo conserva el estado actual: uno dado de baja sigue de baja")
    void actualizarActivoNuloConservaElEstadoInactivo() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Tersuave", null);
        proveedor.desactivar();
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Tersuave", 5L)).thenReturn(false);

        ProveedorResponse respuesta = service.actualizar(5L, new ProveedorRequest("Tersuave", null, null));

        assertThat(proveedor.isActivo()).isFalse();
        assertThat(respuesta.activo()).isFalse();
    }

    @Test
    @DisplayName("actualizar sin activo conserva el estado actual: uno activo sigue activo")
    void actualizarActivoNuloConservaElEstadoActivo() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Tersuave", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Tersuave", 5L)).thenReturn(false);

        service.actualizar(5L, new ProveedorRequest("Tersuave", null, null));

        assertThat(proveedor.isActivo()).isTrue();
    }

    @Test
    @DisplayName("actualizar con activo=true reactiva a uno dado de baja")
    void actualizarActivoTrueReactiva() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Tersuave", null);
        proveedor.desactivar();
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Tersuave", 5L)).thenReturn(false);

        service.actualizar(5L, new ProveedorRequest("Tersuave", null, true));

        assertThat(proveedor.isActivo()).isTrue();
    }

    @Test
    @DisplayName("actualizar excluye al propio proveedor al buscar el nombre repetido")
    void actualizarExcluyeAlPropio() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Tersuave", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Tersuave", 5L)).thenReturn(false);

        service.actualizar(5L, new ProveedorRequest("Tersuave", "30-12345678-9", null));

        verify(proveedorRepository).existeOtroConNombreScoped("Tersuave", 5L);
        verify(proveedorRepository, never()).existeNombreScoped(any());
        assertThat(proveedor.getCuit()).isEqualTo("30-12345678-9");
    }

    @Test
    @DisplayName("renombrar a un nombre de otro proveedor da 409 y no cambia nada")
    void actualizarNombreOcupadoDa409() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Tersuave", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));
        when(proveedorRepository.existeOtroConNombreScoped("Sinteplast", 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(5L, new ProveedorRequest("Sinteplast", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Ya existe un proveedor llamado Sinteplast.");
        assertThat(proveedor.getNombre()).isEqualTo("Tersuave");
    }

    @Test
    @DisplayName("actualizar un proveedor ajeno da 404")
    void actualizarAjenoDa404() {
        when(proveedorRepository.findScoped(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(7L, new ProveedorRequest("X", null, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");
        verify(proveedorRepository, never()).existeOtroConNombreScoped(any(), any());
    }

    // ---- desactivar ----

    @Test
    @DisplayName("desactivar pone activo=false sin borrar la fila")
    void desactivarNoBorra() {
        Proveedor proveedor = new Proveedor(FERRETERIA, "Sinteplast", null);
        when(proveedorRepository.findScoped(5L)).thenReturn(Optional.of(proveedor));

        service.desactivar(5L);

        assertThat(proveedor.isActivo()).isFalse();
        verify(proveedorRepository, never()).delete(any());
        verify(proveedorRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("desactivar un proveedor ajeno da 404")
    void desactivarAjenoDa404() {
        when(proveedorRepository.findScoped(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.desactivar(7L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Proveedor no encontrado");
    }
}
