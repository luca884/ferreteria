package com.ferreteria.clientes.repository;

import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClienteRepository extends TenantScopedRepository<Cliente, Long> {

    /**
     * Por razón social (contiene) o por documento (empieza con los dígitos). La collation de la tabla (_ai_ci) hace que
     * no distinga mayúsculas ni tildes. {@code patron} ya viene con los comodines y escapado (barra invertida, el escape
     * de LIKE en MySQL); {@code digitos} vacío no busca por documento.
     */
    @Query("select c from Cliente c where c.ferreteriaId = :fid and (:soloActivos = false or c.activo = true) "
            + "and (c.razonSocial like :patron or (:digitos <> '' and c.numeroDocumento like concat(:digitos, '%'))) "
            + "order by c.razonSocial, c.id")
    Page<Cliente> buscar(@Param("fid") Long ferreteriaId, @Param("soloActivos") boolean soloActivos,
            @Param("patron") String patron, @Param("digitos") String digitos, Pageable pageable);

    @Query("select c from Cliente c where c.ferreteriaId = :fid and (:soloActivos = false or c.activo = true) "
            + "order by c.razonSocial, c.id")
    Page<Cliente> todos(@Param("fid") Long ferreteriaId, @Param("soloActivos") boolean soloActivos, Pageable pageable);

    boolean existsByFerreteriaIdAndNumeroDocumento(Long ferreteriaId, String numeroDocumento);

    boolean existsByFerreteriaIdAndNumeroDocumentoAndIdNot(Long ferreteriaId, String numeroDocumento, Long id);

    default Page<Cliente> buscarScoped(String patron, String digitos, boolean soloActivos, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), soloActivos, patron, digitos, pageable);
    }

    default Page<Cliente> todosScoped(boolean soloActivos, Pageable pageable) {
        return todos(TenantContext.requireFerreteriaId(), soloActivos, pageable);
    }

    default boolean existeDocumentoScoped(String numeroDocumento) {
        return existsByFerreteriaIdAndNumeroDocumento(TenantContext.requireFerreteriaId(), numeroDocumento);
    }

    /** Para editar: busca otro cliente con ese documento, excluyendo el propio. */
    default boolean existeOtroConDocumentoScoped(String numeroDocumento, Long idPropio) {
        return existsByFerreteriaIdAndNumeroDocumentoAndIdNot(TenantContext.requireFerreteriaId(), numeroDocumento,
                idPropio);
    }
}
