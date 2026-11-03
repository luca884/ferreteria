package com.ferreteria.facturacion.repository;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaveDeSerie;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.RegistroVigente;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Los registros fiscales, siempre de la ferretería del request. Lo escribe solo el motor ({@code facturacion.motor}): el
 * alta guarda la entidad y cada transición es un UPDATE condicional que mira el estado (y el número) de antes, así un
 * resultado viejo o de otra instancia no pisa nada (devuelve 0). Nada se borra. Desde la V36 una venta puede tener varios
 * registros (el reintento del Dueño deja el anterior como historia) y uno solo vigente: todo lo que lee el registro de una
 * venta lee el vigente ({@code reemplazado_en} null).
 *
 * <p>Las consultas reciben la ferretería explícita; se llaman por su {@code *Scoped}, que la toma de {@code TenantContext}.
 */
public interface ComprobanteFiscalRepository extends TenantScopedRepository<ComprobanteFiscal, Long> {

    /**
     * El PENDIENTE más viejo de la serie (FIFO por id), por el índice {@code ix_comprobante_fiscal_cola}. Un PENDIENTE es
     * siempre vigente (V36: solo un RECHAZADO o un FUERA_DE_FECHA queda reemplazado).
     */
    @Query(value = """
            select * from comprobante_fiscal
            where ferreteria_id = :fid and ambiente = :ambiente and punto_venta = :pv and cbte_tipo = :tipo
              and estado = 'PENDIENTE'
            order by id limit 1
            """, nativeQuery = true)
    Optional<ComprobanteFiscal> siguientePendiente(@Param("fid") Long ferreteriaId, @Param("ambiente") String ambiente,
            @Param("pv") int puntoVenta, @Param("tipo") int cbteTipo);

    default Optional<ComprobanteFiscal> siguientePendienteScoped(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return siguientePendiente(TenantContext.requireFerreteriaId(), ambiente.name(), puntoVenta, cbteTipo);
    }

    /**
     * El registro vigente de una venta (uno solo: {@code uk_comprobante_fiscal_vigente}, V36). Solo lectura: el estado que ve
     * la venta.
     */
    @Query("select c from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId = :comprobanteId "
            + "and c.reemplazadoEn is null")
    Optional<ComprobanteFiscal> deComprobante(@Param("fid") Long ferreteriaId, @Param("comprobanteId") long comprobanteId);

    default Optional<ComprobanteFiscal> deComprobanteScoped(long comprobanteId) {
        return deComprobante(TenantContext.requireFerreteriaId(), comprobanteId);
    }

    /**
     * El id, el estado y la hora del registro vigente de una venta, sin cargar la entidad en el contexto de persistencia (lo
     * que lee el reintento del Dueño en la transacción de quien llama).
     */
    @Query("select new com.ferreteria.facturacion.model.RegistroVigente(c.id, c.estado, c.registradoEn, c.clase, "
            + "c.asociadoId) "
            + "from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId = :comprobanteId "
            + "and c.reemplazadoEn is null")
    Optional<RegistroVigente> vigenteDe(@Param("fid") Long ferreteriaId, @Param("comprobanteId") long comprobanteId);

    default Optional<RegistroVigente> vigenteDeScoped(long comprobanteId) {
        return vigenteDe(TenantContext.requireFerreteriaId(), comprobanteId);
    }

    /** Los registros vigentes de esas ventas (el listado, CU-16): uno por venta. Solo lectura. */
    @Query("select c from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId in :comprobantes "
            + "and c.reemplazadoEn is null")
    List<ComprobanteFiscal> deComprobantes(@Param("fid") Long ferreteriaId,
            @Param("comprobantes") Collection<Long> comprobanteIds);

    /** Vacío sin ventas: no llega a la base. */
    default List<ComprobanteFiscal> deComprobantesScoped(Collection<Long> comprobanteIds) {
        if (comprobanteIds.isEmpty()) {
            return List.of();
        }
        return deComprobantes(TenantContext.requireFerreteriaId(), comprobanteIds);
    }

