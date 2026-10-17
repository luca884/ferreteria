package com.ferreteria.auth.repository;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.model.Usuario;
import com.ferreteria.ferreteria.model.EstadoFerreteria;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * El login busca por email antes de conocer la ferreteria, por eso este repositorio
 * no extiende TenantScopedRepository. Los listados de usuarios de una ferreteria (CU-02)
 * deben filtrar siempre por ferreteriaId.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);

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
