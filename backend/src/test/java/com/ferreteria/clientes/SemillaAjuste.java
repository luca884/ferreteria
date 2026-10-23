package com.ferreteria.clientes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Siembra por jdbc los ajustes (GDS-20) que necesitan los tests que arman el libro a mano. Desde V28 un renglón {@code AJUSTE}
 * del libro lleva su {@code ajuste_cuenta} (ck_mcc_ajuste_ref), así que quien siembra un AJUSTE por SQL siembra antes su ajuste:
 * un RECARGO si el renglón va al debe, un DESCUENTO si va al haber.
 */
final class SemillaAjuste {

    private static final String MOTIVO = "Ajuste de prueba";

    private SemillaAjuste() {}

    /** Un ajuste sembrado; devuelve su id. */
    static long ajuste(JdbcTemplate jdbc, long ferreteriaId, long clienteId, long usuarioId, String tipo, BigDecimal importe) {
        return ajustes(jdbc, ferreteriaId, clienteId, usuarioId, tipo, List.of(importe)).get(0);
    }

    /** Un ajuste por importe, del mismo tipo, con números correlativos; devuelve los ids en el mismo orden. */
    static List<Long> ajustes(JdbcTemplate jdbc, long ferreteriaId, long clienteId, long usuarioId, String tipo,
            List<BigDecimal> importes) {
        long base = jdbc.queryForObject("select coalesce(max(numero), 0) from ajuste_cuenta where ferreteria_id = ?", Long.class,
                ferreteriaId);
        List<Object[]> filas = new ArrayList<>();
        for (int i = 0; i < importes.size(); i++) {
            filas.add(new Object[] {ferreteriaId, clienteId, base + 1 + i, tipo, importes.get(i), MOTIVO,
                    UUID.randomUUID().toString(), usuarioId});
        }
        jdbc.batchUpdate("insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, "
                + "usuario_id) values (?, ?, ?, ?, ?, ?, ?, now(6), ?)", filas);
        return jdbc.queryForList("select id from ajuste_cuenta where ferreteria_id = ? and numero > ? order by numero", Long.class,
                ferreteriaId, base);
    }
}
