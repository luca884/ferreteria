package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface ArticuloRepository extends TenantScopedRepository<Articulo, Long> {

    /**
     * Busqueda paginada. q ya viene como patron LIKE (con % y los comodines escapados con \); null
     * no filtra. La coleccion utf8mb4_0900_ai_ci hace que el LIKE no distinga mayusculas ni tildes.
     */
    @Query(value = """
            select a from Articulo a
            where a.ferreteriaId = :fid
              and (:incluirInactivos = true or a.activo = true)
              and (:q is null
                   or a.descripcion like :q escape '\\'
                   or a.codigoInterno like :q escape '\\'
                   or a.codigoBarras like :q escape '\\'
                   or a.codigoProveedor like :q escape '\\')
            """,
            countQuery = """
            select count(a) from Articulo a
            where a.ferreteriaId = :fid
              and (:incluirInactivos = true or a.activo = true)
              and (:q is null
                   or a.descripcion like :q escape '\\'
                   or a.codigoInterno like :q escape '\\'
                   or a.codigoBarras like :q escape '\\'
                   or a.codigoProveedor like :q escape '\\')
            """)
    Page<Articulo> buscar(
            @Param("fid") Long ferreteriaId,
            @Param("q") String q,
            @Param("incluirInactivos") boolean incluirInactivos,
            Pageable pageable);

    @Query("""
            select count(a) > 0 from Articulo a
            where a.ferreteriaId = :fid and a.codigoInterno = :codigo
              and (:idPropio is null or a.id <> :idPropio)
            """)
    boolean existeCodigoInterno(
            @Param("fid") Long ferreteriaId, @Param("codigo") String codigo, @Param("idPropio") Long idPropio);

    @Query("""
            select count(a) > 0 from Articulo a
            where a.ferreteriaId = :fid and a.codigoBarras = :codigo and a.activo = true
              and (:idPropio is null or a.id <> :idPropio)
            """)
    boolean existeCodigoBarrasActivo(
            @Param("fid") Long ferreteriaId, @Param("codigo") String codigo, @Param("idPropio") Long idPropio);

    @Query("""
            select count(a) > 0 from Articulo a
            where a.ferreteriaId = :fid and a.proveedorId = :proveedorId and a.codigoProveedor = :codigo
              and (:idPropio is null or a.id <> :idPropio)
            """)
    boolean existeCodigoProveedor(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("codigo") String codigo,
            @Param("idPropio") Long idPropio);

    List<Articulo> findAllByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrue(
            Long ferreteriaId, Long rubroId);

    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.proveedorId = :proveedorId")
    List<Articulo> deProveedor(@Param("fid") Long ferreteriaId, @Param("proveedorId") Long proveedorId);

    @Query("select a.codigoInterno from Articulo a where a.ferreteriaId = :fid and a.codigoInterno in :codigos")
    List<String> codigosInternosEn(@Param("fid") Long ferreteriaId, @Param("codigos") Collection<String> codigos);

    /** Tamano de lote del IN de codigosInternosOcupadosScoped. */
    int LOTE_CODIGOS_INTERNOS = 1000;

    /** UNA consulta: todos los articulos del proveedor, activos e inactivos (importacion de listas). */
    default List<Articulo> deProveedorScoped(Long proveedorId) {
        return deProveedor(TenantContext.requireFerreteriaId(), proveedorId);
    }

    /**
     * Codigos internos de la ferreteria que ya existen (activos o no), tal como estan guardados. La
     * coleccion ai_ci hace el IN insensible a mayusculas y acentos. Parte la lista en lotes de 1.000.
     */
    default List<String> codigosInternosOcupadosScoped(Collection<String> codigos) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        List<String> pedidos = List.copyOf(codigos);
        List<String> ocupados = new ArrayList<>();
        for (int desde = 0; desde < pedidos.size(); desde += LOTE_CODIGOS_INTERNOS) {
            int hasta = Math.min(desde + LOTE_CODIGOS_INTERNOS, pedidos.size());
            ocupados.addAll(codigosInternosEn(ferreteriaId, pedidos.subList(desde, hasta)));
        }
        return ocupados;
    }

    default Page<Articulo> buscarScoped(String q, boolean incluirInactivos, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), q, incluirInactivos, pageable);
    }

    /** Incluye los dados de baja. idPropio excluye al articulo que se esta editando (null en el alta). */
    default boolean existeCodigoInternoScoped(String codigo, Long idPropio) {
        return existeCodigoInterno(TenantContext.requireFerreteriaId(), codigo, idPropio);
    }

    /** Solo cuenta los activos: un articulo dado de baja no bloquea el codigo. */
    default boolean existeCodigoBarrasActivoScoped(String codigo, Long idPropio) {
        return existeCodigoBarrasActivo(TenantContext.requireFerreteriaId(), codigo, idPropio);
    }

    default boolean existeCodigoProveedorScoped(Long proveedorId, String codigo, Long idPropio) {
        return existeCodigoProveedor(TenantContext.requireFerreteriaId(), proveedorId, codigo, idPropio);
    }

    /** Articulos activos del rubro sin margen propio: los que heredan su margen (cascada de CU-03). */
    default List<Articulo> heredanMargenDeRubroScoped(Long rubroId) {
        return findAllByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrue(
                TenantContext.requireFerreteriaId(), rubroId);
    }
}
