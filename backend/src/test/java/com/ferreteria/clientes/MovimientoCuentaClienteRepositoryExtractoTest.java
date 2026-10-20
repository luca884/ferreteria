package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.LineaDeExtracto;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.TotalesDelPeriodo;
import com.ferreteria.tenant.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3B1-501 (CC-074..CC-078, CC-080): las consultas nativas del extracto sobre MySQL real. La ventana
 * {@code SUM(debe − haber) OVER (ORDER BY id)} corre sobre TODO el libro del cliente y los filtros (fecha, tipo) y el LIMIT van
 * AFUERA: el saldo de cada línea es el real aunque se filtre o se pagine. Sin filtros se bindean centinelas (D11). CUIT
 * 30-8505NNNN.
 */
class MovimientoCuentaClienteRepositoryExtractoTest extends CuentaCorrienteTestBase {

    private static final LocalDateTime DESDE_ABIERTO = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime HASTA_ABIERTO = LocalDateTime.of(9999, 12, 31, 0, 0);
    private static final EnumSet<TipoMovimientoCuenta> TODOS = EnumSet.allOf(TipoMovimientoCuenta.class);

    @Autowired
    MovimientoCuentaClienteRepository libro;

    @Autowired
    PlatformTransactionManager tm;

    @Override
    protected String prefijoDeCuit() {
        return "30-8505";
    }

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(tm).execute(status -> trabajo.get());
    }

    /** Una fila del libro con fecha propia (SQL directo; no toca el saldo del cliente: acá solo se prueban consultas). */
    private long mov(Ferreteria f, long cliente, String tipo, String debe, String haber, LocalDateTime fecha) {
        return mov(f, cliente, tipo, debe, haber, fecha, null, null, null, null, false);
    }

    private long mov(Ferreteria f, long cliente, String tipo, String debe, String haber, LocalDateTime fecha, Long recibo,
            Long comprobante, String motivo, Long autorizadoPor, boolean excedio) {
        // los CHECK del libro piden su soporte: VENTA con comprobante y pago, COBRO/ANULACION con recibo, ANULACION/AJUSTE con motivo
        Long pago = null;
        if (tipo.equals("VENTA")) {
            VentaSuelta venta = comprobante == null ? ventaSuelta(f, cliente, debe) : null;
            if (venta != null) {
                comprobante = venta.comprobanteId();
                pago = venta.pagoId();
            } else {
                pago = jdbc.queryForObject("select max(id) from pago where comprobante_id = ?", Long.class, comprobante);
            }
        }
        if ((tipo.equals("COBRO") || tipo.equals("ANULACION")) && recibo == null) {
            recibo = reciboSuelto(f, cliente, tipo.equals("COBRO") ? haber : debe);
        }
        if (tipo.equals("ANULACION") && motivo == null) {
            motivo = "Cobro mal cargado";
        }
        jdbc.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, comprobante_id, pago_id, "
                + "recibo_id, referencia, motivo, fecha, usuario_id, excedio_limite, autorizado_por) values (?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?)", f.id(), cliente, tipo, new BigDecimal(debe), new BigDecimal(haber), comprobante, pago,
                recibo, "Mov " + tipo, motivo, almacenada(fecha), f.usuarioId(), excedio, autorizadoPor);
        return jdbc.queryForObject("select max(id) from movimiento_cuenta_cliente where ferreteria_id = ? and cliente_id = ?",
                Long.class, f.id(), cliente);
    }

    private List<LineaDeExtracto> lineas(long cliente, LocalDateTime desde, LocalDateTime hastaExcl,
            EnumSet<TipoMovimientoCuenta> tipos, int limite, int offset) {
        return enTx(() -> libro.lineasDelExtractoScoped(cliente, desde, hastaExcl, tipos, limite, offset));
    }

    private List<LineaDeExtracto> todas(long cliente) {
        return lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 100000, 0);
    }

    /** El driver entrega el booleano como Boolean (TINYINT(1)) o como Number (una expresión): se lee de las dos formas. */
    private static boolean si(Object valor) {
        return valor instanceof Boolean b ? b : valor instanceof Number n && n.intValue() != 0;
    }

    /**
     * Cómo queda guardada una fecha escrita por JDBC directo. La aplicación configura {@code hibernate.jdbc.time_zone = UTC}: lo
     * que Hibernate bindea (los parámetros {@code desde}/{@code hasta} de las nativas) pasa de la zona de la JVM a UTC, igual que
     * lo que escribe una entidad. Los seeds por JdbcTemplate hacen la misma conversión para ser comparables con esos parámetros
     * en cualquier zona (en una JVM en UTC es la identidad).
     */
    private static LocalDateTime almacenada(LocalDateTime fecha) {
        return fecha.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDateTime t(int dia, int hora, int minuto) {
        return LocalDateTime.of(2026, 10, dia, hora, minuto);
    }

    @Test
    @DisplayName("CC-074: VENTA, COBRO y ANULACION → ANULACION, COBRO, VENTA (id descendente); dos movimientos con la misma fecha se ordenan por id descendente")
    void ordenIdDescendente() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        LocalDateTime misma = t(10, 9, 0);
        long venta = mov(f, cliente, "VENTA", "1000", "0", misma);
        long cobro = mov(f, cliente, "COBRO", "0", "400", misma);
        long anulacion = mov(f, cliente, "ANULACION", "400", "0", misma);

        List<LineaDeExtracto> lineas = todas(cliente);

        assertThat(lineas).extracting(LineaDeExtracto::getId).containsExactly(anulacion, cobro, venta);
        assertThat(lineas).extracting(LineaDeExtracto::getTipo).containsExactly("ANULACION", "COBRO", "VENTA");
    }

    @Test
    @DisplayName("CC-076: VENTA 1.000 (1.000), COBRO 400 (600), VENTA 200 (800); tipo=COBRO → UNA línea con saldo 600 (no −400); y cada línea trae el saldo real sin filtro")
    void saldoPorLineaRealConFiltroPorTipo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        mov(f, cliente, "VENTA", "1000", "0", t(1, 9, 0));
        long cobro = mov(f, cliente, "COBRO", "0", "400", t(2, 9, 0));
        mov(f, cliente, "VENTA", "200", "0", t(3, 9, 0));

        List<LineaDeExtracto> sinFiltro = todas(cliente);
        List<LineaDeExtracto> soloCobros = lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, EnumSet.of(TipoMovimientoCuenta.COBRO), 20, 0);

        assertThat(sinFiltro).extracting(LineaDeExtracto::getSaldo).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("800"), new BigDecimal("600"), new BigDecimal("1000"));
        assertThat(soloCobros).hasSize(1);
        assertThat(soloCobros.get(0).getId()).isEqualTo(cobro);
        assertThat(soloCobros.get(0).getSaldo()).as("saldo del libro completo hasta el cobro, no −400").isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("CC-076: con filtro por fechas el saldo de cada línea sigue siendo el real (incluye lo anterior a desde)")
    void saldoPorLineaRealConFiltroPorFechas() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        mov(f, cliente, "VENTA", "1000", "0", t(1, 9, 0));
        mov(f, cliente, "COBRO", "0", "400", t(5, 9, 0));
        mov(f, cliente, "VENTA", "200", "0", t(9, 9, 0));
        mov(f, cliente, "VENTA", "50", "0", t(20, 9, 0));

        List<LineaDeExtracto> lineas = lineas(cliente, t(5, 0, 0), t(10, 0, 0), TODOS, 20, 0);

        assertThat(lineas).extracting(LineaDeExtracto::getSaldo).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("800"), new BigDecimal("600"));
    }

    @Test
    @DisplayName("CC-076: 30 movimientos, size=10 page=1 → las mismas líneas y los mismos saldos que sin paginar (ids 20..11)")
    void paginacionNoCambiaElSaldo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "100000");
        TenantContext.setCurrentFerreteriaId(f.id());
        for (int i = 1; i <= 30; i++) {
            if (i % 3 == 0) {
                mov(f, cliente, "COBRO", "0", "7.50", t(1, 9, i));
            } else {
                mov(f, cliente, "VENTA", i + ".25", "0", t(1, 9, i));
            }
        }

        List<LineaDeExtracto> completo = todas(cliente);
        List<LineaDeExtracto> pagina1 = lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 10, 10);

        assertThat(completo).hasSize(30);
        assertThat(pagina1).hasSize(10);
        List<LineaDeExtracto> esperadas = completo.subList(10, 20);
        for (int i = 0; i < 10; i++) {
            assertThat(pagina1.get(i).getId()).isEqualTo(esperadas.get(i).getId());
            assertThat(pagina1.get(i).getSaldo()).isEqualByComparingTo(esperadas.get(i).getSaldo());
        }
        assertThat(completo.get(0).getSaldo()).as("la más nueva = Σ de todo el libro").isEqualByComparingTo(enTx(() -> libro.saldoDelLibroScoped(cliente)));
    }

    @Test
    @DisplayName("CC-075: un movimiento a las 23:59:59.999999 del 10/10 entra con hasta=10/10 (< 11/10 00:00) y no con hasta=09/10; el de las 00:00 entra con desde")
    void bordesDeFecha() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        LocalDateTime finDelDia = LocalDateTime.of(2026, 10, 10, 23, 59, 59, 999_999_000);
        LocalDateTime inicioDelDia = LocalDateTime.of(2026, 10, 10, 0, 0, 0);
        long tarde = mov(f, cliente, "VENTA", "100", "0", finDelDia);
        long temprano = mov(f, cliente, "VENTA", "10", "0", inicioDelDia);
        LocalDateTime hasta1010 = LocalDate.of(2026, 10, 10).plusDays(1).atStartOfDay();
        LocalDateTime hasta0910 = LocalDate.of(2026, 10, 9).plusDays(1).atStartOfDay();
        LocalDateTime desde1010 = LocalDate.of(2026, 10, 10).atStartOfDay();

        assertThat(lineas(cliente, DESDE_ABIERTO, hasta1010, TODOS, 20, 0)).extracting(LineaDeExtracto::getId)
                .containsExactlyInAnyOrder(tarde, temprano);
        assertThat(lineas(cliente, DESDE_ABIERTO, hasta0910, TODOS, 20, 0)).isEmpty();
        assertThat(lineas(cliente, desde1010, HASTA_ABIERTO, TODOS, 20, 0)).extracting(LineaDeExtracto::getId)
                .containsExactlyInAnyOrder(tarde, temprano);
        assertThat(lineas(cliente, desde1010.plusSeconds(1), HASTA_ABIERTO, TODOS, 20, 0)).extracting(LineaDeExtracto::getId)
                .containsExactly(tarde);
        assertThat(enTx(() -> libro.contarLineasScoped(cliente, DESDE_ABIERTO, hasta0910, TODOS))).isZero();
        assertThat(enTx(() -> libro.contarLineasScoped(cliente, DESDE_ABIERTO, hasta1010, TODOS))).isEqualTo(2);
        assertThat(enTx(() -> libro.saldoAntesDeScoped(cliente, desde1010))).as("las 00:00 NO son anteriores a desde").isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.saldoHastaScoped(cliente, hasta0910))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.saldoHastaScoped(cliente, hasta1010))).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("CC-078: anulado = true solo en el COBRO cuyo recibo tiene ANULACION; 0/1 se leen como Number; motivo, usuario, comprobante, excedioLimite y autorizadoPor llegan por línea")
    void contenidoDeCadaLinea() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        long reciboAnulado = reciboSuelto(f, cliente, "400");
        long reciboVivo = reciboSuelto(f, cliente, "100");
        VentaSuelta venta = ventaSuelta(f, cliente, "1000");
        long lVenta = mov(f, cliente, "VENTA", "1000", "0", t(1, 9, 0), null, venta.comprobanteId(), null, f.usuarioId(), true);
        long lCobro = mov(f, cliente, "COBRO", "0", "400", t(2, 9, 0), reciboAnulado, null, null, null, false);
        long lVivo = mov(f, cliente, "COBRO", "0", "100", t(3, 9, 0), reciboVivo, null, null, null, false);
        long lAnulacion = mov(f, cliente, "ANULACION", "400", "0", t(4, 9, 0), reciboAnulado, null, "Cobro mal cargado", null, false);

        Map<Long, LineaDeExtracto> porId = new java.util.HashMap<>();
        todas(cliente).forEach(l -> porId.put(l.getId(), l));

        assertThat(si(porId.get(lCobro).getAnulado())).isTrue();
        assertThat(si(porId.get(lVivo).getAnulado())).isFalse();
        assertThat(si(porId.get(lVenta).getAnulado())).isFalse();
        assertThat(si(porId.get(lAnulacion).getAnulado())).as("la ANULACION no es un cobro anulado").isFalse();
        assertThat(porId.get(lAnulacion).getReciboId()).isEqualTo(reciboAnulado);
        assertThat(porId.get(lAnulacion).getMotivo()).isEqualTo("Cobro mal cargado");
        assertThat(porId.get(lAnulacion).getUsuario()).isEqualTo("Dueno CC");
        assertThat(porId.get(lVenta).getComprobanteId()).isEqualTo(venta.comprobanteId());
        assertThat(si(porId.get(lVenta).getExcedioLimite())).isTrue();
        assertThat(si(porId.get(lCobro).getExcedioLimite())).isFalse();
        assertThat(porId.get(lVenta).getAutorizadoPor()).isEqualTo("Dueno CC");
        assertThat(porId.get(lCobro).getAutorizadoPor()).isNull();
        assertThat(porId.get(lCobro).getMotivo()).isNull();
        assertThat(porId.get(lVenta).getDebe()).isEqualByComparingTo("1000");
        assertThat(porId.get(lVenta).getHaber()).isEqualByComparingTo("0");
        assertThat(porId.get(lVenta).getFecha()).isEqualTo(t(1, 9, 0));
    }

    @Test
    @DisplayName("CC-075: un filtro sin coincidencias → vacío, conteo 0 y totales en 0 (no null)")
    void sinCoincidencias() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        mov(f, cliente, "VENTA", "100", "0", t(1, 9, 0));

        assertThat(lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, EnumSet.of(TipoMovimientoCuenta.COBRO), 20, 0)).isEmpty();
        assertThat(enTx(() -> libro.contarLineasScoped(cliente, DESDE_ABIERTO, HASTA_ABIERTO, EnumSet.of(TipoMovimientoCuenta.COBRO)))).isZero();
        TotalesDelPeriodo totales = enTx(() -> libro.totalesDelPeriodoScoped(cliente, DESDE_ABIERTO, HASTA_ABIERTO, EnumSet.of(TipoMovimientoCuenta.COBRO)));
        assertThat(totales.getDebe()).isEqualByComparingTo("0");
        assertThat(totales.getHaber()).isEqualByComparingTo("0");
        long sinMovimientos = cliente(f, null);
        assertThat(lineas(sinMovimientos, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 20, 0)).as("un cliente sin un solo movimiento").isEmpty();
    }

    @Test
    @DisplayName("saldoAntesDe, totalesDelPeriodo y saldoHasta: Σ del libro anterior a desde; Σdebe y Σhaber filtrados; saldoHasta sin filtro de tipo; centinelas = todo el libro")
    void encabezado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000");
        TenantContext.setCurrentFerreteriaId(f.id());
        mov(f, cliente, "VENTA", "1000", "0", t(1, 9, 0));
        mov(f, cliente, "COBRO", "0", "400", t(5, 9, 0));
        mov(f, cliente, "VENTA", "200", "0", t(9, 9, 0));
        mov(f, cliente, "VENTA", "50", "0", t(20, 9, 0));

        assertThat(enTx(() -> libro.saldoAntesDeScoped(cliente, t(5, 0, 0)))).as("solo lo anterior a desde").isEqualByComparingTo("1000");
        assertThat(enTx(() -> libro.saldoAntesDeScoped(cliente, DESDE_ABIERTO))).as("sin desde: 0").isEqualByComparingTo("0");
        TotalesDelPeriodo rango = enTx(() -> libro.totalesDelPeriodoScoped(cliente, t(5, 0, 0), t(10, 0, 0), TODOS));
        assertThat(rango.getDebe()).isEqualByComparingTo("200");
        assertThat(rango.getHaber()).isEqualByComparingTo("400");
        TotalesDelPeriodo soloVentas = enTx(() -> libro.totalesDelPeriodoScoped(cliente, t(1, 0, 0), HASTA_ABIERTO,
                EnumSet.of(TipoMovimientoCuenta.VENTA)));
        assertThat(soloVentas.getDebe()).isEqualByComparingTo("1250");
        assertThat(soloVentas.getHaber()).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.saldoHastaScoped(cliente, t(10, 0, 0)))).as("incluye todo lo anterior al corte, de cualquier tipo")
                .isEqualByComparingTo("800");
        assertThat(enTx(() -> libro.saldoHastaScoped(cliente, HASTA_ABIERTO))).isEqualByComparingTo("850");
        assertThat(enTx(() -> libro.saldoDelLibroScoped(cliente))).isEqualByComparingTo("850");
    }

    @Test
    @DisplayName("CC-080: otra ferretería con movimientos de ids cercanos no aparece; pedir el cliente ajeno con mi ferretería no trae nada, y viceversa")
    void aislamientoPorFerreteria() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long deA = cliente(a, "5000");
        long deB = cliente(b, "5000");
        mov(a, deA, "VENTA", "100", "0", t(1, 9, 0));
        mov(b, deB, "VENTA", "7000", "0", t(1, 9, 1));
        mov(a, deA, "COBRO", "0", "30", t(2, 9, 0));
        mov(b, deB, "COBRO", "0", "9000", t(2, 9, 1));

        TenantContext.setCurrentFerreteriaId(a.id());
        assertThat(todas(deA)).hasSize(2);
        assertThat(todas(deA)).extracting(LineaDeExtracto::getSaldo).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("70"), new BigDecimal("100"));
        assertThat(todas(deB)).as("el cliente de B con la ferretería A").isEmpty();
        assertThat(enTx(() -> libro.contarLineasScoped(deB, DESDE_ABIERTO, HASTA_ABIERTO, TODOS))).isZero();
        assertThat(enTx(() -> libro.saldoHastaScoped(deB, HASTA_ABIERTO))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.saldoAntesDeScoped(deB, t(30, 0, 0)))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.totalesDelPeriodoScoped(deB, DESDE_ABIERTO, HASTA_ABIERTO, TODOS)).getDebe()).isEqualByComparingTo("0");
        TenantContext.setCurrentFerreteriaId(b.id());
        assertThat(todas(deB)).hasSize(2);
        assertThat(todas(deA)).isEmpty();
    }

    @Test
    @DisplayName("CC-076: un libro de 3.000 movimientos es correcto y paginado (los saldos coinciden con el cálculo en Java) y el plan usa ix_mcc_cliente; el tiempo es informativo")
    void libroGrandeCorrectoYConIndice() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "100000000");
        long otro = cliente(f, "100000000");
        TenantContext.setCurrentFerreteriaId(f.id());
        int n = 3000;
        List<Object[]> filas = new ArrayList<>();
        List<Object[]> filasOtro = new ArrayList<>();
        BigDecimal[] acumulado = new BigDecimal[n];
        BigDecimal corrida = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            boolean venta = i % 4 != 3;
            BigDecimal importe = new BigDecimal(10 + (i % 17) + "." + (i % 100 < 10 ? "0" + i % 10 : "" + i % 100));
            corrida = venta ? corrida.add(importe) : corrida.subtract(importe);
            acumulado[i] = corrida;
            LocalDateTime fecha = almacenada(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i));
            // AJUSTE (debe, con motivo) y NC (haber): no piden comprobante, pago ni recibo, así que se siembran por miles
            filas.add(new Object[] {f.id(), cliente, venta ? "AJUSTE" : "NC", venta ? importe : BigDecimal.ZERO,
                    venta ? BigDecimal.ZERO : importe, "Mov " + i, venta ? "Ajuste de prueba" : null, fecha, f.usuarioId()});
            filasOtro.add(new Object[] {f.id(), otro, "AJUSTE", new BigDecimal("1.00"), BigDecimal.ZERO, "Otro " + i,
                    "Ajuste de prueba", fecha, f.usuarioId()});
        }
        String insert = "insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, referencia, motivo, "
                + "fecha, usuario_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.batchUpdate(insert, filas);
        jdbc.batchUpdate(insert, filasOtro); // ids intercalados con el cliente vecino de la MISMA ferretería: la ventana no los suma

        long inicio = System.nanoTime();
        List<LineaDeExtracto> pagina0 = lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 50, 0);
        List<LineaDeExtracto> ultima = lineas(cliente, DESDE_ABIERTO, HASTA_ABIERTO, TODOS, 50, n - 50);
        long micros = (System.nanoTime() - inicio) / 1000;
        System.out.println("extracto de un libro de " + n + " movimientos: dos páginas en " + micros + " µs (informativo)");

        assertThat(enTx(() -> libro.contarLineasScoped(cliente, DESDE_ABIERTO, HASTA_ABIERTO, TODOS))).isEqualTo(n);
        assertThat(pagina0).hasSize(50);
        for (int i = 0; i < 50; i++) {
            assertThat(pagina0.get(i).getSaldo()).as("línea %d desde la más nueva", i).isEqualByComparingTo(acumulado[n - 1 - i]);
        }
        assertThat(ultima).hasSize(50);
        for (int i = 0; i < 50; i++) {
            assertThat(ultima.get(i).getSaldo()).as("línea %d de la última página", n - 50 + i)
                    .isEqualByComparingTo(acumulado[49 - i]);
        }
        assertThat(pagina0.get(0).getSaldo()).isEqualByComparingTo(enTx(() -> libro.saldoDelLibroScoped(cliente)));

        // EXPLAIN de la consulta REAL (el texto de @Query con literales): el libro del cliente se recorre por ix_mcc_cliente
        String sql = consultaDe("lineasDelExtracto");
        sql = sql.replace(":fid", String.valueOf(f.id())).replace(":clienteId", String.valueOf(cliente))
                .replace(":desde", "'1000-01-01'").replace(":hastaExclusivo", "'9999-12-31'")
                .replace(":tipos", "'VENTA','COBRO','AJUSTE','NC','ANULACION'").replace(":limite", "50").replace(":offset", "0");
        List<Map<String, Object>> plan = jdbc.queryForList("explain " + sql);
        assertThat(plan).as("plan de lineasDelExtracto: %s", plan).anySatisfy(fila -> {
            assertThat(String.valueOf(fila.get("table"))).isEqualTo("m");
            assertThat(String.valueOf(fila.get("key"))).isEqualTo("ix_mcc_cliente");
        });
    }

    private static String consultaDe(String metodo) {
        for (Method m : MovimientoCuentaClienteRepository.class.getDeclaredMethods()) {
            if (m.getName().equals(metodo) && m.isAnnotationPresent(Query.class)) {
                return m.getAnnotation(Query.class).value();
            }
        }
        throw new AssertionError("no hay una consulta " + metodo);
    }
}
