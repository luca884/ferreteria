package com.ferreteria.auth.repository;

import com.ferreteria.auth.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * El login busca por email antes de conocer la ferreteria, por eso este repositorio
 * no extiende TenantScopedRepository. Los listados de usuarios de una ferreteria (CU-02)
 * deben filtrar siempre por ferreteriaId.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);
}
