package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.auth.repository.UsuarioRepository;
import com.ferreteria.auth.repository.UsuarioRepository.ConteoPorRol;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Las consultas de UsuarioRepository que usa la plataforma (estado general y reset del Dueño), contra MySQL real. */
class UsuarioRepositoryPlataformaIntegrationTest extends UsuariosTestBase {

    @Autowired
    UsuarioRepository usuarios;

    private long ferreteria;
    private long otra;
    private long dueno1;
    private long dueno2;
    private long duenoInactivo;
    private long vendedor;

    @BeforeEach
    void armar() {
        ferreteria = crearFerreteria("Repo plataforma");
        otra = crearFerreteria("Repo plataforma otra");
        dueno1 = crearUsuario(ferreteria, unico("d1") + "@repo.test", "Dueño uno", "DUENO");
        dueno2 = crearUsuario(ferreteria, unico("d2") + "@repo.test", "Dueño dos", "DUENO");
        duenoInactivo = crearUsuario(ferreteria, unico("d3") + "@repo.test", "Dueño inactivo", "DUENO");
        jdbc.update("update usuario set activo = false where id = ?", duenoInactivo);
        vendedor = crearUsuario(ferreteria, unico("v1") + "@repo.test", "Vendedor", "VENDEDOR");
        crearUsuario(ferreteria, unico("v2") + "@repo.test", "Vendedor inactivo", "VENDEDOR");
        jdbc.update("update usuario set activo = false where email like ?", "v2-%@repo.test");
    }

    private long cantidad(List<ConteoPorRol> conteos, long fid, Rol rol) {
        return conteos.stream().filter(c -> c.ferreteriaId() == fid && c.rol() == rol).mapToLong(ConteoPorRol::cantidad).sum();
    }

    @Test
    @DisplayName("conteoActivosPorRol cuenta solo activos, por ferretería y rol, y deja afuera al super admin")
    void conteoActivosPorRol() {
        List<ConteoPorRol> conteos = usuarios.conteoActivosPorRol();

        assertThat(cantidad(conteos, ferreteria, Rol.DUENO)).isEqualTo(2);
        assertThat(cantidad(conteos, ferreteria, Rol.VENDEDOR)).isEqualTo(1);
        assertThat(cantidad(conteos, otra, Rol.DUENO)).isZero();
        assertThat(conteos).noneMatch(c -> c.ferreteriaId() == null || c.rol() == Rol.SUPER_ADMIN);
    }

    @Test
    @DisplayName("duenosActivos trae solo Dueños activos, del id más bajo al más alto")
    void duenosActivos() {
        List<Usuario> duenos = usuarios.duenosActivos();

        assertThat(duenos).allMatch(u -> u.getRol() == Rol.DUENO && u.isActivo());
        assertThat(duenos).extracting(Usuario::getId).isSorted();
        List<Long> deEstaFerreteria = duenos.stream().filter(u -> u.getFerreteriaId() == ferreteria).map(Usuario::getId).toList();
        assertThat(deEstaFerreteria).containsExactly(dueno1, dueno2);
    }

    @Test
    @DisplayName("duenoDe devuelve al usuario solo si es DUENO de esa ferretería")
    void duenoDe() {
        assertThat(usuarios.duenoDe(ferreteria, dueno1)).isPresent();
        assertThat(usuarios.duenoDe(ferreteria, duenoInactivo)).isPresent();
        assertThat(usuarios.duenoDe(ferreteria, vendedor)).isEmpty();
        assertThat(usuarios.duenoDe(otra, dueno1)).isEmpty();
        assertThat(usuarios.duenoDe(ferreteria, 999_999_999L)).isEmpty();
    }
}