    /**
     * Las ventas cuyo registro vigente tiene ese número, en cualquier ambiente, clase y letra; con {@code puntoVenta} null,
     * en cualquier punto de venta (la búsqueda por número fiscal, cu-07-factura-mostrador S15). Solo lectura.
     */
    @Query("select c.comprobanteId from ComprobanteFiscal c where c.ferreteriaId = :fid and c.numero = :numero "
            + "and (:pv is null or c.puntoVenta = :pv) and c.reemplazadoEn is null order by c.comprobanteId")
    List<Long> comprobantesConNumero(@Param("fid") Long ferreteriaId, @Param("pv") Integer puntoVenta,
            @Param("numero") long numero);

    default List<Long> comprobantesConNumeroScoped(Integer puntoVenta, long numero) {
        return comprobantesConNumero(TenantContext.requireFerreteriaId(), puntoVenta, numero);
    }

    /**
     * Cuántos registros de la serie, además de {@code id}, tienen un número mayor que {@code ultimo}, vigentes o reemplazados
     * (los cuenta {@code uk_comprobante_fiscal_numero}). El 10016 (rama 7, C6): con alguno, ARCA va atrás de lo que la serie
     * ya tiene y no se renumera. Solo lectura.
     */
    @Query("select count(c) from ComprobanteFiscal c where c.ferreteriaId = :fid and c.ambiente = :ambiente "
            + "and c.puntoVenta = :puntoVenta and c.cbteTipo = :cbteTipo and c.numero > :ultimo and c.id <> :id")
    long contarNumerosDespuesDe(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("ambiente") Ambiente ambiente,
            @Param("puntoVenta") int puntoVenta, @Param("cbteTipo") int cbteTipo, @Param("ultimo") long ultimo);

    default long contarNumerosDespuesDeScoped(long id, Ambiente ambiente, int puntoVenta, int cbteTipo, long ultimo) {
        return contarNumerosDespuesDe(TenantContext.requireFerreteriaId(), id, ambiente, puntoVenta, cbteTipo, ultimo);
    }

    /**
     * La hora más nueva de los registros en trámite de la serie (PENDIENTE, EN_CURSO y EN_VERIFICACION): de ella sale la
     * CbteFch más nueva que la cola va a mandar antes que un registro que se da de alta ahora (FIFO por id). Vacío sin
     * ninguno. Solo lectura (cu-07-advertencias-contingencias S1).
     */
    @Query("select max(c.registradoEn) from ComprobanteFiscal c where c.ferreteriaId = :fid and c.ambiente = :ambiente "
            + "and c.puntoVenta = :puntoVenta and c.cbteTipo = :cbteTipo and c.estado in ("
            + "com.ferreteria.facturacion.model.EstadoFiscal.PENDIENTE, com.ferreteria.facturacion.model.EstadoFiscal.EN_CURSO, "
            + "com.ferreteria.facturacion.model.EstadoFiscal.EN_VERIFICACION)")
    Optional<Instant> ultimaHoraEnTramite(@Param("fid") Long ferreteriaId, @Param("ambiente") Ambiente ambiente,
            @Param("puntoVenta") int puntoVenta, @Param("cbteTipo") int cbteTipo);

    default Optional<Instant> ultimaHoraEnTramiteScoped(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return ultimaHoraEnTramite(TenantContext.requireFerreteriaId(), ambiente, puntoVenta, cbteTipo);
    }

