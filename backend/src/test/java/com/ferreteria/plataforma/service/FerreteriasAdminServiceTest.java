package com.ferreteria.plataforma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.api.UsuariosParaPlataforma;
import com.ferreteria.auth.api.UsuariosParaPlataforma.EmailEnUsoException;
import com.ferreteria.auth.api.UsuariosParaPlataforma.ResumenUsuarios;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.CondicionIva;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.plataforma.service.DatosAlta.Dueno;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class FerreteriasAdminServiceTest {

    private static final String CUIT_OK = "30-12345678-1";

    private FerreteriaRepository ferreterias;
    private UsuariosParaPlataforma usuarios;
    private FerreteriasAdminService servicio;

    @BeforeEach
    void armar() {
        ferreterias = mock(FerreteriaRepository.class);
        usuarios = mock(UsuariosParaPlataforma.class);
        when(ferreterias.saveAndFlush(any(Ferreteria.class))).thenAnswer(i -> {
            Ferreteria f = i.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 3L);
            return f;
        });
        when(usuarios.crearDueno(anyLong(), anyString(), anyString(), anyString())).thenReturn(8L);
        servicio = new FerreteriasAdminService(ferreterias, usuarios);
    }

    private static DatosAlta alta(String cuit) {
        return new DatosAlta("  Tornillo SA ", cuit, CondicionIva.RESPONSABLE_INSCRIPTO,
                new Dueno(" Ana ", "ana@tornillo.com", "clave1234"));
    }

    private static DataIntegrityViolationException duplicado() {
        return new DataIntegrityViolationException("dup", new SQLException("Duplicate entry", "23000", 1062));
    }

    private void assertInvalido(DatosAlta datos, String clave) {
        assertThatThrownBy(() -> servicio.alta(datos)).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsOnlyKeys(clave));
        verify(ferreterias, never()).saveAndFlush(any());
    }

    private void assertConflicto(DatosAlta datos, String clave) {
        assertThatThrownBy(() -> servicio.alta(datos)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.errors()).containsOnlyKeys(clave));
    }

    @Test
    @DisplayName("alta es transaccional")
    void altaEsTransaccional() throws Exception {
        Method m = FerreteriasAdminService.class.getMethod("alta", DatosAlta.class);

        assertThat(m.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    @DisplayName("alta guarda la ferretería con el CUIT con guiones y crea el Dueño de su id")
    void altaOk() {
        FerreteriaAdmin admin = servicio.alta(alta("30123456781"));

        ArgumentCaptor<Ferreteria> guardada = ArgumentCaptor.forClass(Ferreteria.class);
        verify(ferreterias).saveAndFlush(guardada.capture());
        assertThat(guardada.getValue().getCuit()).isEqualTo(CUIT_OK);
        assertThat(guardada.getValue().getRazonSocial()).isEqualTo("Tornillo SA");
        verify(usuarios).crearDueno(3L, " Ana ", "ana@tornillo.com", "clave1234");
        assertThat(admin.id()).isEqualTo(3L);
        assertThat(admin.cuit()).isEqualTo(CUIT_OK);
        assertThat(admin.estado()).isEqualTo(EstadoFerreteria.ACTIVA);
        assertThat(admin.duenosActivos()).isEqualTo(1);
        assertThat(admin.vendedoresActivos()).isZero();
        assertThat(admin.duenoId()).isEqualTo(8L);
        assertThat(admin.emailDueno()).isEqualTo("ana@tornillo.com");
    }

    @Test
    @DisplayName("un CUIT con puntos y espacios también se guarda con guiones")
    void cuitConPuntos() {
        servicio.alta(alta("30.12345678.1"));

        ArgumentCaptor<Ferreteria> guardada = ArgumentCaptor.forClass(Ferreteria.class);
        verify(ferreterias).saveAndFlush(guardada.capture());
        assertThat(guardada.getValue().getCuit()).isEqualTo(CUIT_OK);
    }

    @Test
    @DisplayName("CUIT vacío: 400 en cuit")
    void cuitVacio() {
        assertInvalido(alta("  "), "cuit");
        assertInvalido(alta(null), "cuit");
    }

    @Test
    @DisplayName("CUIT con dígito verificador o largo inválido: 400 en cuit")
    void cuitInvalido() {
        assertInvalido(alta("30-12345678-2"), "cuit");
        assertInvalido(alta("123"), "cuit");
    }

    @Test
    @DisplayName("CUIT con prefijo que no existe (10-): 400 en cuit")
    void cuitMalPrefijo() {
        assertThatThrownBy(() -> servicio.alta(alta("10-12345678-0"))).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsEntry("cuit", "El CUIT no es válido: revisá los dos primeros dígitos."));
    }

    @Test
    @DisplayName("la contraseña mala del Dueño sale con la clave dueno.password")
    void contrasenaRemapeada() {
        when(usuarios.crearDueno(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new DatosInvalidosException(Map.of("password", "La contraseña no puede ser solo espacios.")));

        assertThatThrownBy(() -> servicio.alta(alta(CUIT_OK))).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsOnly(Map.entry("dueno.password", "La contraseña no puede ser solo espacios.")));
    }

    @Test
    @DisplayName("CUIT ya registrado: 409 en cuit y no se guarda nada")
    void cuitRepetido() {
        when(ferreterias.existsByCuit(CUIT_OK)).thenReturn(true);

        assertConflicto(alta(CUIT_OK), "cuit");
        verify(ferreterias, never()).saveAndFlush(any());
        verify(usuarios, never()).crearDueno(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("email en uso: 409 en dueno.email y no se guarda nada")
    void emailEnUso() {
        when(usuarios.emailEnUso("ana@tornillo.com")).thenReturn(true);

        assertConflicto(alta(CUIT_OK), "dueno.email");
        verify(ferreterias, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("el email que gana la carrera contra la unique: 409 en dueno.email")
    void emailEnUsoPorCarrera() {
        when(usuarios.crearDueno(anyLong(), anyString(), anyString(), anyString())).thenThrow(new EmailEnUsoException());

        assertConflicto(alta(CUIT_OK), "dueno.email");
    }

    @Test
    @DisplayName("el CUIT que gana la carrera contra la unique: 409 en cuit")
    void cuitPorCarrera() {
        when(ferreterias.saveAndFlush(any(Ferreteria.class))).thenThrow(duplicado());

        assertConflicto(alta(CUIT_OK), "cuit");
        verify(usuarios, never()).crearDueno(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("otro error de la base se relanza")
    void otroErrorSeRelanza() {
        DataIntegrityViolationException otro = new DataIntegrityViolationException("x",
                new SQLException("Data too long", "22001", 1406));
        when(ferreterias.saveAndFlush(any(Ferreteria.class))).thenThrow(otro);

        assertThatThrownBy(() -> servicio.alta(alta(CUIT_OK))).isSameAs(otro);
    }

    @Test
    @DisplayName("listar cuenta activas y suspendidas y completa cada una con su resumen de usuarios")
    void listar() {
        Ferreteria a = ferreteria(1L, "Arco", EstadoFerreteria.ACTIVA);
        Ferreteria b = ferreteria(2L, "Bulón", EstadoFerreteria.SUSPENDIDA);
        Ferreteria c = ferreteria(4L, "Clavo", EstadoFerreteria.ACTIVA);
        when(ferreterias.todasOrdenadas()).thenReturn(List.of(a, b, c));
        when(usuarios.resumenPorFerreteria()).thenReturn(Map.of(1L, new ResumenUsuarios(2, 3, 7L, "d@arco.com")));

        EstadoGeneral estado = servicio.listar();

        assertThat(estado.activas()).isEqualTo(2);
        assertThat(estado.suspendidas()).isEqualTo(1);
        assertThat(estado.ferreterias()).extracting(FerreteriaAdmin::razonSocial).containsExactly("Arco", "Bulón", "Clavo");
        assertThat(estado.ferreterias().get(0).emailDueno()).isEqualTo("d@arco.com");
        assertThat(estado.ferreterias().get(0).vendedoresActivos()).isEqualTo(3);
        assertThat(estado.ferreterias().get(2).duenosActivos()).isZero();
        assertThat(estado.ferreterias().get(2).emailDueno()).isNull();
    }

    private static Ferreteria ferreteria(long id, String razon, EstadoFerreteria estado) {
        Ferreteria f = new Ferreteria(razon, CUIT_OK, CondicionIva.EXENTO);
        ReflectionTestUtils.setField(f, "id", id);
        f.cambiarActivo(estado == EstadoFerreteria.ACTIVA);
        return f;
    }

    @Test
    @DisplayName("actualizar cambia razón social e IVA y deja el CUIT")
    void actualizar() {
        Ferreteria f = ferreteria(1L, "Vieja", EstadoFerreteria.ACTIVA);
        when(ferreterias.findById(1L)).thenReturn(Optional.of(f));
        when(usuarios.resumenPorFerreteria()).thenReturn(Map.of());

        FerreteriaAdmin admin = servicio.actualizar(1L, " Nueva SA ", CondicionIva.MONOTRIBUTISTA);

        assertThat(f.getRazonSocial()).isEqualTo("Nueva SA");
        assertThat(f.getCondicionIva()).isEqualTo(CondicionIva.MONOTRIBUTISTA);
        assertThat(f.getCuit()).isEqualTo(CUIT_OK);
        assertThat(admin.razonSocial()).isEqualTo("Nueva SA");
    }

    @Test
    @DisplayName("actualizar y cambiarActivo sobre una ferretería inexistente: 404")
    void inexistente() {
        when(ferreterias.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(9L, "X", CondicionIva.EXENTO))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Ferretería no encontrada");
        assertThatThrownBy(() -> servicio.cambiarActivo(9L, false))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Ferretería no encontrada");
    }

    @Test
    @DisplayName("cambiarActivo suspende y reactiva")
    void cambiarActivo() {
        Ferreteria f = ferreteria(1L, "Arco", EstadoFerreteria.ACTIVA);
        when(ferreterias.findById(1L)).thenReturn(Optional.of(f));
        when(usuarios.resumenPorFerreteria()).thenReturn(Map.of());

        assertThat(servicio.cambiarActivo(1L, false).estado()).isEqualTo(EstadoFerreteria.SUSPENDIDA);
        assertThat(servicio.cambiarActivo(1L, true).estado()).isEqualTo(EstadoFerreteria.ACTIVA);
    }

    @Test
    @DisplayName("resetear: ferretería inexistente o usuario que no es su Dueño dan 404 «Usuario no encontrado»")
    void resetear404() {
        when(ferreterias.existsById(9L)).thenReturn(false);
        when(ferreterias.existsById(1L)).thenReturn(true);
        when(usuarios.resetearContrasenaDeDueno(1L, 5L, "clave1234")).thenReturn(false);

        assertThatThrownBy(() -> servicio.resetearContrasenaDeDueno(9L, 5L, "clave1234"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Usuario no encontrado");
        assertThatThrownBy(() -> servicio.resetearContrasenaDeDueno(1L, 5L, "clave1234"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Usuario no encontrado");
        verify(usuarios, never()).resetearContrasenaDeDueno(eq(9L), anyLong(), anyString());
    }

    @Test
    @DisplayName("resetear delega en el puerto cuando el Dueño es de esa ferretería")
    void resetear() {
        when(ferreterias.existsById(1L)).thenReturn(true);
        when(usuarios.resetearContrasenaDeDueno(1L, 5L, "clave1234")).thenReturn(true);

        servicio.resetearContrasenaDeDueno(1L, 5L, "clave1234");

        verify(usuarios).resetearContrasenaDeDueno(1L, 5L, "clave1234");
    }
}
