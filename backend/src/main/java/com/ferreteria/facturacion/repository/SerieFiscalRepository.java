package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.SerieFiscal;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Las series de numeración, siempre de la ferretería del request. La escribe solo el motor, con UPDATE condicionales. Las
 * horas del lease salen del {@code Clock} del motor, nunca de {@code NOW()} de la base: así los tests las controlan y
 * todas las instancias comparan contra el mismo reloj.
 *
 * <p>El lease (L5): se toma si está libre o vencido, con un dueño nuevo por toma (UUID); se renueva y se usa solo si sigue
 * siendo propio y no venció; se suelta solo si es propio. Un dueño viejo no pisa al nuevo.
 */
public interface SerieFiscalRepository extends TenantScopedRepository<SerieFiscal, Long> {

    /** Crea la serie si no existe (sin cambiar una que ya está). INSERT con ON DUPLICATE KEY: no pelea por locks de hueco. */
    @Modifying
    @Query(value = """
            insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo) values (:fid, :ambiente, :pv, :tipo)
            on duplicate key update ferreteria_id = ferreteria_id
            """, nativeQuery = true)
    int crearSiFalta(@Param("fid") Long ferreteriaId, @Param("ambiente") String ambiente, @Param("pv") int puntoVenta,
            @Param("tipo") int cbteTipo);

    @Query("select s.id from SerieFiscal s where s.ferreteriaId = :fid and s.ambiente = :ambiente "
            + "and s.puntoVenta = :pv and s.cbteTipo = :tipo")
    Optional<Long> idDe(@Param("fid") Long ferreteriaId, @Param("ambiente") Ambiente ambiente,
            @Param("pv") int puntoVenta, @Param("tipo") int cbteTipo);

    /** El id de la serie de la ferretería del request; la crea si no existe. Exige una transacción. */
    default long asegurarScoped(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        crearSiFalta(ferreteriaId, ambiente.name(), puntoVenta, cbteTipo);
        return idDe(ferreteriaId, ambiente, puntoVenta, cbteTipo).orElseThrow();
    }

    /** Toma el lease si está libre o vencido. 1 si lo tomó; 0 si lo tiene otro. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set lease_dueno = :dueno, lease_vence = :vence
            where ferreteria_id = :fid and id = :id and (lease_dueno is null or lease_vence <= :ahora or lease_dueno = :dueno)
            """, nativeQuery = true)
    int tomar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("dueno") String dueno,
            @Param("ahora") Instant ahora, @Param("vence") Instant vence);

    default int tomarScoped(long id, String dueno, Instant ahora, Instant vence) {
        return tomar(TenantContext.requireFerreteriaId(), id, dueno, ahora, vence);
    }

    /** Estira el lease si sigue siendo propio y no venció. 1 si lo estiró; 0 si se perdió. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set lease_vence = :vence
            where ferreteria_id = :fid and id = :id and lease_dueno = :dueno and lease_vence > :ahora
            """, nativeQuery = true)
    int renovar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("dueno") String dueno,
            @Param("ahora") Instant ahora, @Param("vence") Instant vence);

    default int renovarScoped(long id, String dueno, Instant ahora, Instant vence) {
        return renovar(TenantContext.requireFerreteriaId(), id, dueno, ahora, vence);
    }

    /** Suelta el lease si es propio (si ya lo tomó otro, no lo toca). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set lease_dueno = null, lease_vence = null
            where ferreteria_id = :fid and id = :id and lease_dueno = :dueno
            """, nativeQuery = true)
    int soltar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("dueno") String dueno);

    default int soltarScoped(long id, String dueno) {
        return soltar(TenantContext.requireFerreteriaId(), id, dueno);
    }

    /** Lo último autorizado según ARCA (FECompUltimoAutorizado), con el lease propio y vigente. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set ultimo_autorizado = :ultimo, ultima_cbte_fch = :fecha
            where ferreteria_id = :fid and id = :id and lease_dueno = :dueno and lease_vence > :ahora
            """, nativeQuery = true)
    int sincronizar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("dueno") String dueno,
            @Param("ahora") Instant ahora, @Param("ultimo") long ultimo, @Param("fecha") LocalDate fecha);

    default int sincronizarScoped(long id, String dueno, Instant ahora, long ultimo, LocalDate fecha) {
        return sincronizar(TenantContext.requireFerreteriaId(), id, dueno, ahora, ultimo, fecha);
    }

    /** El número recién autorizado pasa a ser el último de la serie (nunca la hace retroceder). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set ultimo_autorizado = :numero, ultima_cbte_fch = :fecha
            where ferreteria_id = :fid and id = :id and (ultimo_autorizado is null or ultimo_autorizado < :numero)
            """, nativeQuery = true)
    int registrarAutorizado(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("fecha") LocalDate fecha);

    default int registrarAutorizadoScoped(long id, long numero, LocalDate fecha) {
        return registrarAutorizado(TenantContext.requireFerreteriaId(), id, numero, fecha);
    }

    // ---- Rama 7 (V36): el backoff del job de reintento y lo que mira el reintento del Dueño ----

    /** La CbteFch del último autorizado de la serie con esa clave; vacío si la serie no existe o no la conoce. */
    @Query("select s.ultimaCbteFch from SerieFiscal s where s.ferreteriaId = :fid and s.ambiente = :ambiente "
            + "and s.puntoVenta = :pv and s.cbteTipo = :tipo")
    Optional<LocalDate> ultimaCbteFch(@Param("fid") Long ferreteriaId, @Param("ambiente") Ambiente ambiente,
            @Param("pv") int puntoVenta, @Param("tipo") int cbteTipo);

    default Optional<LocalDate> ultimaCbteFchScoped(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return ultimaCbteFch(TenantContext.requireFerreteriaId(), ambiente, puntoVenta, cbteTipo);
    }

    /** Las fallas seguidas de la serie, con su fila tomada ({@code FOR UPDATE}) hasta el fin de la transacción. */
    @Query(value = "select fallas_seguidas from serie_fiscal where ferreteria_id = :fid and id = :id for update",
            nativeQuery = true)
    Optional<Integer> fallasSeguidasBloqueando(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default Optional<Integer> fallasSeguidasBloqueandoScoped(long id) {
        return fallasSeguidasBloqueando(TenantContext.requireFerreteriaId(), id);
    }

    /** Otra pasada con error transitorio: las fallas seguidas y desde cuándo el job la vuelve a atender. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set fallas_seguidas = :fallas, proximo_reintento = :proximo
            where ferreteria_id = :fid and id = :id
            """, nativeQuery = true)
    int posponer(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("fallas") int fallas,
            @Param("proximo") Instant proximo);

    default int posponerScoped(long id, int fallas, Instant proximo) {
        return posponer(TenantContext.requireFerreteriaId(), id, fallas, proximo);
    }

    /** El backoff vuelve a cero: la serie autorizó, o una pasada terminó sin error transitorio. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update serie_fiscal set fallas_seguidas = 0, proximo_reintento = null
            where ferreteria_id = :fid and id = :id and fallas_seguidas > 0
            """, nativeQuery = true)
    int reiniciarBackoff(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default int reiniciarBackoffScoped(long id) {
        return reiniciarBackoff(TenantContext.requireFerreteriaId(), id);
    }
}