    /** PENDIENTE → EN_CURSO con el número y la CbteFch del intento que se guarda en la misma transacción. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'EN_CURSO', numero = :numero, cbte_fch = :cbteFch
            where ferreteria_id = :fid and id = :id and estado = 'PENDIENTE'
            """, nativeQuery = true)
    int numerar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("cbteFch") LocalDate cbteFch);

    default int numerarScoped(long id, long numero, LocalDate cbteFch) {
        return numerar(TenantContext.requireFerreteriaId(), id, numero, cbteFch);
    }

    /** EN_CURSO (con ese número) → PENDIENTE sin número: el pedido no llegó a ARCA. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'PENDIENTE', numero = null, cbte_fch = null
            where ferreteria_id = :fid and id = :id and estado = 'EN_CURSO' and numero = :numero
            """, nativeQuery = true)
    int liberar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero);

    default int liberarScoped(long id, long numero) {
        return liberar(TenantContext.requireFerreteriaId(), id, numero);
    }

    /** {@code desde} (con ese número) → {@code hacia}, conservando el número: EN_CURSO ⇄ EN_VERIFICACION y las alertas. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = :hacia
            where ferreteria_id = :fid and id = :id and estado = :desde and numero = :numero
            """, nativeQuery = true)
    int pasar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("desde") String desde, @Param("hacia") String hacia);

    default int pasarScoped(long id, long numero, EstadoFiscal desde, EstadoFiscal hacia) {
        return pasar(TenantContext.requireFerreteriaId(), id, numero, desde.name(), hacia.name());
    }

    /** {@code desde} (EN_CURSO o EN_VERIFICACION, con ese número) → AUTORIZADO, con el CAE y lo que dijo ARCA tal cual. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'AUTORIZADO', resultado = 'A', cae = :cae,
              cae_vencimiento = :vencimiento, observaciones = :observaciones, errores = :errores
            where ferreteria_id = :fid and id = :id and estado = :desde and numero = :numero
            """, nativeQuery = true)
    int autorizar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("desde") String desde, @Param("cae") String cae, @Param("vencimiento") LocalDate vencimiento,
            @Param("observaciones") String observaciones, @Param("errores") String errores);

    default int autorizarScoped(long id, long numero, EstadoFiscal desde, String cae, LocalDate vencimiento,
            String observaciones, String errores) {
        return autorizar(TenantContext.requireFerreteriaId(), id, numero, desde.name(), cae, vencimiento, observaciones,
                errores);
    }

    /** EN_CURSO (con ese número) → RECHAZADO: el número se libera, y lo que dijo ARCA queda tal cual. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'RECHAZADO', resultado = 'R', numero = null, cbte_fch = null,
              observaciones = :observaciones, errores = :errores
            where ferreteria_id = :fid and id = :id and estado = 'EN_CURSO' and numero = :numero
            """, nativeQuery = true)
    int rechazar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("observaciones") String observaciones, @Param("errores") String errores);

    default int rechazarScoped(long id, long numero, String observaciones, String errores) {
        return rechazar(TenantContext.requireFerreteriaId(), id, numero, observaciones, errores);
    }

    /** PENDIENTE → FUERA_DE_FECHA, sin número y sin reintento. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'FUERA_DE_FECHA'
            where ferreteria_id = :fid and id = :id and estado = 'PENDIENTE'
            """, nativeQuery = true)
    int fueraDeFecha(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default int fueraDeFechaScoped(long id) {
        return fueraDeFecha(TenantContext.requireFerreteriaId(), id);
    }

    /** EN_VERIFICACION (con ese número) → FUERA_DE_FECHA: ARCA no lo tiene y la CbteFch ya no entra para reenviarlo. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'FUERA_DE_FECHA', numero = null, cbte_fch = null
            where ferreteria_id = :fid and id = :id and estado = 'EN_VERIFICACION' and numero = :numero
            """, nativeQuery = true)
    int vencerSinReenvio(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero);

    default int vencerSinReenvioScoped(long id, long numero) {
        return vencerSinReenvio(TenantContext.requireFerreteriaId(), id, numero);
    }

    /**
     * EN_CURSO (con ese número) → FUERA_DE_FECHA sin número: ARCA lo rechazó con 10016 y el número era el próximo, así que
     * el problema es la CbteFch (C6). Lo que dijo ARCA queda tal cual; el resultado R no, porque no es un RECHAZADO.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'FUERA_DE_FECHA', numero = null, cbte_fch = null,
              observaciones = :observaciones, errores = :errores
            where ferreteria_id = :fid and id = :id and estado = 'EN_CURSO' and numero = :numero
            """, nativeQuery = true)
    int fueraDeFechaPorArca(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("numero") long numero,
            @Param("observaciones") String observaciones, @Param("errores") String errores);

    default int fueraDeFechaPorArcaScoped(long id, long numero, String observaciones, String errores) {
        return fueraDeFechaPorArca(TenantContext.requireFerreteriaId(), id, numero, observaciones, errores);
    }

    // ---- Rama 7 (V36): las acciones del Dueño y la marca de provisorio ----

    /**
     * El vigente RECHAZADO o FUERA_DE_FECHA queda reemplazado por el reintento del Dueño: deja de ser el vigente (y de
     * chocar en {@code uk_comprobante_fiscal_vigente}) y no cambia más. No limpia el contexto de persistencia: corre en la
     * transacción de quien llama, que puede tener sus entidades (el cliente tomado con lock).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set reemplazado_en = :ahora
            where ferreteria_id = :fid and id = :id and estado in ('RECHAZADO', 'FUERA_DE_FECHA')
              and reemplazado_en is null
            """, nativeQuery = true)
    int reemplazar(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("ahora") Instant ahora);

    default int reemplazarScoped(long id, Instant ahora) {
        return reemplazar(TenantContext.requireFerreteriaId(), id, ahora);
    }

    /** El vigente RECHAZADO o FUERA_DE_FECHA de la venta → SIN_FACTURA, con el motivo, el usuario y la hora (S12). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'SIN_FACTURA', motivo_sin_factura = :motivo,
              usuario_sin_factura_id = :usuario, sin_factura_en = :ahora
            where ferreteria_id = :fid and comprobante_id = :comprobante and estado in ('RECHAZADO', 'FUERA_DE_FECHA')
              and reemplazado_en is null
            """, nativeQuery = true)
    int dejarSinFactura(@Param("fid") Long ferreteriaId, @Param("comprobante") long comprobanteId,
            @Param("motivo") String motivo, @Param("usuario") long usuarioId, @Param("ahora") Instant ahora);

    default int dejarSinFacturaScoped(long comprobanteId, String motivo, long usuarioId, Instant ahora) {
        return dejarSinFactura(TenantContext.requireFerreteriaId(), comprobanteId, motivo, usuarioId, ahora);
    }

    /** La venta salió con el ticket provisorio: marca su vigente si sigue en trámite (PENDIENTE, EN_CURSO o EN_VERIFICACION). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set ticket_provisorio = true
            where ferreteria_id = :fid and comprobante_id = :comprobante
              and estado in ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION') and reemplazado_en is null
            """, nativeQuery = true)
    int marcarProvisorio(@Param("fid") Long ferreteriaId, @Param("comprobante") long comprobanteId);

    default int marcarProvisorioScoped(long comprobanteId) {
        return marcarProvisorio(TenantContext.requireFerreteriaId(), comprobanteId);
    }

    // ---- Rama 8 (V38): la anulación de una venta (CU-08, C1 de cu-08-nota-de-credito) ----

    /**
     * El estado, el ambiente y si quedó reemplazado del registro, con su fila tomada ({@code FOR UPDATE}) hasta el fin de la
     * transacción de quien llama: el paso «factura original» del orden de locks de la anulación. Es por la clave primaria,
     * así toma solo esa fila (sin lock de hueco que frene el alta del registro de otra venta), y lee lo último confirmado.
     * No carga la entidad en el contexto de persistencia de quien llama. Una fila (estado, ambiente, reemplazado_en), o
     * ninguna.
     */
    @Query(value = """
            select estado, ambiente, reemplazado_en from comprobante_fiscal
            where ferreteria_id = :fid and id = :id for update
            """, nativeQuery = true)
    List<Object[]> bloquearParaAnular(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default Optional<RegistroTomado> bloquearParaAnularScoped(long id) {
        return bloquearParaAnular(TenantContext.requireFerreteriaId(), id).stream().findFirst()
                .map(fila -> new RegistroTomado(EstadoFiscal.valueOf((String) fila[0]), Ambiente.valueOf((String) fila[1]),
                        fila[2] != null));
    }

    /** Lo que {@link #bloquearParaAnular} lee del registro tomado. */
    record RegistroTomado(EstadoFiscal estado, Ambiente ambiente, boolean reemplazado) {}

    /**
     * El registro entero, con su fila tomada ({@code FOR UPDATE}, por la clave primaria) y leído de lo último confirmado: la
     * factura que copia su nota de crédito, que la anulación ya tomó con {@link #bloquearParaAnular}.
     */
    @Query(value = "select * from comprobante_fiscal where ferreteria_id = :fid and id = :id for update", nativeQuery = true)
    Optional<ComprobanteFiscal> bloquearRegistro(@Param("fid") Long ferreteriaId, @Param("id") long id);

    default Optional<ComprobanteFiscal> bloquearRegistroScoped(long id) {
        return bloquearRegistro(TenantContext.requireFerreteriaId(), id);
    }

    /**
     * La anulación interna de una venta sin CAE (CU-08 punto 3, D12): su vigente PENDIENTE (sin número, así que sin un envío
     * abierto), RECHAZADO o FUERA_DE_FECHA → SIN_FACTURA, con el motivo, el usuario y la hora. Es el mismo cambio condicional
     * que los del job ({@link #numerar} mira el mismo PENDIENTE): con la fila tomada por la anulación, el job espera y
     * después no la encuentra pendiente; si el job la numeró antes, acá no cambia nada. Por la clave primaria, como
     * {@link #bloquearParaAnular}. No limpia el contexto de persistencia: corre en la transacción de quien llama, que tiene
     * sus entidades (la caja, el cliente y la anulación).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update comprobante_fiscal set estado = 'SIN_FACTURA', motivo_sin_factura = :motivo,
              usuario_sin_factura_id = :usuario, sin_factura_en = :ahora
            where ferreteria_id = :fid and id = :id and estado in ('PENDIENTE', 'RECHAZADO', 'FUERA_DE_FECHA')
              and reemplazado_en is null
            """, nativeQuery = true)
    int cerrarSinCae(@Param("fid") Long ferreteriaId, @Param("id") long id, @Param("motivo") String motivo,
            @Param("usuario") long usuarioId, @Param("ahora") Instant ahora);

    default int cerrarSinCaeScoped(long id, String motivo, long usuarioId, Instant ahora) {
        return cerrarSinCae(TenantContext.requireFerreteriaId(), id, motivo, usuarioId, ahora);
    }

    // ---- Rama 7: lo que leen la bandeja de Ventas, «llegó el CAE» y los avisos del mostrador (C1, C4 y C7) ----

    /** Las ventas cuyo registro vigente está en alguno de esos estados (la bandeja, C1), por id. Solo lectura. */
    @Query("select c.comprobanteId from ComprobanteFiscal c where c.ferreteriaId = :fid and c.estado in :estados "
            + "and c.reemplazadoEn is null order by c.comprobanteId")
    List<Long> comprobantesEnEstados(@Param("fid") Long ferreteriaId,
            @Param("estados") Collection<EstadoFiscal> estados);

    default List<Long> comprobantesEnEstadosScoped(Collection<EstadoFiscal> estados) {
        return comprobantesEnEstados(TenantContext.requireFerreteriaId(), estados);
    }

    /** De esas ventas, los registros vigentes que salieron con el ticket provisorio y ya están AUTORIZADO (C7). */
    @Query("select c from ComprobanteFiscal c where c.ferreteriaId = :fid and c.comprobanteId in :comprobantes "
            + "and c.reemplazadoEn is null and c.ticketProvisorio = true "
            + "and c.estado = com.ferreteria.facturacion.model.EstadoFiscal.AUTORIZADO")
    List<ComprobanteFiscal> provisoriasAutorizadas(@Param("fid") Long ferreteriaId,
            @Param("comprobantes") Collection<Long> comprobanteIds);

    /** Vacío sin ventas: no llega a la base. */
    default List<ComprobanteFiscal> provisoriasAutorizadasScoped(Collection<Long> comprobanteIds) {
        if (comprobanteIds.isEmpty()) {
            return List.of();
        }
        return provisoriasAutorizadas(TenantContext.requireFerreteriaId(), comprobanteIds);
    }

    /**
     * Lo que el mostrador avisa al Dueño (C4), contado sobre los registros vigentes: rechazadas (RECHAZADO y FUERA_DE_FECHA),
     * pendientes de CAE (PENDIENTE, EN_CURSO y EN_VERIFICACION) registradas antes de {@code limite}, y en revisión
     * (CAE_NO_COINCIDE y SERIE_DESINCRONIZADA). Por {@code ix_comprobante_fiscal_estado}: las autorizadas, que son casi
     * todas, ni se leen. Una sola fila con las tres cuentas.
     */
    @Query(value = """
            select coalesce(sum(estado in ('RECHAZADO', 'FUERA_DE_FECHA')), 0),
              coalesce(sum(estado in ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION') and registrado_en < :limite), 0),
              coalesce(sum(estado in ('CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA')), 0)
            from comprobante_fiscal
            where ferreteria_id = :fid and reemplazado_en is null
              and estado in ('RECHAZADO', 'FUERA_DE_FECHA', 'PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION', 'CAE_NO_COINCIDE',
                'SERIE_DESINCRONIZADA')
            """, nativeQuery = true)
    List<Object[]> avisos(@Param("fid") Long ferreteriaId, @Param("limite") Instant limite);

    default AvisosFiscales avisosScoped(Instant limite) {
        Object[] fila = avisos(TenantContext.requireFerreteriaId(), limite).get(0);
        return new AvisosFiscales(((Number) fila[0]).longValue(), ((Number) fila[1]).longValue(),
                ((Number) fila[2]).longValue());
    }

    /** Las cuentas de {@link #avisos}. */
    record AvisosFiscales(long rechazadas, long pendientesViejas, long enRevision) {}

    // ---- Rama 7: lo que lee el job de reintento ----

    /**
     * Las series de la ferretería con trabajo para el job (S2, S13): con algún registro en trámite (PENDIENTE, EN_CURSO o
     * EN_VERIFICACION), sin backoff o con el próximo reintento vencido en {@code ahora}, y sin ninguna alerta
     * (CAE_NO_COINCIDE o SERIE_DESINCRONIZADA: esas las resuelve soporte). Sale de los registros, no de las series: un
     * pendiente que nadie atendió todavía puede no tener su fila en {@code serie_fiscal}. En orden FIFO entre series (la del
     * registro en trámite más viejo primero), por {@code ix_comprobante_fiscal_estado} y {@code ix_comprobante_fiscal_cola}.
     * Cada fila es (ambiente, punto de venta, CbteTipo).
     */
    @Query(value = """
            select c.ambiente, c.punto_venta, c.cbte_tipo from comprobante_fiscal c
            left join serie_fiscal s on s.ferreteria_id = c.ferreteria_id and s.ambiente = c.ambiente
              and s.punto_venta = c.punto_venta and s.cbte_tipo = c.cbte_tipo
            where c.ferreteria_id = :fid and c.estado in ('PENDIENTE', 'EN_CURSO', 'EN_VERIFICACION')
              and (s.proximo_reintento is null or s.proximo_reintento <= :ahora)
              and not exists (select 1 from comprobante_fiscal a where a.ferreteria_id = c.ferreteria_id
                and a.ambiente = c.ambiente and a.punto_venta = c.punto_venta and a.cbte_tipo = c.cbte_tipo
                and a.estado in ('CAE_NO_COINCIDE', 'SERIE_DESINCRONIZADA'))
            group by c.ambiente, c.punto_venta, c.cbte_tipo
            order by min(c.id)
            """, nativeQuery = true)
    List<Object[]> seriesConTrabajo(@Param("fid") Long ferreteriaId, @Param("ahora") Instant ahora);

    default List<ClaveDeSerie> seriesConTrabajoScoped(Instant ahora) {
        return seriesConTrabajo(TenantContext.requireFerreteriaId(), ahora).stream()
                .map(fila -> new ClaveDeSerie(Ambiente.valueOf((String) fila[0]), ((Number) fila[1]).intValue(),
                        ((Number) fila[2]).intValue()))
                .toList();
    }
}
