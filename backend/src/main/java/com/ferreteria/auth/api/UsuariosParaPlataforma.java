package com.ferreteria.auth.api;

import java.util.Map;

/**
 * Puerto de {@code auth} para el modulo {@code plataforma} (super admin). Solo tipos del JDK: la plataforma no conoce las
 * entidades, los repositorios ni los servicios de auth.
 */
public interface UsuariosParaPlataforma {

    /**
     * Crea el primer Dueno de esa ferreteria y devuelve su id. Exige una transaccion abierta (MANDATORY): el alta de la
     * ferreteria y la de su Dueno son atomicas.
     *
     * @throws EmailEnUsoException si el email ya lo usa cualquier usuario
     */
    long crearDueno(long ferreteriaId, String nombre, String email, String password);

    boolean emailEnUso(String email);

    /** Usuarios activos por rol y el Dueno activo de alta mas antigua (menor id), por ferreteria. Sin N+1. */
    Map<Long, ResumenUsuarios> resumenPorFerreteria();

    /** Resetea la contrasena si {@code usuarioId} es DUENO de {@code ferreteriaId}; si no, devuelve false (404 arriba). */
    boolean resetearContrasenaDeDueno(long ferreteriaId, long usuarioId, String password);

    /** {@code duenoId} y {@code emailDueno} son null si la ferreteria no tiene ningun Dueno activo. */
    record ResumenUsuarios(long duenosActivos, long vendedoresActivos, Long duenoId, String emailDueno) {}

    /** El email ya esta en uso por otro usuario. */
    final class EmailEnUsoException extends RuntimeException {

        public EmailEnUsoException() {
            super("Ese email ya está en uso.");
        }
    }
}
