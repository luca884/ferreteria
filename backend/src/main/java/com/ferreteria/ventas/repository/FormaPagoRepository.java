package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FormaPagoRepository extends TenantScopedRepository<FormaPago, Long> {

    @Query("select f from FormaPago f where f.ferreteriaId = :fid order by f.orden, f.nombre")
    List<FormaPago> deFerreteria(@Param("fid") Long ferreteriaId);

    @Query("select f from FormaPago f where f.ferreteriaId = :fid and f.activa = true order by f.orden, f.nombre")
    List<FormaPago> activasDeFerreteria(@Param("fid") Long ferreteriaId);

    @Query("select f from FormaPago f where f.ferreteriaId = :fid and f.id in :ids")
    List<FormaPago> porIds(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    @Query("select count(f) from FormaPago f where f.ferreteriaId = :fid")
    long contarDeFerreteria(@Param("fid") Long ferreteriaId);

    boolean existsByFerreteriaIdAndNombreIgnoreCase(Long ferreteriaId, String nombre);

    boolean existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(Long ferreteriaId, String nombre, Long id);

    @Query("select count(f) > 0 from FormaPago f where f.ferreteriaId = :fid and f.medio = :medio "
            + "and f.activa = true and f.id <> :idExcluido")
    boolean existeOtraActivaDeMedio(@Param("fid") Long ferreteriaId, @Param("medio") MedioPago medio,
            @Param("idExcluido") Long idExcluido);

    @Query("select coalesce(max(f.orden), 0) from FormaPago f where f.ferreteriaId = :fid")
    int maxOrden(@Param("fid") Long ferreteriaId);

    /**
     * Toma el candado de la fila de la ferreteria hasta el fin de la transaccion. Los INSERT IGNORE que chocan contra la
     * clave unica se traban entre si (deadlock de InnoDB): quien siembra primero los serializa con este candado.
     */
    @Query(value = "select id from ferreteria where id = :fid for update", nativeQuery = true)
    Long bloquearFerreteria(@Param("fid") Long ferreteriaId);

    /** INSERT IGNORE: si dos pedidos siembran a la vez, el segundo no rompe contra la clave unica del nombre. */
    @Modifying
    @Query(value = "insert ignore into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
            + "values (:fid, :nombre, :medio, 0, :activa, :orden)", nativeQuery = true)
    int sembrar(@Param("fid") Long ferreteriaId, @Param("nombre") String nombre, @Param("medio") String medio,
            @Param("orden") int orden, @Param("activa") boolean activa);

    default List<FormaPago> listarScoped() {
        return deFerreteria(TenantContext.requireFerreteriaId());
    }

    default List<FormaPago> listarActivasScoped() {
        return activasDeFerreteria(TenantContext.requireFerreteriaId());
    }

    /** Las que existen en esta ferreteria entre esas ids (activas o no): las de otra ferreteria no vuelven. */
    default List<FormaPago> porIdsScoped(Collection<Long> ids) {
        return porIds(TenantContext.requireFerreteriaId(), ids);
    }

    default long contarScoped() {
        return contarDeFerreteria(TenantContext.requireFerreteriaId());
    }

    default boolean existeNombreScoped(String nombre) {
        return existsByFerreteriaIdAndNombreIgnoreCase(TenantContext.requireFerreteriaId(), nombre);
    }

    /** Para renombrar: busca otra forma con ese nombre, excluyendo la propia. */
    default boolean existeOtroConNombreScoped(String nombre, Long idPropio) {
        return existsByFerreteriaIdAndNombreIgnoreCaseAndIdNot(TenantContext.requireFerreteriaId(), nombre, idPropio);
    }

    default boolean existeOtraActivaDeMedioScoped(MedioPago medio, Long idExcluido) {
        return existeOtraActivaDeMedio(TenantContext.requireFerreteriaId(), medio, idExcluido);
    }

    default int maxOrdenScoped() {
        return maxOrden(TenantContext.requireFerreteriaId());
    }

    default Long bloquearFerreteriaScoped() {
        return bloquearFerreteria(TenantContext.requireFerreteriaId());
    }

    default int sembrarScoped(String nombre, MedioPago medio, int orden, boolean activa) {
        return sembrar(TenantContext.requireFerreteriaId(), nombre, medio.name(), orden, activa);
    }
}
