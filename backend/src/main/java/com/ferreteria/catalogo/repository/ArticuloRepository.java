package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ConsultaMostrador;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ArticuloRepository extends TenantScopedRepository<Articulo, Long> {

    /**
     * Unica definicion de "que articulos cumplen el filtro". proveedorId y rubroId son opcionales (null
     * no filtra). q ya viene como patron LIKE (con % y los comodines escapados con \); null no filtra.
     * La coleccion utf8mb4_0900_ai_ci hace que el LIKE no distinga mayusculas ni tildes.
     */
    String FILTRO_ARTICULOS = """
            from Articulo a
            where a.ferreteriaId = :fid
              and (:incluirInactivos = true or a.activo = true)
              and (:proveedorId is null or a.proveedorId = :proveedorId)
              and (:rubroId is null or a.rubroId = :rubroId)
              and (:q is null
                   or a.descripcion like :q escape '\\'
                   or a.codigoInterno like :q escape '\\'
                   or a.codigoBarras like :q escape '\\'
                   or a.codigoProveedor like :q escape '\\')
            """;

    @Query(value = "select a " + FILTRO_ARTICULOS, countQuery = "select count(a) " + FILTRO_ARTICULOS)
    Page<Articulo> buscar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("q") String q,
            @Param("incluirInactivos") boolean incluirInactivos,
            Pageable pageable);

    /** Los articulos del filtro (actualizacion masiva), todos y en orden ascendente de id. */
    @Query("select a " + FILTRO_ARTICULOS + " order by a.id")
    List<Articulo> filtrar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("q") String q,
            @Param("incluirInactivos") boolean incluirInactivos);

    @Query("select count(a) " + FILTRO_ARTICULOS)
    long contar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("q") String q,
            @Param("incluirInactivos") boolean incluirInactivos);

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

    long countByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrue(Long ferreteriaId, Long rubroId);

    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.proveedorId = :proveedorId")
    List<Articulo> deProveedor(@Param("fid") Long ferreteriaId, @Param("proveedorId") Long proveedorId);

    @Query("select a.codigoInterno from Articulo a where a.ferreteriaId = :fid and a.codigoInterno in :codigos")
    List<String> codigosInternosEn(@Param("fid") Long ferreteriaId, @Param("codigos") Collection<String> codigos);

    /**
     * Mostrador (CU-06): los activos cuyo codigo de barras o codigo interno es exactamente codigo. La
     * coleccion ai_ci hace la igualdad insensible a mayusculas y tildes. El de proveedor no cuenta.
     */
    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.activo = true "
            + "and (a.codigoBarras = :codigo or a.codigoInterno = :codigo) order by a.descripcion, a.id")
    List<Articulo> exactosActivos(@Param("fid") Long ferreteriaId, @Param("codigo") String codigo, Pageable limite);

    /** Mostrador (CU-06): true si un articulo dado de baja tiene exactamente ese codigo de barras o interno. */
    @Query("select count(a) > 0 from Articulo a where a.ferreteriaId = :fid and a.activo = false "
            + "and (a.codigoBarras = :codigo or a.codigoInterno = :codigo)")
    boolean hayDadoDeBajaConCodigo(@Param("fid") Long ferreteriaId, @Param("codigo") String codigo);

    /**
     * Mostrador (CU-06): los activos que contienen TODAS las palabras (p1 a p5, ya como patron LIKE; null
     * es un slot sin usar y no filtra), cada una en la descripcion o en cualquiera de los tres codigos.
     * Los cinco bloques van escritos a mano para que la consulta siga siendo una constante.
     */
    @Query("""
            select a from Articulo a
            where a.ferreteriaId = :fid and a.activo = true
              and (:p1 is null or a.descripcion like :p1 escape '\\' or a.codigoInterno like :p1 escape '\\'
                   or a.codigoBarras like :p1 escape '\\' or a.codigoProveedor like :p1 escape '\\')
              and (:p2 is null or a.descripcion like :p2 escape '\\' or a.codigoInterno like :p2 escape '\\'
                   or a.codigoBarras like :p2 escape '\\' or a.codigoProveedor like :p2 escape '\\')
              and (:p3 is null or a.descripcion like :p3 escape '\\' or a.codigoInterno like :p3 escape '\\'
                   or a.codigoBarras like :p3 escape '\\' or a.codigoProveedor like :p3 escape '\\')
              and (:p4 is null or a.descripcion like :p4 escape '\\' or a.codigoInterno like :p4 escape '\\'
                   or a.codigoBarras like :p4 escape '\\' or a.codigoProveedor like :p4 escape '\\')
              and (:p5 is null or a.descripcion like :p5 escape '\\' or a.codigoInterno like :p5 escape '\\'
                   or a.codigoBarras like :p5 escape '\\' or a.codigoProveedor like :p5 escape '\\')
            order by a.descripcion, a.id
            """)
    List<Articulo> coincidenciasMostrador(
            @Param("fid") Long ferreteriaId,
            @Param("p1") String p1,
            @Param("p2") String p2,
            @Param("p3") String p3,
            @Param("p4") String p4,
            @Param("p5") String p5,
            Pageable limite);

    /**
     * Ventas (CU-07): suma delta al stock con un UPDATE relativo y atomico, acotado a la ferreteria. Es
     * nativo a proposito: stock_actual es updatable=false en la entidad (ningun guardado de un articulo
     * viejo lo pisa), y asi no depende de como Hibernate trate ese atributo en un UPDATE de HQL. Devuelve
     * las filas afectadas: 0 si el id no existe o es de otra ferreteria. Exige una transaccion abierta.
     */
    @Modifying
    @Query(value = "update articulo set stock_actual = stock_actual + :delta where id = :id and ferreteria_id = :fid",
            nativeQuery = true)
    int sumarStock(@Param("fid") Long ferreteriaId, @Param("id") Long id, @Param("delta") BigDecimal delta);

    /** Los articulos de la ferreteria con esos ids, activos o no, por id ascendente. Los ajenos no vienen. */
    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.id in :ids order by a.id")
    List<Articulo> porIds(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /** Pares (id, stock actual) leidos de la base, sin pasar por el contexto de persistencia. */
    @Query("select a.id, a.stockActual from Articulo a where a.ferreteriaId = :fid and a.id in :ids")
    List<Object[]> stockDe(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /**
     * Stock del articulo tal como esta confirmado HOY en la base. A diferencia de {@code stockDe}, es una
     * lectura con lock ({@code FOR UPDATE}): una lectura comun de MySQL (REPEATABLE READ) devuelve la foto
     * que tomo la transaccion en su primera lectura, o sea el stock viejo si una venta confirmo en el medio.
     * Exige una transaccion abierta. Vacio si el id no existe o es de otra ferreteria.
     */
    @Query(value = "select stock_actual from articulo where id = :id and ferreteria_id = :fid for update",
            nativeQuery = true)
    Optional<BigDecimal> stockVigente(@Param("fid") Long ferreteriaId, @Param("id") Long id);

    /** Todos los articulos en dolares de la ferreteria, activos o no, por id (recalculo por cotizacion). */
    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.moneda = com.ferreteria.catalogo.model.Moneda.USD "
            + "order by a.id")
    List<Articulo> enDolares(@Param("fid") Long ferreteriaId);

    /** Tamano de lote del IN de codigosInternosOcupadosScoped. */
    int LOTE_CODIGOS_INTERNOS = 1000;

    /** Tamano de lote del IN de codigosBarrasActivosOcupadosScoped. */
    int LOTE_CODIGOS_DE_BARRAS = 1000;

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

    /**
     * Codigos de barras de la ferreteria que ya usa un articulo ACTIVO, tal como estan guardados (como en el alta
     * manual, uno inactivo no ocupa el codigo). La coleccion ai_ci hace el IN insensible a mayusculas. Parte la
     * lista en lotes de 1.000.
     */
    default List<String> codigosBarrasActivosOcupadosScoped(Collection<String> codigos) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        List<String> pedidos = List.copyOf(codigos);
        List<String> ocupados = new ArrayList<>();
        for (int desde = 0; desde < pedidos.size(); desde += LOTE_CODIGOS_DE_BARRAS) {
            int hasta = Math.min(desde + LOTE_CODIGOS_DE_BARRAS, pedidos.size());
            ocupados.addAll(codigosBarrasActivosEn(ferreteriaId, pedidos.subList(desde, hasta)));
        }
        return ocupados;
    }

    @Query("""
            select a.codigoBarras from Articulo a
            where a.ferreteriaId = :fid and a.activo = true and a.codigoBarras in :codigos
            """)
    List<String> codigosBarrasActivosEn(@Param("fid") Long ferreteriaId, @Param("codigos") List<String> codigos);

    default Page<Articulo> buscarScoped(String q, boolean incluirInactivos, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), null, null, q, incluirInactivos, pageable);
    }

    /** Los articulos de la ferreteria que cumplen el filtro, por id ascendente (CU-05). */
    default List<Articulo> filtrarScoped(FiltroArticulos filtro) {
        return filtrar(TenantContext.requireFerreteriaId(), filtro.proveedorId(), filtro.rubroId(),
                filtro.patron(), filtro.incluirInactivos());
    }

    /** Cuantos articulos de la ferreteria cumplen el filtro, sin cargarlos (CU-05). */
    default long contarScoped(FiltroArticulos filtro) {
        return contar(TenantContext.requireFerreteriaId(), filtro.proveedorId(), filtro.rubroId(),
                filtro.patron(), filtro.incluirInactivos());
    }

    /** Hasta limite activos con ese codigo exacto, por descripcion y luego id (CU-06). */
    default List<Articulo> exactosActivosScoped(String codigo, int limite) {
        return exactosActivos(TenantContext.requireFerreteriaId(), codigo, PageRequest.of(0, limite));
    }

    /** True si la ferreteria tiene un articulo dado de baja con ese codigo exacto (CU-06). */
    default boolean hayDadoDeBajaConCodigoScoped(String codigo) {
        return hayDadoDeBajaConCodigo(TenantContext.requireFerreteriaId(), codigo);
    }

    /** Hasta limite activos que contienen todas las palabras de la consulta, por descripcion y luego id (CU-06). */
    default List<Articulo> coincidenciasMostradorScoped(ConsultaMostrador consulta, int limite) {
        return coincidenciasMostrador(TenantContext.requireFerreteriaId(),
                consulta.patron(0), consulta.patron(1), consulta.patron(2), consulta.patron(3), consulta.patron(4),
                PageRequest.of(0, limite));
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

    /**
     * Los articulos en dolares de la ferreteria, activos e inactivos: el recalculo por cotizacion los incluye a todos
     * para que al reactivarse no se vendan a un precio viejo (la cascada de rubro, en cambio, solo toca los activos).
     */
    default List<Articulo> enDolaresScoped() {
        return enDolares(TenantContext.requireFerreteriaId());
    }

    /** Cuantos articulos heredan el margen del rubro, sin cargarlos (aviso antes de cambiar el margen). */
    default long contarHeredanMargenDeRubroScoped(Long rubroId) {
        return countByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrue(
                TenantContext.requireFerreteriaId(), rubroId);
    }

    /** Suma delta al stock del articulo de la ferreteria del request (atomico). Devuelve las filas afectadas. */
    default int sumarStockScoped(Long id, BigDecimal delta) {
        return sumarStock(TenantContext.requireFerreteriaId(), id, delta);
    }

    /** Los articulos de la ferreteria con esos ids (activos o no), por id ascendente. */
    default List<Articulo> porIdsScoped(Collection<Long> ids) {
        return porIds(TenantContext.requireFerreteriaId(), ids);
    }

    /** Stock confirmado hoy en la base (lectura con lock) del articulo de la ferreteria del request. */
    default Optional<BigDecimal> stockVigenteScoped(Long id) {
        return stockVigente(TenantContext.requireFerreteriaId(), id);
    }

    /** Stock actual de cada id de la ferreteria que existe; los ids ajenos o inexistentes no figuran. */
    default Map<Long, BigDecimal> stockDeScoped(Collection<Long> ids) {
        Map<Long, BigDecimal> stock = new HashMap<>();
        for (Object[] fila : stockDe(TenantContext.requireFerreteriaId(), ids)) {
            stock.put((Long) fila[0], (BigDecimal) fila[1]);
        }
        return stock;
    }
}
