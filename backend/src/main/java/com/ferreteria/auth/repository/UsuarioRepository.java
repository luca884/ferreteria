package com.ferreteria.auth.repository;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de tenant: los usuarios de una ferreteria se leen con los metodos *Scoped. El login busca por email
 * antes de conocer la ferreteria (findByEmailIgnoreCase) y el super admin no tiene ferreteria: esos accesos sin scope
 * viven solo en clases con {@code @UnscopedTenantAccess} (AuthService, SesionVigente, EmailUnico).
 */
public interface UsuarioRepository extends TenantScopedRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);

    /** Usuarios de la ferreteria por nombre; los inactivos solo si se piden. Llamar por {@link #listarScoped}. */
    @Query("""
            select u from Usuario u
            where u.ferreteriaId = :fid and (:incluirInactivos = true or u.activo = true)
            order by u.nombre, u.id
            """)
    List<Usuario> deFerreteria(@Param("fid") Long ferreteriaId, @Param("incluirInactivos") boolean incluirInactivos);

    /** Duenos activos de la ferreteria, sin contar al excluido. Llamar por {@link #otrosDuenosActivosScoped}. */
    @Query("""
            select count(u) from Usuario u
            where u.ferreteriaId = :fid and u.rol = com.ferreteria.auth.model.Rol.DUENO
              and u.activo = true and u.id <> :excluido
            """)
    long otrosDuenosActivos(@Param("fid") Long ferreteriaId, @Param("excluido") Long excluidoId);

    /**
     * Toma el candado de la fila de la ferreteria hasta el fin de la transaccion: serializa los cambios que pueden dejarla
     * sin Dueno activo. Llamar por {@link #bloquearFerreteriaScoped}.
     */
    @Query(value = "select id from ferreteria where id = :fid for update", nativeQuery = true)
    Long bloquearFerreteria(@Param("fid") Long ferreteriaId);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /** Usuarios activos por ferreteria y rol (para el estado general de la plataforma). */
    @Query("""
            select new com.ferreteria.auth.repository.UsuarioRepository$ConteoPorRol(u.ferreteriaId, u.rol, count(u))
            from Usuario u
            where u.activo = true and u.ferreteriaId is not null
            group by u.ferreteriaId, u.rol
            """)
    List<ConteoPorRol> conteoActivosPorRol();

    /** Todos los duenos activos de todas las ferreterias, del mas antiguo al mas nuevo (para la plataforma). */
    @Query("select u from Usuario u where u.rol = com.ferreteria.auth.model.Rol.DUENO and u.activo = true order by u.id")
    List<Usuario> duenosActivos();

    /** El usuario si es DUENO de esa ferreteria (para la plataforma). */
    @Query("select u from Usuario u where u.id = :id and u.ferreteriaId = :fid and u.rol = com.ferreteria.auth.model.Rol.DUENO")
    Optional<Usuario> duenoDe(@Param("fid") Long ferreteriaId, @Param("id") Long id);

    default List<Usuario> listarScoped(boolean incluirInactivos) {
        return deFerreteria(TenantContext.requireFerreteriaId(), incluirInactivos);
    }

    default long otrosDuenosActivosScoped(Long excluidoId) {
        return otrosDuenosActivos(TenantContext.requireFerreteriaId(), excluidoId);
    }

    default Long bloquearFerreteriaScoped() {
        return bloquearFerreteria(TenantContext.requireFerreteriaId());
    }

    record ConteoPorRol(Long ferreteriaId, Rol rol, long cantidad) {}

    /**
     * La sesion del token sigue vigente: el usuario existe, esta activo, tiene el mismo rol y la misma ferreteria
     * que dice el token, y esa ferreteria esta activa. El super admin no tiene ferreteria.
     */
    @Query("""
            select count(u) > 0 from Usuario u
            where u.id = :usuarioId and u.activo = true and u.rol = :rol
              and ((:ferreteriaId is null and u.ferreteriaId is null)
                or (u.ferreteriaId = :ferreteriaId and exists (
                      select 1 from Ferreteria f where f.id = u.ferreteriaId and f.estado = :activa)))
            """)
    boolean sesionVigente(@Param("usuarioId") Long usuarioId, @Param("rol") Rol rol,
                          @Param("ferreteriaId") Long ferreteriaId, @Param("activa") EstadoFerreteria activa);
}
