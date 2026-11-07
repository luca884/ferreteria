package com.ferreteria.catalogo.repository;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloBajoMinimo;
import com.ferreteria.catalogo.model.ArticuloEnControl;
import com.ferreteria.catalogo.model.ControlDeDatos;
import com.ferreteria.catalogo.model.ConsultaMostrador;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.OrdenBajoMinimo;
import com.ferreteria.catalogo.model.RecargoCosto;
import com.ferreteria.catalogo.model.RecargosDeCosto;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
              and (:importacionId is null or a.ultimaImportacionId = :importacionId)
              and (:q is null
                   or a.descripcion like :q escape '\\'
                   or a.codigoInterno like :q escape '\\'
                   or a.codigoBarras like :q escape '\\'
                   or a.codigoProveedor like :q escape '\\')
            """;

    /**
     * Con busqueda, primero los que empiezan con lo buscado en la descripcion o el codigo interno ({@code prefijo}, el
     * patron {@code q%}; null no prioriza a ninguno). En el listado, el Sort de la pagina (descripcion e id) va despues.
     */
    String PRIMERO_LOS_QUE_EMPIEZAN = """
             order by case when a.descripcion like :prefijo escape '\\' or a.codigoInterno like :prefijo escape '\\'
                           then 0 else 1 end
            """;

    @Query(value = "select a " + FILTRO_ARTICULOS + PRIMERO_LOS_QUE_EMPIEZAN,
            countQuery = "select count(a) " + FILTRO_ARTICULOS)
    Page<Articulo> buscar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
            @Param("q") String q,
            @Param("prefijo") String prefijo,
            @Param("incluirInactivos") boolean incluirInactivos,
            Pageable pageable);

    /**
     * Ofertas (S3): los del listado con una oferta vigente ese dia que baja el precio normal, la misma regla que
     * {@link com.ferreteria.catalogo.model.Oferta#precioSobre}. En el %, el precio de oferta al centavo hacia arriba es
     * {@code ceiling(precio x (100 - %)) / 100}; como {@code precio x 100} es entero, eso baja el precio si
     * {@code precio x (100 - %) <= precio x 100 - 1} (y es mayor a 0 si el precio lo es). Sin {@code ceiling}, que el
     * parser de JPQL de Spring Data no acepta.
     */
    String FILTRO_EN_OFERTA = FILTRO_ARTICULOS + """
              and a.ofertaTipo is not null
              and (a.ofertaHasta is null or a.ofertaHasta >= :hoy)
              and ((a.ofertaTipo = com.ferreteria.catalogo.model.TipoOferta.PRECIO_FIJO
                    and a.ofertaValor < a.precioFinal)
                   or (a.ofertaTipo = com.ferreteria.catalogo.model.TipoOferta.DESCUENTO_MONTO
                    and a.ofertaValor < a.precioFinal)
                   or (a.ofertaTipo = com.ferreteria.catalogo.model.TipoOferta.DESCUENTO_PORCENTAJE
                    and a.precioFinal > 0
                    and a.precioFinal * (100 - a.ofertaValor) <= a.precioFinal * 100 - 1))
            """;

    @Query(value = "select a " + FILTRO_EN_OFERTA + PRIMERO_LOS_QUE_EMPIEZAN,
            countQuery = "select count(a) " + FILTRO_EN_OFERTA)
    Page<Articulo> buscarEnOferta(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
            @Param("q") String q,
            @Param("prefijo") String prefijo,
            @Param("incluirInactivos") boolean incluirInactivos,
            @Param("hoy") LocalDate hoy,
            Pageable pageable);

    /** Los articulos del filtro (actualizacion masiva), todos y en orden ascendente de id. */
    @Query("select a " + FILTRO_ARTICULOS + " order by a.id")
    List<Articulo> filtrar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
            @Param("q") String q,
            @Param("incluirInactivos") boolean incluirInactivos);

    /** El orden del listado sin paginar: los que empiezan con lo buscado, descripcion e id (GDS-19). */
    String ORDEN_DEL_LISTADO = PRIMERO_LOS_QUE_EMPIEZAN + ", a.descripcion, a.id";

    /** Los articulos del filtro, todos y en el orden del listado: los que empiezan con lo buscado, descripcion e id (GDS-19). */
    @Query("select a " + FILTRO_ARTICULOS + ORDEN_DEL_LISTADO)
    List<Articulo> paraExportar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
            @Param("q") String q,
            @Param("prefijo") String prefijo,
            @Param("incluirInactivos") boolean incluirInactivos);

    /** Los de {@link #paraExportar} con una oferta vigente ese dia que baja el precio normal ({@link #FILTRO_EN_OFERTA}). */
    @Query("select a " + FILTRO_EN_OFERTA + ORDEN_DEL_LISTADO)
    List<Articulo> paraExportarEnOferta(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
            @Param("q") String q,
            @Param("prefijo") String prefijo,
            @Param("incluirInactivos") boolean incluirInactivos,
            @Param("hoy") LocalDate hoy);

    @Query("select count(a) " + FILTRO_ARTICULOS)
    long contar(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("rubroId") Long rubroId,
            @Param("importacionId") Long importacionId,
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

    List<Articulo> findAllByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrueOrderByDescripcionAscIdAsc(
            Long ferreteriaId, Long rubroId);

    List<Articulo> findAllByFerreteriaIdAndRubroIdIsNullAndMargenPorcentajeIsNullAndActivoTrueAndPrecioManualFalseOrderById(
            Long ferreteriaId);

    long countByFerreteriaIdAndRubroIdIsNullAndMargenPorcentajeIsNullAndActivoTrueAndPrecioManualFalse(Long ferreteriaId);

    List<Articulo> findAllByFerreteriaIdAndActivoTrueAndPrecioManualFalseOrderById(Long ferreteriaId);

    long countByFerreteriaIdAndActivoTrueAndPrecioManualFalse(Long ferreteriaId);

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

    /**
     * Mostrador (CU-06): los articulos que contienen TODAS las palabras (p1 a p5, ya como patron LIKE; null
     * es un slot sin usar y no filtra), cada una en la descripcion o en cualquiera de los tres codigos. Incluye los
     * dados de baja, despues de los activos: se ven en la busqueda y el mostrador avisa al querer agregarlos.
     * Los cinco bloques van escritos a mano para que la consulta siga siendo una constante.
     */
    @Query("""
            select a from Articulo a
            where a.ferreteriaId = :fid
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
            order by a.activo desc, a.descripcion, a.id
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

    /** Pares (id del articulo, nombre del rubro) de los articulos con rubro. */
    @Query("select a.id, r.nombre from Articulo a, Rubro r where r.id = a.rubroId and r.ferreteriaId = a.ferreteriaId "
            + "and a.ferreteriaId = :fid and a.id in :ids")
    List<Object[]> rubrosDe(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

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

    /**
     * El articulo de la ferreteria leido con lock de escritura ({@code FOR UPDATE}), para editarlo sin perder lo que
     * una importacion confirme en el medio: una lectura comun toma la foto de la primera lectura de la transaccion y
     * el flush reescribiria costo, base y ajustes con valores viejos. Exige una transaccion abierta y que el
     * articulo no se haya leido antes en ella. Vacio si el id no existe o es de otra ferreteria.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.id = :id")
    Optional<Articulo> findParaEditar(@Param("fid") Long ferreteriaId, @Param("id") Long id);

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

    /** {@code q} y {@code prefijo} ya vienen como patrones ({@link FiltroArticulos}); el Sort de la pagina va despues del prefijo. */
    default Page<Articulo> buscarScoped(String q, String prefijo, boolean incluirInactivos, Pageable pageable) {
        return buscar(TenantContext.requireFerreteriaId(), null, null, null, q, prefijo, incluirInactivos, pageable);
    }

    default Page<Articulo> buscarEnOfertaScoped(String q, String prefijo, boolean incluirInactivos, LocalDate hoy,
                                                Pageable pageable) {
        return buscarEnOferta(TenantContext.requireFerreteriaId(), null, null, null, q, prefijo, incluirInactivos, hoy,
                pageable);
    }

    /** Los articulos de la ferreteria que cumplen el filtro, por id ascendente (CU-05). */
    default List<Articulo> filtrarScoped(FiltroArticulos filtro) {
        return filtrar(TenantContext.requireFerreteriaId(), filtro.proveedorId(), filtro.rubroId(),
                filtro.importacionId(), filtro.patron(), filtro.incluirInactivos());
    }

    /** Los articulos de la ferreteria que muestra el listado con esa busqueda, todos y en su orden (GDS-19). */
    default List<Articulo> paraExportarScoped(String q, boolean incluirInactivos) {
        return paraExportar(TenantContext.requireFerreteriaId(), null, null, null,
                FiltroArticulos.patronDeBusqueda(q), FiltroArticulos.prefijoDeBusqueda(q), incluirInactivos);
    }

    /** Los de {@link #paraExportarScoped} que tienen hoy una oferta que baja el precio: el listado con «Solo en oferta». */
    default List<Articulo> paraExportarEnOfertaScoped(String q, boolean incluirInactivos, LocalDate hoy) {
        return paraExportarEnOferta(TenantContext.requireFerreteriaId(), null, null, null,
                FiltroArticulos.patronDeBusqueda(q), FiltroArticulos.prefijoDeBusqueda(q), incluirInactivos, hoy);
    }

    /** Cuantos articulos de la ferreteria cumplen el filtro, sin cargarlos (CU-05). */
    default long contarScoped(FiltroArticulos filtro) {
        return contar(TenantContext.requireFerreteriaId(), filtro.proveedorId(), filtro.rubroId(),
                filtro.importacionId(), filtro.patron(), filtro.incluirInactivos());
    }

    /** Hasta limite activos con ese codigo exacto, por descripcion y luego id (CU-06). */
    default List<Articulo> exactosActivosScoped(String codigo, int limite) {
        return exactosActivos(TenantContext.requireFerreteriaId(), codigo, PageRequest.of(0, limite));
    }

    /** Hasta limite articulos con todas las palabras de la consulta: activos primero, despues por descripcion e id (CU-06). */
    default List<Articulo> coincidenciasMostradorScoped(ConsultaMostrador consulta, int limite) {
        return coincidenciasMostrador(TenantContext.requireFerreteriaId(),
                consulta.patron(0), consulta.patron(1), consulta.patron(2), consulta.patron(3), consulta.patron(4),
                PageRequest.of(0, limite));
    }

    /**
     * Codigos internos de los articulos cargados a mano, del mas nuevo al mas viejo (incluye los dados de baja). Un
     * articulo cuyo codigo interno es igual al del proveedor cuenta como importado: la importacion los crea asi.
     */
    @Query("""
            select a.codigoInterno from Articulo a
            where a.ferreteriaId = :fid and (a.codigoProveedor is null or a.codigoProveedor <> a.codigoInterno)
            order by a.id desc
            """)
    List<String> codigosCargadosAMano(@Param("fid") Long ferreteriaId, Pageable pagina);

    /** El codigo interno del ultimo articulo cargado a mano de la ferreteria del request; vacio si no hay. */
    default Optional<String> ultimoCodigoCargadoAManoScoped() {
        return codigosCargadosAMano(TenantContext.requireFerreteriaId(), PageRequest.of(0, 1)).stream().findFirst();
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
     * Los mismos herederos que {@link #heredanMargenDeRubroScoped} (tambien los de precio manual), por descripcion y
     * despues por alta: la vista previa del margen nuevo los lista en ese orden. La coleccion de la base ordena sin
     * distinguir mayusculas ni tildes.
     */
    default List<Articulo> heredanMargenDeRubroPorDescripcionScoped(Long rubroId) {
        return findAllByFerreteriaIdAndRubroIdAndMargenPorcentajeIsNullAndActivoTrueOrderByDescripcionAscIdAsc(
                TenantContext.requireFerreteriaId(), rubroId);
    }

    /**
     * Los articulos en dolares de la ferreteria, activos e inactivos: el recalculo por cotizacion los incluye a todos
     * para que al reactivarse no se vendan a un precio viejo (la cascada de rubro, en cambio, solo toca los activos).
     */
    default List<Articulo> enDolaresScoped() {
        return enDolares(TenantContext.requireFerreteriaId());
    }

    /**
     * Articulos activos sin precio manual, sin rubro y sin margen propio: los que usan el margen por defecto de la
     * ferreteria y cambian de precio si cambia (el margen del rubro nunca es null).
     */
    default List<Articulo> heredanMargenPorDefectoScoped() {
        return findAllByFerreteriaIdAndRubroIdIsNullAndMargenPorcentajeIsNullAndActivoTrueAndPrecioManualFalseOrderById(
                TenantContext.requireFerreteriaId());
    }

    default long contarHeredanMargenPorDefectoScoped() {
        return countByFerreteriaIdAndRubroIdIsNullAndMargenPorcentajeIsNullAndActivoTrueAndPrecioManualFalse(
                TenantContext.requireFerreteriaId());
    }

    /** Articulos activos sin precio manual: los que cambian de precio si cambia el multiplo de redondeo. */
    default List<Articulo> activosConPrecioCalculadoScoped() {
        return findAllByFerreteriaIdAndActivoTrueAndPrecioManualFalseOrderById(TenantContext.requireFerreteriaId());
    }

    default long contarActivosConPrecioCalculadoScoped() {
        return countByFerreteriaIdAndActivoTrueAndPrecioManualFalse(TenantContext.requireFerreteriaId());
    }

    /** Suma delta al stock del articulo de la ferreteria del request (atomico). Devuelve las filas afectadas. */
    default int sumarStockScoped(Long id, BigDecimal delta) {
        return sumarStock(TenantContext.requireFerreteriaId(), id, delta);
    }

    /** El nombre del rubro de cada articulo de la ferreteria del request que tiene rubro, por id. */
    default Map<Long, String> rubrosDeScoped(Collection<Long> ids) {
        Map<Long, String> rubros = new HashMap<>();
        if (!ids.isEmpty()) {
            rubrosDe(TenantContext.requireFerreteriaId(), ids).forEach(f -> rubros.put((Long) f[0], (String) f[1]));
        }
        return rubros;
    }

    /** Los articulos activos del proveedor con alguno de esos codigos de proveedor (CU-13, lectura de la factura). */
    @Query("select a from Articulo a where a.ferreteriaId = :fid and a.proveedorId = :proveedorId "
            + "and a.activo = true and a.codigoProveedor in :codigos order by a.id")
    List<Articulo> activosDeProveedorPorCodigo(
            @Param("fid") Long ferreteriaId,
            @Param("proveedorId") Long proveedorId,
            @Param("codigos") Collection<String> codigos);

    default List<Articulo> activosDeProveedorPorCodigoScoped(Long proveedorId, Collection<String> codigos) {
        return activosDeProveedorPorCodigo(TenantContext.requireFerreteriaId(), proveedorId, codigos);
    }

    /** Los articulos de la ferreteria con esos ids (activos o no), por id ascendente. */
    default List<Articulo> porIdsScoped(Collection<Long> ids) {
        return porIds(TenantContext.requireFerreteriaId(), ids);
    }

    /** El articulo de la ferreteria del request con lock de escritura; ver {@link #findParaEditar}. */
    default Optional<Articulo> findParaEditarScoped(Long id) {
        return findParaEditar(TenantContext.requireFerreteriaId(), id);
    }

    /**
     * Los articulos activos con stock menor al minimo (y minimo cargado, mayor a cero), en el orden pedido: DESCRIPCION,
     * CODIGO, RUBRO o PROVEEDOR. Sin rubro o sin proveedor van al final; el desempate es la descripcion. Rubro y
     * proveedor se unen por la ferreteria del articulo. Filas (id, codigo, descripcion, rubro, proveedor, costo, moneda,
     * unidad, stock, minimo, codigo del proveedor).
     */
    @Query(nativeQuery = true, value = """
            select a.id, a.codigo_interno, a.descripcion, r.nombre, p.nombre, a.costo, a.moneda, a.unidad,
                   a.stock_actual, a.stock_minimo, a.codigo_proveedor
            from articulo a
            left join rubro r on r.id = a.rubro_id and r.ferreteria_id = a.ferreteria_id
            left join proveedor p on p.id = a.proveedor_id and p.ferreteria_id = a.ferreteria_id
            where a.ferreteria_id = :fid and a.activo and a.stock_minimo > 0 and a.stock_actual < a.stock_minimo
            order by case when :orden = 'RUBRO' then r.nombre is null else 0 end,
                     case when :orden = 'RUBRO' then r.nombre end,
                     case when :orden = 'PROVEEDOR' then p.nombre is null else 0 end,
                     case when :orden = 'PROVEEDOR' then p.nombre end,
                     case when :orden = 'CODIGO' then a.codigo_interno end,
                     a.descripcion, a.id
            """)
    List<Object[]> bajoMinimo(@Param("fid") Long ferreteriaId, @Param("orden") String orden);

    /** Los articulos de la ferreteria del request bajo el stock minimo, en ese orden (CU-09, G72). */
    default List<ArticuloBajoMinimo> bajoMinimoScoped(OrdenBajoMinimo orden) {
        return bajoMinimo(TenantContext.requireFerreteriaId(), orden.name()).stream()
                .map(f -> ArticuloBajoMinimo.de((Long) f[0], (String) f[1], (String) f[10], (String) f[2],
                        (String) f[3], (String) f[4], (BigDecimal) f[5], (String) f[6], (String) f[7],
                        (BigDecimal) f[8], (BigDecimal) f[9]))
                .toList();
    }

    /**
     * Los articulos activos que caen en un control de datos (GDS-18), por descripcion. Sin rubro es sin un rubro propio
     * (uno de otra ferreteria cuenta como ninguno). Margen negativo trae los candidatos y la decision final es de
     * {@code MargenNegativo} (con el costo real), en el servicio: los que dan costo x cotizacion x (100 + IVA) mayor que
     * precio x 100 con el costo base, y todos los que tienen recargos, porque un recargo cambia el costo para los dos
     * lados. La regla redondeada no avisa en ningun caso en que esta cuenta exacta no avise, asi que no se pierde
     * ninguno. El porcentaje de cada alicuota repite el de {@code AlicuotaIva} (lo cuida ControlesDeDatosIntegrationTest
     * con cada alicuota). Rubro y proveedor se unen por la ferreteria del articulo. Filas (id, codigo, descripcion,
     * rubro, proveedor, costo, moneda, alicuota, precio final, precio manual).
     */
    @Query(nativeQuery = true, value = """
            select a.id, a.codigo_interno, a.descripcion, r.nombre, p.nombre, a.costo, a.moneda, a.alicuota_iva,
                   a.precio_final, a.precio_manual
            from articulo a
            left join rubro r on r.id = a.rubro_id and r.ferreteria_id = a.ferreteria_id
            left join proveedor p on p.id = a.proveedor_id and p.ferreteria_id = a.ferreteria_id
            where a.ferreteria_id = :fid and a.activo
              and ((:control = 'COSTO_CERO' and a.costo = 0)
                or (:control = 'SIN_RUBRO' and r.id is null)
                or (:control = 'IVA_CERO' and a.alicuota_iva = 'IVA_0')
                or (:control = 'MARGEN_NEGATIVO'
                    and (json_length(a.recargos_costo) > 0
                        or a.costo * (case when a.moneda = 'USD' then :cotizacion else 1 end)
                            * (100 + case a.alicuota_iva when 'IVA_0' then 0 when 'IVA_2_5' then 2.5 when 'IVA_5' then 5
                                     when 'IVA_10_5' then 10.5 when 'IVA_21' then 21 when 'IVA_27' then 27 end)
                            > a.precio_final * 100)))
            order by a.descripcion, a.id
            """)
    List<Object[]> enControl(@Param("fid") Long ferreteriaId, @Param("control") String control,
                             @Param("cotizacion") BigDecimal cotizacion);

    /**
     * Los articulos de la ferreteria del request en ese control de datos (GDS-18), sin el costo real; en margen
     * negativo, los candidatos (ver {@code enControl}). Cotizacion null si no hay dolar.
     */
    default List<ArticuloEnControl> enControlScoped(ControlDeDatos control, BigDecimal cotizacion) {
        return enControl(TenantContext.requireFerreteriaId(), control.name(), cotizacion).stream()
                .map(f -> new ArticuloEnControl((Long) f[0], (String) f[1], (String) f[2], (String) f[3],
                        (String) f[4], (BigDecimal) f[5], (String) f[6], (String) f[7], (BigDecimal) f[8],
                        f[9] instanceof Boolean manual ? manual : ((Number) f[9]).intValue() != 0, null))
                .toList();
    }

    /** Pares (id, recargos del costo) de los articulos de la ferreteria con esos ids; los ajenos no vienen. */
    @Query("select a.id, a.recargosCosto from Articulo a where a.ferreteriaId = :fid and a.id in :ids")
    List<Object[]> recargosDe(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /** Los recargos del costo de esos articulos de la ferreteria del request, por id; los ajenos no vienen. */
    @SuppressWarnings("unchecked")
    default Map<Long, RecargosDeCosto> recargosDeScoped(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return recargosDe(TenantContext.requireFerreteriaId(), ids).stream()
                .collect(Collectors.toMap(f -> (Long) f[0], f -> new RecargosDeCosto((List<RecargoCosto>) f[1])));
    }

    /**
     * Desde cuando esta al dia el costo de cada articulo (GDS-05): la fecha mas nueva entre el ultimo historial que
     * cambio el costo o la moneda (un cambio solo de precio no cuenta), la ultima importacion de lista que toco el
     * articulo (aunque haya confirmado el mismo costo) y el alta. Historial e importacion se unen por la ferreteria del
     * articulo. Filas (id, fecha y hora guardadas).
     */
    @Query(nativeQuery = true, value = """
            select a.id,
                   greatest(a.creado_en,
                            coalesce((select max(h.fecha) from historial_precio h
                                      where h.ferreteria_id = a.ferreteria_id and h.articulo_id = a.id
                                        and (h.costo_anterior is null or h.costo_anterior <> h.costo_nuevo
                                             or (h.moneda_anterior is not null and h.moneda_anterior <> h.moneda))),
                                     a.creado_en),
                            coalesce(i.fecha, a.creado_en))
            from articulo a
            left join importacion_lista i on i.id = a.ultima_importacion_id and i.ferreteria_id = a.ferreteria_id
            where a.ferreteria_id = :fid and a.id in :ids
            """)
    List<Object[]> costoAl(@Param("fid") Long ferreteriaId, @Param("ids") Collection<Long> ids);

    /**
     * El dia de {@link #costoAl} de cada id de la ferreteria del request; los ajenos o inexistentes no figuran. La fecha
     * vuelve a la hora de Buenos Aires con la zona de la JVM, la misma conversion con la que Hibernate la guardo
     * (docs/spec.md, «Despliegue: zona horaria»).
     */
    default Map<Long, LocalDate> costoAlScoped(Collection<Long> ids) {
        Map<Long, LocalDate> fechas = new HashMap<>();
        if (ids.isEmpty()) {
            return fechas;
        }
        for (Object[] fila : costoAl(TenantContext.requireFerreteriaId(), ids)) {
            Object fecha = fila[1];
            LocalDateTime momento = fecha instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) fecha;
            fechas.put(((Number) fila[0]).longValue(), momento.toLocalDate());
        }
        return fechas;
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
