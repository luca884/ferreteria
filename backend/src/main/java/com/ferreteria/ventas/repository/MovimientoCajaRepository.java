package com.ferreteria.ventas.repository;

import com.ferreteria.tenant.TenantContext;
import com.ferreteria.tenant.TenantScopedRepository;
import com.ferreteria.ventas.model.MovimientoCaja;
import com.ferreteria.ventas.model.TipoMovimientoCaja;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public interface MovimientoCajaRepository extends TenantScopedRepository<MovimientoCaja, Long> {

    @Query("select m from MovimientoCaja m where m.ferreteriaId = :fid and m.cajaId = :cajaId order by m.fecha, m.id")
    List<MovimientoCaja> deCaja(@Param("fid") Long ferreteriaId, @Param("cajaId") Long cajaId);

    @Query("select m.tipo, sum(m.importe) from MovimientoCaja m where m.ferreteriaId = :fid and m.cajaId = :cajaId "
            + "group by m.tipo")
    List<Object[]> sumas(@Param("fid") Long ferreteriaId, @Param("cajaId") Long cajaId);

    @Query("select m from MovimientoCaja m where m.ferreteriaId = :fid and m.fecha >= :desde and m.fecha < :hasta "
            + "order by m.fecha, m.id")
    List<MovimientoCaja> delRango(@Param("fid") Long ferreteriaId, @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    /** Los movimientos de todas las cajas de la ferreteria entre esas fechas ({@code hasta} exclusivo), del mas viejo al mas nuevo. */
    default List<MovimientoCaja> delRangoScoped(LocalDateTime desde, LocalDateTime hasta) {
        return delRango(TenantContext.requireFerreteriaId(), desde, hasta);
    }

    /** Los movimientos de la caja, en el orden en que se cargaron. */
    default List<MovimientoCaja> deCajaScoped(Long cajaId) {
        return deCaja(TenantContext.requireFerreteriaId(), cajaId);
    }

    /** Total de ingresos, gastos y retiros de la caja; los tipos sin movimientos no vienen. */
    default Map<TipoMovimientoCaja, BigDecimal> totalesScoped(Long cajaId) {
        Map<TipoMovimientoCaja, BigDecimal> totales = new EnumMap<>(TipoMovimientoCaja.class);
        sumas(TenantContext.requireFerreteriaId(), cajaId)
                .forEach(fila -> totales.put((TipoMovimientoCaja) fila[0], (BigDecimal) fila[1]));
        return totales;
    }
}
