package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.Caja;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Las cajas de la ferreteria del request (CU-10). Las lecturas con lock ordenan la carrera entre vender, cargar un
 * movimiento y cerrar: la venta y el movimiento toman la caja compartida (FOR SHARE) y el cierre exclusiva (FOR UPDATE),
 * asi una venta en curso termina antes del cierre y una que llega despues ya ve la caja cerrada.
 */
public interface CajaRepository extends TenantScopedRepository<Caja, Long> {

    @Query("select c from Caja c where c.ferreteriaId = :fid and c.abierta = true")
    Optional<Caja> abierta(@Param("fid") Long ferreteriaId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Caja c where c.ferreteriaId = :fid and c.abierta = true")
    Optional<Caja> abiertaCompartida(@Param("fid") Long ferreteriaId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Caja c where c.id = :id and c.ferreteriaId = :fid")
    Optional<Caja> porIdCompartida(@Param("id") Long id, @Param("fid") Long ferreteriaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Caja c where c.id = :id and c.ferreteriaId = :fid")
    Optional<Caja> porIdExclusiva(@Param("id") Long id, @Param("fid") Long ferreteriaId);

    /**
     * La caja ABIERTA con lock exclusivo, tomada por el MISMO camino de índice que {@link #abiertaCompartida} (la que toman vender,
     * cobrar y anular): primero el registro de {@code uk_caja_abierta (ferreteria_id, abierta)} y recién después el de la clave
     * primaria. {@link #porIdCompartida} (la del movimiento de caja) entra solo por la PK y no forma ciclo con el cierre. Es lo que evita el deadlock entre el cierre y las operaciones que toman la caja en S
     * (vender, cobrar, anular): el cierre que entraba por la PK tenía la fila en X y, al poner {@code abierta = NULL}, pedía el
     * registro de {@code uk_caja_abierta} que un lector compartido ya tenía en S mientras esperaba la PK (ciclo). {@code force
     * index} fija el camino (con {@code id = :id} el optimizador elegiría la PK). Vacía si la caja no está abierta: el que llama
     * recurre a {@link #porIdExclusiva} para distinguir «ya cerrada» de «no existe».
     */
    @Query(value = "select * from caja force index (uk_caja_abierta) where ferreteria_id = :fid and abierta = 1 and id = :id for update",
            nativeQuery = true)
    Optional<Caja> abiertaPorIdExclusiva(@Param("id") Long id, @Param("fid") Long ferreteriaId);

    @Query("select c from Caja c where c.ferreteriaId = :fid and c.abierta is null order by c.fechaCierre desc, c.id desc")
    Page<Caja> cerradas(@Param("fid") Long ferreteriaId, Pageable pageable);

    @Query("select c.efectivoContado from Caja c where c.ferreteriaId = :fid and c.abierta is null "
            + "order by c.fechaCierre desc, c.id desc")
    List<BigDecimal> contados(@Param("fid") Long ferreteriaId, Pageable pageable);

    @Query("select u.id, u.nombre from Usuario u where u.ferreteriaId = :fid and u.id in :ids")
    List<Object[]> nombres(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /** La caja abierta de la ferreteria, sea de hoy o de otro dia. */
    default Optional<Caja> abiertaScoped() {
        return abierta(TenantContext.requireFerreteriaId());
    }

    /** La caja abierta con lock compartido: la toma la venta antes de numerar. */
    default Optional<Caja> abiertaParaVenderScoped() {
        return abiertaCompartida(TenantContext.requireFerreteriaId());
    }

    /** La caja con lock compartido, para cargarle un movimiento. La de otra ferreteria no aparece. */
    default Optional<Caja> paraMovimientoScoped(Long id) {
        return porIdCompartida(id, TenantContext.requireFerreteriaId());
    }

    /** La caja con lock exclusivo, para cerrarla. La de otra ferreteria no aparece. */
    default Optional<Caja> paraCerrarScoped(Long id) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        // Abierta: por uk_caja_abierta y después la PK, igual que los lectores compartidos (sin ciclo con ellos). Si no está
        // abierta (ya cerrada o inexistente) no hay quien la comparta: se la toma por la PK para distinguir un caso del otro.
        Optional<Caja> abierta = abiertaPorIdExclusiva(id, ferreteriaId);
        return abierta.isPresent() ? abierta : porIdExclusiva(id, ferreteriaId);
    }

    /**
     * La caja con lock exclusivo, para un gasto o retiro: dos egresos no leen a la vez el mismo efectivo esperado (GDS-36).
     * Mismo camino que el cierre (por uk_caja_abierta y despues la PK), sin ciclo con los lectores compartidos.
     */
    default Optional<Caja> paraEgresoScoped(Long id) {
        return paraCerrarScoped(id);
    }

    /** Las cerradas, mas nuevas primero. */
    default Page<Caja> cerradasScoped(Pageable pageable) {
        return cerradas(TenantContext.requireFerreteriaId(), pageable);
    }

    /** El efectivo contado del ultimo cierre: lo que se propone al abrir. Vacio si nunca se cerro una caja. */
    default Optional<BigDecimal> ultimoContadoScoped() {
        return contados(TenantContext.requireFerreteriaId(), PageRequest.of(0, 1)).stream().findFirst();
    }

    /** El nombre de cada usuario de la ferreteria del request, por id (los de otra ferreteria no vienen). */
    default Map<Long, String> nombresScoped(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return nombres(TenantContext.requireFerreteriaId(), ids).stream()
                .collect(Collectors.toMap(fila -> (Long) fila[0], fila -> (String) fila[1]));
    }
}
