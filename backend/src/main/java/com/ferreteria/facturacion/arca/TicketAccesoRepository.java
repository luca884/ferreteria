package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los tickets de acceso de WSAA de la ferretería del request, uno por (servicio, ambiente). Repositorio de tenant: se usa por
 * los métodos {@code *Scoped}, con la ferretería de {@code TenantContext}. Lo escribe solo {@link PasosDelTicket}, con UPDATE
 * condicionales.
 *
 * <p>El lease, con el molde de {@code SerieFiscalRepository}: se toma si está libre o vencido, con un dueño nuevo por toma
 * (UUID); se suelta solo si es propio, así un dueño viejo no pisa al nuevo. Las horas salen del {@code Clock} de quien llama,
 * nunca de {@code NOW()} de la base: los tests las controlan y las instancias de un deploy comparan contra el mismo reloj.
 */
public interface TicketAccesoRepository extends TenantScopedRepository<TicketAcceso, Long> {

    @Query("select t from TicketAcceso t where t.ferreteriaId = :fid and t.servicio = :servicio and t.ambiente = :ambiente")
    Optional<TicketAcceso> deClave(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio,
            @Param("ambiente") Ambiente ambiente);

    /** La fila con lock exclusivo ({@code FOR UPDATE}): lee lo último confirmado. */
    @Query(value = "select * from ticket_acceso where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente "
            + "for update", nativeQuery = true)
    Optional<TicketAcceso> bloquearDeClave(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio,
            @Param("ambiente") String ambiente);

    /** Crea la fila sin TA si no existe (sin cambiar una que ya está). INSERT con ON DUPLICATE KEY, como la serie. */
    @Modifying
    @Query(value = """
            insert into ticket_acceso (ferreteria_id, servicio, ambiente) values (:fid, :servicio, :ambiente)
            on duplicate key update ferreteria_id = ferreteria_id
            """, nativeQuery = true)
    int crearSiFalta(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio,
            @Param("ambiente") String ambiente);

    /** Toma el lease si está libre o vencido. 1 si lo tomó; 0 si lo tiene otro. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set lease_dueno = :dueno, lease_vence = :vence
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente
              and (lease_dueno is null or lease_vence <= :ahora or lease_dueno = :dueno)
            """, nativeQuery = true)
    int tomar(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("ambiente") String ambiente,
            @Param("dueno") String dueno, @Param("ahora") Instant ahora, @Param("vence") Instant vence);

    /** Suelta el lease si es propio (si ya lo tomó otro, no lo toca). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set lease_dueno = null, lease_vence = null
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente and lease_dueno = :dueno
            """, nativeQuery = true)
    int soltar(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("ambiente") String ambiente,
            @Param("dueno") String dueno);

    /** Guarda el TA nuevo (cifrado), con la huella de la credencial que lo pidió. Reemplaza al anterior. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set huella_credencial = :huella, token_cifrado = :token, sign_cifrado = :sign,
              generado = :generado, vence = :vence
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente
            """, nativeQuery = true)
    int guardarTa(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("ambiente") String ambiente,
            @Param("huella") String huella, @Param("token") byte[] token, @Param("sign") byte[] sign,
            @Param("generado") Instant generado, @Param("vence") Instant vence);

    /** Borra el TA guardado (la fila queda, con su espera y su última falla). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set huella_credencial = null, token_cifrado = null, sign_cifrado = null, generado = null,
              vence = null
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente
            """, nativeQuery = true)
    int borrarTa(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("ambiente") String ambiente);

    /** Lo último que falló (el código de ARCA y la hora), sin tocar la espera. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set ultima_falla = :codigo, ultima_falla_en = :ahora
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente
            """, nativeQuery = true)
    int registrarFalla(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio,
            @Param("ambiente") String ambiente, @Param("codigo") String codigo, @Param("ahora") Instant ahora);

    /** Lo último que falló y, hasta {@code hasta}, sin llamar a WSAA (C4). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update ticket_acceso set ultima_falla = :codigo, ultima_falla_en = :ahora, espera_hasta = :hasta
            where ferreteria_id = :fid and servicio = :servicio and ambiente = :ambiente
            """, nativeQuery = true)
    int esperar(@Param("fid") Long ferreteriaId, @Param("servicio") String servicio, @Param("ambiente") String ambiente,
            @Param("codigo") String codigo, @Param("ahora") Instant ahora, @Param("hasta") Instant hasta);

    default Optional<TicketAcceso> deClaveScoped(String servicio, Ambiente ambiente) {
        return deClave(TenantContext.requireFerreteriaId(), servicio, ambiente);
    }

    /** La fila con lock exclusivo hasta el fin de la transacción; exige una abierta. */
    default Optional<TicketAcceso> bloquearScoped(String servicio, Ambiente ambiente) {
        return bloquearDeClave(TenantContext.requireFerreteriaId(), servicio, ambiente.name());
    }

    default void crearSiFaltaScoped(String servicio, Ambiente ambiente) {
        crearSiFalta(TenantContext.requireFerreteriaId(), servicio, ambiente.name());
    }

    default boolean tomarScoped(String servicio, Ambiente ambiente, String dueno, Instant ahora, Instant vence) {
        return tomar(TenantContext.requireFerreteriaId(), servicio, ambiente.name(), dueno, ahora, vence) == 1;
    }

    default void soltarScoped(String servicio, Ambiente ambiente, String dueno) {
        soltar(TenantContext.requireFerreteriaId(), servicio, ambiente.name(), dueno);
    }

    default void guardarTaScoped(String servicio, Ambiente ambiente, String huella, byte[] token, byte[] sign,
            Instant generado, Instant vence) {
        guardarTa(TenantContext.requireFerreteriaId(), servicio, ambiente.name(), huella, token, sign, generado, vence);
    }

    default void borrarTaScoped(String servicio, Ambiente ambiente) {
        borrarTa(TenantContext.requireFerreteriaId(), servicio, ambiente.name());
    }

    default void registrarFallaScoped(String servicio, Ambiente ambiente, String codigo, Instant ahora) {
        registrarFalla(TenantContext.requireFerreteriaId(), servicio, ambiente.name(), codigo, ahora);
    }

    default void esperarScoped(String servicio, Ambiente ambiente, String codigo, Instant ahora, Instant hasta) {
        esperar(TenantContext.requireFerreteriaId(), servicio, ambiente.name(), codigo, ahora, hasta);
    }
}
