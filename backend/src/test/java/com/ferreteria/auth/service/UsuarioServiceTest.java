package com.ferreteria.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.MissingTenantException;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class UsuarioServiceTest {

    private static final long FERRETERIA = 5L;
    private static final long ACTOR = 10L;

    private UsuarioRepository usuarios;
    private EmailUnico emailUnico;
    private PasswordEncoder encoder;
    private UsuarioService servicio;

    @BeforeEach
    void armar() {
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        usuarios = mock(UsuarioRepository.class);
        emailUnico = mock(EmailUnico.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash-nuevo");
        when(usuarios.saveAndFlush(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        servicio = new UsuarioService(usuarios, emailUnico, encoder);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static Usuario usuario(Rol rol, boolean activo) {
        Usuario u = Usuario.nuevo(FERRETERIA, "x@t.com", "hash", "Equis", rol);
        u.cambiarActivo(activo);
        return u;
    }

    private void existe(long id, Usuario u) {
        when(usuarios.findScoped(id)).thenReturn(Optional.of(u));
    }

    private static DataIntegrityViolationException duplicado() {
        return new DataIntegrityViolationException("dup", new SQLException("Duplicate entry", "23000", 1062));
    }

    // ---- listar ----

    @Test
    @DisplayName("listar pide los de la ferreteria del contexto")
    void listarUsaElScope() {
        Usuario u = usuario(Rol.VENDEDOR, true);
        when(usuarios.listarScoped(true)).thenReturn(List.of(u));

        assertThat(servicio.listar(true)).containsExactly(u);
    }

    // ---- crear ----

    private static DatosUsuario datos(Rol rol, String password) {
        return new DatosUsuario("  Ana  ", "  ANA@Tornillo.com ", rol, password);
    }

    @Test
    @DisplayName("crear guarda un usuario de la ferreteria del JWT con email normalizado y la contraseña hasheada")
    void crearGuardaConLaFerreteriaDelContexto() {
        Usuario creado = servicio.crear(datos(Rol.VENDEDOR, "clave1234"));

        ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).saveAndFlush(capturado.capture());
        assertThat(capturado.getValue()).isSameAs(creado);
        assertThat(creado.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(creado.getEmail()).isEqualTo("ana@tornillo.com");
        assertThat(creado.getNombre()).isEqualTo("Ana");
        assertThat(creado.getRol()).isEqualTo(Rol.VENDEDOR);
        assertThat(creado.getPasswordHash()).isEqualTo("hash-nuevo");
        assertThat(creado.isActivo()).isTrue();
    }

    @Test
    @DisplayName("crear sin ferreteria en el contexto falla cerrado")
    void crearSinTenantFalla() {
        TenantContext.clear();

        assertThatThrownBy(() -> servicio.crear(datos(Rol.DUENO, "clave1234"))).isInstanceOf(MissingTenantException.class);
    }

    @Test
    @DisplayName("crear rechaza SUPER_ADMIN y rol nulo con 400 en el campo rol")
    void crearRechazaRolInvalido() {
        for (Rol rol : new Rol[] {Rol.SUPER_ADMIN, null}) {
            assertThatThrownBy(() -> servicio.crear(datos(rol, "clave1234")))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                        assertThat(e.errors()).containsOnlyKeys("rol");
                        assertThat(e.errors().get("rol")).isEqualTo("El rol tiene que ser Dueño o Vendedor.");
                    });
        }
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("crear valida la política de contraseña en el campo password")
    void crearValidaLaPolitica() {
        assertThatThrownBy(() -> servicio.crear(datos(Rol.VENDEDOR, "corta")))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("password"));
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("crear con el email en uso responde 409 en el campo email, sin hashear ni guardar")
    void crearConEmailEnUso() {
        when(emailUnico.enUso("  ANA@Tornillo.com ")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(datos(Rol.VENDEDOR, "clave1234")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Ese email ya está en uso.");
                    assertThat(e.errors()).containsOnlyKeys("email");
                });
        verify(usuarios, never()).saveAndFlush(any());
        verify(encoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("crear: si dos altas pasan el pre-chequeo, la unique de la base da 409 y no 500")
    void crearConDuplicadoDeLaBase() {
        when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(duplicado());

        assertThatThrownBy(() -> servicio.crear(datos(Rol.VENDEDOR, "clave1234")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errors()).containsOnlyKeys("email"));
    }

    @Test
    @DisplayName("crear relanza cualquier otro error de integridad")
    void crearRelanzaOtroErrorDeIntegridad() {
        DataIntegrityViolationException otro = new DataIntegrityViolationException("x",
                new SQLException("Data too long", "22001", 1406));
        when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(otro);

        assertThatThrownBy(() -> servicio.crear(datos(Rol.VENDEDOR, "clave1234"))).isSameAs(otro);
    }

    // ---- actualizar ----

    private static DatosEdicion edicion(Rol rol) {
        return new DatosEdicion("  Nuevo Nombre ", " Nuevo@Mail.com ", rol);
    }

    @Test
    @DisplayName("actualizar toma el lock de la ferretería ANTES de buscar el usuario")
    void actualizarBloqueaAntesDeBuscar() {
        existe(7, usuario(Rol.VENDEDOR, true));

        servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR);

        var orden = inOrder(usuarios);
        orden.verify(usuarios).bloquearFerreteriaScoped();
        orden.verify(usuarios).findScoped(7L);
    }

    @Test
    @DisplayName("actualizar cambia nombre, email normalizado y rol")
    void actualizarCambiaLosDatos() {
        Usuario u = usuario(Rol.VENDEDOR, true);
        existe(7, u);

        Usuario resultado = servicio.actualizar(7L, edicion(Rol.DUENO), ACTOR);

        assertThat(resultado.getNombre()).isEqualTo("Nuevo Nombre");
        assertThat(resultado.getEmail()).isEqualTo("nuevo@mail.com");
        assertThat(resultado.getRol()).isEqualTo(Rol.DUENO);
        verify(usuarios).saveAndFlush(u);
    }

    @Test
    @DisplayName("actualizar un usuario que no es de esta ferretería (o no existe) responde 404")
    void actualizarInexistente() {
        when(usuarios.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(99L, edicion(Rol.VENDEDOR), ACTOR))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Usuario no encontrado"));
    }

    @Test
    @DisplayName("actualizar rechaza SUPER_ADMIN con 400 en rol")
    void actualizarRechazaSuperAdmin() {
        existe(7, usuario(Rol.VENDEDOR, true));

        assertThatThrownBy(() -> servicio.actualizar(7L, edicion(Rol.SUPER_ADMIN), ACTOR))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("rol"));
    }

    @Test
    @DisplayName("un Dueño no puede cambiarse el rol a sí mismo (422)")
    void noPuedeCambiarseElRol() {
        existe(ACTOR, usuario(Rol.DUENO, true));

        assertThatThrownBy(() -> servicio.actualizar(ACTOR, edicion(Rol.VENDEDOR), ACTOR))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("No podés cambiar tu propio rol."));
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("un Dueño puede editar su propio nombre y email (200)")
    void puedeEditarSuNombreYEmail() {
        Usuario yo = usuario(Rol.DUENO, true);
        existe(ACTOR, yo);

        Usuario resultado = servicio.actualizar(ACTOR, edicion(Rol.DUENO), ACTOR);

        assertThat(resultado.getNombre()).isEqualTo("Nuevo Nombre");
    }

    @Test
    @DisplayName("pasar al último Dueño activo a Vendedor responde 422")
    void ultimoDuenoNoPasaAVendedor() {
        existe(7, usuario(Rol.DUENO, true));
        when(usuarios.otrosDuenosActivosScoped(7L)).thenReturn(0L);

        assertThatThrownBy(() -> servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Tiene que quedar al menos un Dueño activo."));
    }

    @Test
    @DisplayName("pasar a Vendedor a un Dueño cuando hay otro Dueño activo está permitido")
    void conOtroDuenoPasaAVendedor() {
        existe(7, usuario(Rol.DUENO, true));
        when(usuarios.otrosDuenosActivosScoped(7L)).thenReturn(1L);

        assertThat(servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR).getRol()).isEqualTo(Rol.VENDEDOR);
    }

    @Test
    @DisplayName("un Dueño inactivo puede pasar a Vendedor aunque no queden otros: no saca a un Dueño activo")
    void duenoInactivoNoCuenta() {
        existe(7, usuario(Rol.DUENO, false));
        when(usuarios.otrosDuenosActivosScoped(7L)).thenReturn(0L);

        assertThat(servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR).getRol()).isEqualTo(Rol.VENDEDOR);
    }

    @Test
    @DisplayName("actualizar con el email de otro usuario responde 409 en email")
    void actualizarConEmailDeOtro() {
        existe(7, usuario(Rol.VENDEDOR, true));
        when(emailUnico.enUsoPorOtro(" Nuevo@Mail.com ", 7L)).thenReturn(true);

        assertThatThrownBy(() -> servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errors()).containsOnlyKeys("email"));
    }

    @Test
    @DisplayName("actualizar conservando el propio email no choca contra sí mismo")
    void actualizarConservandoElEmail() {
        Usuario u = usuario(Rol.VENDEDOR, true);
        existe(7, u);
        when(emailUnico.enUsoPorOtro(anyString(), anyLong())).thenReturn(false);

        assertThat(servicio.actualizar(7L, new DatosEdicion("Equis", "x@t.com", Rol.VENDEDOR), ACTOR).getEmail())
                .isEqualTo("x@t.com");
    }

    @Test
    @DisplayName("actualizar: la unique de la base también da 409")
    void actualizarConDuplicadoDeLaBase() {
        existe(7, usuario(Rol.VENDEDOR, true));
        when(usuarios.saveAndFlush(any(Usuario.class))).thenThrow(duplicado());

        assertThatThrownBy(() -> servicio.actualizar(7L, edicion(Rol.VENDEDOR), ACTOR))
                .isInstanceOf(ConflictException.class);
    }

    // ---- cambiarActivo ----

    @Test
    @DisplayName("cambiarActivo toma el lock antes de buscar")
    void cambiarActivoBloqueaAntesDeBuscar() {
        existe(7, usuario(Rol.VENDEDOR, true));

        servicio.cambiarActivo(7L, false, ACTOR);

        var orden = inOrder(usuarios);
        orden.verify(usuarios).bloquearFerreteriaScoped();
        orden.verify(usuarios).findScoped(7L);
    }

    @Test
    @DisplayName("cambiarActivo de un usuario ajeno o inexistente responde 404")
    void cambiarActivoInexistente() {
        when(usuarios.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarActivo(99L, false, ACTOR))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("desactiva a un Vendedor")
    void desactivaUnVendedor() {
        Usuario u = usuario(Rol.VENDEDOR, true);
        existe(7, u);

        assertThat(servicio.cambiarActivo(7L, false, ACTOR).isActivo()).isFalse();
        verify(usuarios).saveAndFlush(u);
    }

    @Test
    @DisplayName("un Dueño no puede desactivarse a sí mismo (422)")
    void noPuedeDesactivarseASiMismo() {
        existe(ACTOR, usuario(Rol.DUENO, true));

        assertThatThrownBy(() -> servicio.cambiarActivo(ACTOR, false, ACTOR))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("No podés desactivar tu propio usuario."));
    }

    @Test
    @DisplayName("desactivar al último Dueño activo responde 422")
    void ultimoDuenoNoSeDesactiva() {
        existe(7, usuario(Rol.DUENO, true));
        when(usuarios.otrosDuenosActivosScoped(7L)).thenReturn(0L);

        assertThatThrownBy(() -> servicio.cambiarActivo(7L, false, ACTOR))
                .isInstanceOfSatisfying(ReglaDeNegocioException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Tiene que quedar al menos un Dueño activo."));
    }

    @Test
    @DisplayName("desactivar a un Dueño con otro Dueño activo está permitido")
    void conOtroDuenoSeDesactiva() {
        existe(7, usuario(Rol.DUENO, true));
        when(usuarios.otrosDuenosActivosScoped(7L)).thenReturn(1L);

        assertThat(servicio.cambiarActivo(7L, false, ACTOR).isActivo()).isFalse();
    }

    @Test
    @DisplayName("reactivar no chequea nada")
    void reactivar() {
        existe(7, usuario(Rol.DUENO, false));

        assertThat(servicio.cambiarActivo(7L, true, ACTOR).isActivo()).isTrue();
        verify(usuarios, never()).otrosDuenosActivosScoped(anyLong());
    }

    // ---- resetearContrasena ----

    @Test
    @DisplayName("resetear la contraseña hashea la nueva y la guarda en el usuario")
    void resetear() {
        Usuario u = usuario(Rol.VENDEDOR, true);
        existe(7, u);

        servicio.resetearContrasena(7L, "otraclave99");

        verify(encoder).encode("otraclave99");
        assertThat(u.getPasswordHash()).isEqualTo("hash-nuevo");
        verify(usuarios).saveAndFlush(u);
    }

    @Test
    @DisplayName("resetear la contraseña de un usuario ajeno responde 404 sin hashear")
    void resetearAjeno() {
        when(usuarios.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.resetearContrasena(99L, "otraclave99"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(encoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("resetear valida la política en el campo password")
    void resetearValidaLaPolitica() {
        existe(7, usuario(Rol.VENDEDOR, true));

        assertThatThrownBy(() -> servicio.resetearContrasena(7L, "corta"))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("password"));
    }

    // ---- transacciones ----

    @Test
    @DisplayName("actualizar y cambiarActivo corren en READ_COMMITTED: el conteo posterior al lock ve lo que commiteó el otro")
    void readCommitted() throws Exception {
        for (Method m : List.of(
                UsuarioService.class.getMethod("actualizar", Long.class, DatosEdicion.class, Long.class),
                UsuarioService.class.getMethod("cambiarActivo", Long.class, boolean.class, Long.class))) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as(m.getName()).isNotNull();
            assertThat(tx.isolation()).as(m.getName()).isEqualTo(Isolation.READ_COMMITTED);
        }
    }
}
