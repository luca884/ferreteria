package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.ExtractoCuenta;
import com.ferreteria.clientes.service.ExtractoCuenta.Extracto;
import com.ferreteria.clientes.service.ExtractoCuenta.Linea;
import com.ferreteria.clientes.service.FiltroExtracto;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.PagoPedido;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * CCT3B1-503 (CC-074..CC-080, CC-078, CC-076, CC-079): el servicio {@link ExtractoCuenta} sobre MySQL real, con los servicios
 * REALES de ventas, cobranza y anulación armando el libro (VENTA, COBRO, ANULACION, una venta con exceso autorizado y una venta
 * mixta). Verifica el contenido de cada línea, el encabezado (saldoInicial, totales, saldoFinal, con y sin filtro de tipo), la
 * paginación, la impresión truncada y, sobre todo, que es una lectura sin locks y de una sola foto: no espera a una cobranza ni a
 * una anulación pausadas con todo tomado, no ve nunca una operación a medias y no escribe nada. CUIT 30-8505NNNN.
 */
class ExtractoCuentaIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Autowired
    ExtractoCuenta extracto;

    @Autowired
    VentaService ventaService;

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    AnulacionService anulacionService;

    @Autowired
    ClienteService clienteService;

    /** Pausa a un hilo dentro del puerto de la cuenta: ya tiene el cliente y la caja tomados y no confirmó. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8505";
    }

    @AfterEach
    void soltarLosEspias() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        hiloAPausar.set(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

    private record Escenario(Ferreteria f, long caja, long articulo, long formaCC, long formaEfectivo) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        String codigo = "E-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, unidad, "
                + "stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', 'UNIDAD', 10000000, 0, 1)",
                f.id(), codigo);
        long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                + "values (?, 'Efectivo', 'EFECTIVO', 0, 1, 1)", f.id());
        long cc = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class,
                f.id());
        long ef = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'EFECTIVO'", Long.class, f.id());
        return new Escenario(f, caja, articulo, cc, ef);
    }

    /** Una venta íntegra a cuenta corriente de {@code unidades} × $ 1,00; devuelve el id del comprobante. */
    private long ventaCC(Escenario e, long cliente, int unidades, boolean autorizaExceso) {
        ConfirmarVenta venta = new ConfirmarVenta(UUID.randomUUID().toString(),
                List.of(new Renglon(e.articulo(), new BigDecimal(unidades), new BigDecimal("1.00"))), new BigDecimal(unidades),
                e.formaCC(), BigDecimal.ZERO, List.of(), null, null, true, cliente, autorizaExceso);
        return ventaService.cobrar(venta, e.f().usuarioId(), true).comprobante().getId();
    }

    /** Una venta de {@code total} pagada {@code efectivo} en efectivo y el resto a cuenta corriente (la «mixta»). */
    private long ventaMixta(Escenario e, long cliente, int total, int efectivo) {
        ConfirmarVenta venta = new ConfirmarVenta(UUID.randomUUID().toString(),
                List.of(new Renglon(e.articulo(), new BigDecimal(total), new BigDecimal("1.00"))), new BigDecimal(total), null,
                BigDecimal.ZERO, List.of(new PagoPedido(e.formaEfectivo(), new BigDecimal(efectivo)),
                        new PagoPedido(e.formaCC(), new BigDecimal(total - efectivo))),
                null, null, true, cliente, true);
        return ventaService.cobrar(venta, e.f().usuarioId(), true).comprobante().getId();
    }

    private long cobro(Escenario e, long cliente, String total, Imputar... imputaciones) {
        return cobranzaService.cobrar(new CobranzaPedido(UUID.randomUUID().toString(), cliente, MedioPago.EFECTIVO,
                new BigDecimal(total), List.of(imputaciones)), e.f().usuarioId()).recibo().getId();
    }

    private long ultimoMovimiento(long cliente) {
        return jdbc.queryForObject("select max(id) from movimiento_cuenta_cliente where cliente_id = ?", Long.class, cliente);
    }

    /** Cómo queda guardada una fecha escrita por JDBC: igual que lo que bindea Hibernate (hibernate.jdbc.time_zone = UTC). */
    private static LocalDateTime almacenada(LocalDateTime fecha) {
        return fecha.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private void fechar(long movimiento, LocalDateTime fecha) {
        jdbc.update("update movimiento_cuenta_cliente set fecha = ? where id = ?", almacenada(fecha), movimiento);
    }

    private Extracto pedir(long cliente, FiltroExtracto filtro) {
        return extracto.extracto(cliente, filtro);
    }

    private static FiltroExtracto filtro(LocalDate desde, LocalDate hasta, TipoMovimientoCuenta tipo, Integer page, Integer size) {
        return new FiltroExtracto(desde, hasta, tipo, page, size, false);
    }

    private static FiltroExtracto todo() {
        return filtro(null, null, null, 0, 100);
    }

    /** El libro de los casos de contenido: V1, V2 (exceso), mixta, C1 (anulado), C2 (adelanto), ANULACION de C1. */
    private record Libro(long cliente, long v1, long v2, long mixta, long c1, long c2, long reciboC1, long reciboC2,
            long comprobanteV1, long comprobanteV2, long comprobanteMixta) {}

    private Libro libroCompleto(Escenario e) {
        long cliente = cliente(e.f(), "1000");
        long cV1 = ventaCC(e, cliente, 1000, false);
        long v1 = ultimoMovimiento(cliente);
        long cV2 = ventaCC(e, cliente, 500, true); // pasa el límite de 1.000: la autoriza el Dueño
        long v2 = ultimoMovimiento(cliente);
        long cMixta = ventaMixta(e, cliente, 1000, 300); // 300 en efectivo y 700 a cuenta
        long mixta = ultimoMovimiento(cliente);
        long reciboC1 = cobro(e, cliente, "400", imputa(cV1, "400"));
        long c1 = ultimoMovimiento(cliente);
        long reciboC2 = cobro(e, cliente, "100"); // adelanto
        long c2 = ultimoMovimiento(cliente);
        anulacionService.anular(reciboC1, MOTIVO, e.f().usuarioId());
        return new Libro(cliente, v1, v2, mixta, c1, c2, reciboC1, reciboC2, cV1, cV2, cMixta);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Contenido
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-074/CC-078: el libro con VENTA, exceso autorizado, venta mixta, COBRO anulado, adelanto y ANULACION sale de la más nueva a la más vieja, con el saldo real y el contenido de cada línea")
    void contenidoDeLasLineas() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);

        Extracto x = pedir(l.cliente(), todo());

        assertThat(x.lineas()).extracting(Linea::tipo).containsExactly(TipoMovimientoCuenta.ANULACION, TipoMovimientoCuenta.COBRO,
                TipoMovimientoCuenta.COBRO, TipoMovimientoCuenta.VENTA, TipoMovimientoCuenta.VENTA, TipoMovimientoCuenta.VENTA);
        assertThat(x.lineas()).extracting(Linea::id).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(x.lineas()).extracting(Linea::saldo).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("2100"), new BigDecimal("1700"), new BigDecimal("1800"), new BigDecimal("2200"),
                new BigDecimal("1500"), new BigDecimal("1000"));
        Linea anulacion = x.lineas().get(0);
        Linea adelanto = x.lineas().get(1);
        Linea cobroAnulado = x.lineas().get(2);
        Linea mixta = x.lineas().get(3);
        Linea exceso = x.lineas().get(4);
        Linea comun = x.lineas().get(5);

        assertThat(anulacion.reciboId()).as("la ANULACION lleva el recibo del cobro anulado").isEqualTo(l.reciboC1());
        assertThat(anulacion.motivo()).isEqualTo(MOTIVO);
        assertThat(anulacion.usuario()).as("el usuario que anuló").isEqualTo("Dueno CC");
        assertThat(anulacion.debe()).isEqualByComparingTo("400");
        assertThat(anulacion.haber()).isEqualByComparingTo("0");
        assertThat(anulacion.anulado()).as("la ANULACION no es un cobro anulado").isFalse();
        assertThat(cobroAnulado.reciboId()).isEqualTo(l.reciboC1());
        assertThat(cobroAnulado.anulado()).as("el COBRO cuyo recibo tiene ANULACION").isTrue();
        assertThat(cobroAnulado.haber()).isEqualByComparingTo("400");
        assertThat(cobroAnulado.debe()).isEqualByComparingTo("0");
        assertThat(cobroAnulado.motivo()).isNull();
        assertThat(adelanto.anulado()).as("el otro cobro sigue vigente").isFalse();
        assertThat(adelanto.reciboId()).isEqualTo(l.reciboC2());
        assertThat(mixta.comprobanteId()).isEqualTo(l.comprobanteMixta());
        assertThat(mixta.debe()).as("la venta mixta muestra solo su parte a cuenta (700 de 1.000)").isEqualByComparingTo("700");
        assertThat(mixta.haber()).isEqualByComparingTo("0");
        assertThat(exceso.comprobanteId()).isEqualTo(l.comprobanteV2());
        assertThat(exceso.excedioLimite()).isTrue();
        assertThat(exceso.autorizadoPor()).as("el Dueño que autorizó el exceso").isEqualTo("Dueno CC");
        assertThat(comun.comprobanteId()).isEqualTo(l.comprobanteV1());
        assertThat(comun.excedioLimite()).isFalse();
        assertThat(comun.autorizadoPor()).isNull();
        assertThat(comun.usuario()).isEqualTo("Dueno CC");
        assertThat(comun.referencia()).startsWith("Venta N.º ");
        assertThat(anulacion.referencia()).startsWith("Anulación Recibo N.º ");
        assertThat(adelanto.referencia()).startsWith("Recibo N.º ");
        assertThat(x.lineas()).allSatisfy(linea -> assertThat(linea.fecha()).isNotNull());
        assertInvariante(l.cliente());
    }

    @Test
    @DisplayName("CC-076/CC-077: sin filtros saldoFinal = saldoActual = cliente.saldo = saldo de la línea más nueva (I1 por línea) y saldoInicial 0; con 3 saldos coherentes en toda la lista")
    void sinFiltrosCierraConElSaldo() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);

        Extracto x = pedir(l.cliente(), todo());

        assertThat(x.saldoActual()).isEqualByComparingTo(saldo(l.cliente()));
        assertThat(x.saldoFinal()).isEqualByComparingTo(x.saldoActual());
        assertThat(x.lineas().get(0).saldo()).isEqualByComparingTo(x.saldoActual());
        assertThat(x.saldoInicial()).isEqualByComparingTo("0");
        assertThat(x.saldoInicial().add(x.totalDebe()).subtract(x.totalHaber())).isEqualByComparingTo(x.saldoFinal());
        assertThat(x.totalDebe()).isEqualByComparingTo("2600");
        assertThat(x.totalHaber()).isEqualByComparingTo("500");
        assertThat(x.clienteId()).isEqualTo(l.cliente());
        assertThat(x.truncado()).isFalse();
        // I1 por línea: el saldo de cada línea = Σ(debe − haber) hasta ella
        BigDecimal acumulado = BigDecimal.ZERO;
        List<Linea> cronologicas = new ArrayList<>(x.lineas());
        java.util.Collections.reverse(cronologicas);
        for (Linea linea : cronologicas) {
            acumulado = acumulado.add(linea.debe()).subtract(linea.haber());
            assertThat(linea.saldo()).as("saldo después de la línea %d", linea.id()).isEqualByComparingTo(acumulado);
        }
    }

    @Test
    @DisplayName("CC-077: con desde, saldoInicial suma solo lo anterior; con hasta, saldoFinal incluye hasta ese día; con tipo siguen siendo los del libro completo; saldoInicial + debe − haber = saldoFinal sin filtro de tipo")
    void encabezadoConFiltros() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);
        // se reparte el libro en días distintos (el libro real tiene todo «hoy»)
        fechar(l.v1(), LocalDateTime.of(2026, 3, 1, 9, 0));
        fechar(l.v2(), LocalDateTime.of(2026, 3, 5, 23, 59, 59, 999_999_000));
        fechar(l.mixta(), LocalDateTime.of(2026, 3, 10, 0, 0));
        fechar(l.c1(), LocalDateTime.of(2026, 3, 12, 9, 0));
        fechar(l.c2(), LocalDateTime.of(2026, 3, 15, 9, 0));
        fechar(ultimoMovimiento(l.cliente()), LocalDateTime.of(2026, 3, 20, 9, 0)); // la ANULACION

        Extracto desde = pedir(l.cliente(), filtro(LocalDate.of(2026, 3, 10), null, null, 0, 100));
        assertThat(desde.saldoInicial()).as("V1 + V2 = 1.500, la venta mixta de las 00:00 del 10/3 ya es del período").isEqualByComparingTo("1500");
        assertThat(desde.lineas()).hasSize(4);
        assertThat(desde.totalDebe()).isEqualByComparingTo("1100");
        assertThat(desde.totalHaber()).isEqualByComparingTo("500");
        assertThat(desde.saldoFinal()).isEqualByComparingTo(saldo(l.cliente()));
        assertThat(desde.saldoInicial().add(desde.totalDebe()).subtract(desde.totalHaber())).isEqualByComparingTo(desde.saldoFinal());

        Extracto hasta = pedir(l.cliente(), filtro(null, LocalDate.of(2026, 3, 5), null, 0, 100));
        assertThat(hasta.lineas()).as("las 23:59:59.999999 del 5/3 entran con hasta=5/3").hasSize(2);
        assertThat(hasta.saldoFinal()).isEqualByComparingTo("1500");
        assertThat(hasta.saldoInicial()).isEqualByComparingTo("0");
        assertThat(pedir(l.cliente(), filtro(null, LocalDate.of(2026, 3, 4), null, 0, 100)).lineas()).hasSize(1);

        Extracto rango = pedir(l.cliente(), filtro(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 12), null, 0, 100));
        assertThat(rango.lineas()).hasSize(2);
        assertThat(rango.saldoInicial()).isEqualByComparingTo("1500");
        assertThat(rango.saldoFinal()).isEqualByComparingTo("1800");
        assertThat(rango.saldoInicial().add(rango.totalDebe()).subtract(rango.totalHaber())).isEqualByComparingTo(rango.saldoFinal());

        Extracto soloCobros = pedir(l.cliente(), filtro(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 12), TipoMovimientoCuenta.COBRO, 0, 100));
        assertThat(soloCobros.lineas()).hasSize(1);
        assertThat(soloCobros.lineas().get(0).saldo()).as("el saldo real del libro completo hasta el cobro").isEqualByComparingTo("1800");
        assertThat(soloCobros.totalHaber()).isEqualByComparingTo("400");
        assertThat(soloCobros.totalDebe()).isEqualByComparingTo("0");
        assertThat(soloCobros.saldoInicial()).as("con tipo, el saldoInicial sigue siendo el del libro completo").isEqualByComparingTo("1500");
        assertThat(soloCobros.saldoFinal()).as("con tipo, el saldoFinal sigue siendo el del libro completo").isEqualByComparingTo("1800");
        assertThat(soloCobros.tipo()).isEqualTo(TipoMovimientoCuenta.COBRO);
    }

    @Test
    @DisplayName("CC-075: un filtro sin coincidencias es 200 con líneas vacías, 0 páginas y los saldos del libro; desde > hasta es 400 con la clave desde")
    void sinCoincidenciasYRangoInvalido() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);

        Extracto vacio = pedir(l.cliente(), filtro(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31), null, 0, 20));

        assertThat(vacio.lineas()).isEmpty();
        assertThat(vacio.totalLineas()).isZero();
        assertThat(vacio.totalPaginas()).isZero();
        assertThat(vacio.totalDebe()).isEqualByComparingTo("0");
        assertThat(vacio.saldoInicial()).isEqualByComparingTo("0");
        assertThat(vacio.saldoFinal()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> pedir(l.cliente(), filtro(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), null, 0, 20)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, ex -> assertThat(ex.errors()).containsKey("desde"));
    }

    @Test
    @DisplayName("CC-075/CC-076: paginación con totalLineas y totalPaginas; cada página trae los mismos saldos que sin paginar y el tipo filtra de a uno")
    void paginacion() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);

        Extracto completo = pedir(l.cliente(), todo());
        Extracto p0 = pedir(l.cliente(), filtro(null, null, null, 0, 4));
        Extracto p1 = pedir(l.cliente(), filtro(null, null, null, 1, 4));
        Extracto p2 = pedir(l.cliente(), filtro(null, null, null, 2, 4));
        Extracto ventas = pedir(l.cliente(), filtro(null, null, TipoMovimientoCuenta.VENTA, 0, 2));

        assertThat(p0.totalLineas()).isEqualTo(6);
        assertThat(p0.totalPaginas()).isEqualTo(2);
        assertThat(p0.lineas()).hasSize(4);
        assertThat(p1.lineas()).hasSize(2);
        assertThat(p2.lineas()).as("más allá de la última página").isEmpty();
        List<Linea> juntas = new ArrayList<>(p0.lineas());
        juntas.addAll(p1.lineas());
        assertThat(juntas).extracting(Linea::id).containsExactlyElementsOf(completo.lineas().stream().map(Linea::id).toList());
        assertThat(juntas).extracting(Linea::saldo).usingElementComparator(BigDecimal::compareTo)
                .containsExactlyElementsOf(completo.lineas().stream().map(Linea::saldo).toList());
        assertThat(ventas.totalLineas()).isEqualTo(3);
        assertThat(ventas.totalPaginas()).isEqualTo(2);
        assertThat(ventas.lineas()).extracting(Linea::tipo).containsOnly(TipoMovimientoCuenta.VENTA);
        assertThat(ventas.size()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Impresión (D12)
    // ---------------------------------------------------------------------------------------------------------------

    /** Un cliente con {@code n} movimientos AJUSTE/NC sembrados por SQL (no piden comprobante ni recibo) y su saldo al día. */
    private long clienteConLibroGrande(Ferreteria f, int n) {
        long cliente = cliente(f, null);
        List<Object[]> filas = new ArrayList<>();
        BigDecimal saldo = BigDecimal.ZERO;
        // V28: cada AJUSTE del libro (los que van al debe) lleva su ajuste
        long conDebe = java.util.stream.IntStream.range(0, n).filter(i -> i % 3 != 2).count();
        java.util.Iterator<Long> ajustes = SemillaAjuste.ajustes(jdbc, f.id(), cliente, f.usuarioId(), "RECARGO",
                java.util.Collections.nCopies((int) conDebe, new BigDecimal("1.50"))).iterator();
        for (int i = 0; i < n; i++) {
            boolean debe = i % 3 != 2;
            BigDecimal importe = new BigDecimal("1.50");
            saldo = debe ? saldo.add(importe) : saldo.subtract(importe);
            filas.add(new Object[] {f.id(), cliente, debe ? "AJUSTE" : "NC", debe ? importe : BigDecimal.ZERO,
                    debe ? BigDecimal.ZERO : importe, "Mov " + i, debe ? "Ajuste de prueba" : null,
                    almacenada(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i)), f.usuarioId(),
                    debe ? ajustes.next() : null});
        }
        jdbc.batchUpdate("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, referencia, motivo, "
                + "fecha, usuario_id, ajuste_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", filas);
        jdbc.update("update cliente set saldo = ? where id = ?", saldo, cliente);
        return cliente;
    }

    @Test
    @DisplayName("CC-099/D12: impresión con 2.001 líneas → truncado = true y las 2.000 más nuevas, en UNA consulta; con 2.000 exactas no hay truncado; el saldo de la línea más nueva = saldo del cliente")
    void impresionTruncada() {
        Escenario e = escenario();
        long grande = clienteConLibroGrande(e.f(), 2001);
        long justo = clienteConLibroGrande(e.f(), 2000);
        FiltroExtracto impresion = new FiltroExtracto(null, null, null, 5, 7, true);

        Extracto truncado = pedir(grande, impresion);
        Extracto exacto = pedir(justo, impresion);

        assertThat(truncado.truncado()).isTrue();
        assertThat(truncado.lineas()).hasSize(2000);
        assertThat(truncado.totalLineas()).isEqualTo(2001);
        assertThat(truncado.page()).isZero();
        assertThat(truncado.lineas().get(0).saldo()).isEqualByComparingTo(saldo(grande));
        assertThat(truncado.lineas().get(0).id()).isGreaterThan(truncado.lineas().get(1999).id());
        assertThat(jdbc.queryForObject("select min(id) from movimiento_cuenta_cliente where cliente_id = ?", Long.class, grande))
                .as("la línea más vieja quedó afuera").isLessThan(truncado.lineas().get(1999).id());
        assertThat(exacto.truncado()).isFalse();
        assertThat(exacto.lineas()).hasSize(2000);
        assertThat(exacto.saldoFinal()).isEqualByComparingTo(saldo(justo));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CC-079: foto consistente, sin locks, solo lectura
    // ---------------------------------------------------------------------------------------------------------------

    private enum Punto { ASENTAR_COBRO, ASENTAR_ANULACION }

    private void pausar(Punto punto, Hito llego, Hito suelta) {
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        org.mockito.stubbing.Answer<Object> pausa = invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        };
        switch (punto) {
            case ASENTAR_COBRO -> doAnswer(pausa).when(espia).asentarCobro(any());
            case ASENTAR_ANULACION -> doAnswer(pausa).when(espia).asentarAnulacion(any());
        }
    }

    private <T> Future<T> lanzar(ExecutorService pool, Ferreteria f, boolean pausable, Callable<T> trabajo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            if (pausable) {
                hiloAPausar.set(Thread.currentThread());
            }
            try {
                return trabajo.call();
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static <T> T terminaSola(Future<T> futuro, String siSeBloquea) throws Exception {
        try {
            return futuro.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new AssertionError("Quedó esperando más de 20 s sin que nadie lo soltara: " + siSeBloquea, ex);
        }
    }

    /** El extracto es coherente consigo mismo: el saldo de la línea más nueva = saldoActual = saldoFinal, y anulado ⇔ hay ANULACION. */
    private static void assertCoherente(Extracto x, long reciboC1) {
        assertThat(x.lineas()).isNotEmpty();
        assertThat(x.lineas().get(0).saldo()).as("saldo de la línea más nueva = saldoActual").isEqualByComparingTo(x.saldoActual());
        assertThat(x.saldoFinal()).isEqualByComparingTo(x.saldoActual());
        boolean hayAnulacion = x.lineas().stream().anyMatch(l -> l.tipo() == TipoMovimientoCuenta.ANULACION);
        boolean cobroMarcado = x.lineas().stream().anyMatch(l -> l.tipo() == TipoMovimientoCuenta.COBRO && l.anulado());
        assertThat(cobroMarcado).as("COBRO anulado ⇔ ANULACION en la misma foto (recibo %d)", reciboC1).isEqualTo(hayAnulacion);
    }

    @Test
    @DisplayName("CC-079: con una cobranza retenida (cliente X, caja S, sin confirmar) el extracto termina SOLO (0 transacciones en LOCK WAIT) y devuelve el estado de ANTES, coherente; al soltarla ya muestra el cobro")
    void noEsperaALaCobranza() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        ventaCC(e, cliente, 1000, false);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(Punto.ASENTAR_COBRO, llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cobranza = lanzar(pool, e.f(), true, () -> cobro(e, cliente, "300"));
            llego.esperar(); // la cobranza tiene el cliente en X y la caja en S y no confirmó

            Extracto antes = terminaSola(lanzar(pool, e.f(), false, () -> pedir(cliente, todo())),
                    "el extracto esperó el turno de una cobranza (toma algún lock)");

            barrera.esperarBloqueados(0);
            assertThat(barrera.bloqueadosAhora()).as("transacciones en LOCK WAIT").isZero();
            assertThat(antes.lineas()).as("el estado de antes: solo la venta").hasSize(1);
            assertThat(antes.saldoActual()).isEqualByComparingTo("1000");
            assertCoherente(antes, 0);
            suelta.avisar();
            cobranza.get(60, TimeUnit.SECONDS);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        Extracto despues = pedir(cliente, todo());
        assertThat(despues.lineas()).hasSize(2);
        assertThat(despues.saldoActual()).isEqualByComparingTo("700");
        assertCoherente(despues, 0);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CC-079: con un holder que tiene al cliente en X (select … for update + update, sin confirmar) el extracto igual termina solo; no toma ningún lock")
    void noEsperaAUnHolderDelCliente() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        ventaCC(e, cliente, 100, false);
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + cliente + " for update",
                "update cliente set razon_social = razon_social where id = " + cliente);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Extracto x = terminaSola(lanzar(pool, e.f(), false, () -> pedir(cliente, todo())),
                    "el extracto esperó al holder que tiene el cliente en X (toma algún lock)");

            assertThat(x.lineas()).hasSize(1);
            assertThat(new BarreraDeLocks(jdbcRoot()).bloqueadosAhora()).isZero();
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CC-079: con una anulación retenida (todo tomado, sin confirmar) el extracto devuelve el estado de ANTES sin esperar; al confirmar, el de DESPUÉS; nunca a medias")
    void antesODespuesDeLaAnulacion() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        long venta = ventaCC(e, cliente, 1000, false);
        long recibo = cobro(e, cliente, "400", imputa(venta, "400"));
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(Punto.ASENTAR_ANULACION, llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> anulacion = lanzar(pool, e.f(), true, () -> anulacionService.anular(recibo, MOTIVO, e.f().usuarioId()));
            llego.esperar();

            Extracto antes = terminaSola(lanzar(pool, e.f(), false, () -> pedir(cliente, todo())),
                    "el extracto esperó el turno de una anulación");

            assertThat(barrera.bloqueadosAhora()).isZero();
            assertThat(antes.lineas()).as("antes: VENTA y COBRO, sin ANULACION").hasSize(2);
            assertThat(antes.lineas().get(0).anulado()).isFalse();
            assertThat(antes.saldoActual()).isEqualByComparingTo("600");
            assertCoherente(antes, recibo);
            suelta.avisar();
            anulacion.get(60, TimeUnit.SECONDS);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        Extracto despues = pedir(cliente, todo());
        assertThat(despues.lineas()).hasSize(3);
        assertThat(despues.lineas().get(1).anulado()).isTrue();
        assertThat(despues.saldoActual()).isEqualByComparingTo("1000");
        assertCoherente(despues, recibo);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CC-079: extractos pedidos sin parar mientras una anulación corre de verdad → cada uno es antes o después (nunca a medias: saldo = última línea y COBRO anulado ⇔ ANULACION); sin deadlocks")
    void extractosDuranteUnaAnulacionReal() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        long venta = ventaCC(e, cliente, 1000, false);
        List<Long> recibos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            recibos.add(cobro(e, cliente, "50", imputa(venta, "50")));
        }
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int pedidos = 0;
        try {
            Future<?> anulaciones = lanzar(pool, e.f(), false, () -> {
                for (long recibo : recibos) {
                    anulacionService.anular(recibo, MOTIVO, e.f().usuarioId());
                }
                return null;
            });
            do { // se pide hasta que terminan las anulaciones (sin esperas por reloj: el bucle ES la consulta)
                Extracto x = pedir(cliente, todo());
                assertThat(x.lineas().get(0).saldo()).as("saldo de la línea más nueva").isEqualByComparingTo(x.saldoActual());
                assertThat(x.saldoFinal()).isEqualByComparingTo(x.saldoActual());
                long anuladas = x.lineas().stream().filter(l -> l.tipo() == TipoMovimientoCuenta.COBRO && l.anulado()).count();
                long contraasientos = x.lineas().stream().filter(l -> l.tipo() == TipoMovimientoCuenta.ANULACION).count();
                assertThat(anuladas).as("cobros marcados anulados = ANULACION visibles (misma foto)").isEqualTo(contraasientos);
                BigDecimal acumulado = x.lineas().stream().map(l -> l.debe().subtract(l.haber())).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(acumulado).as("Σ(debe − haber) de las líneas = saldo (todo el libro entra)").isEqualByComparingTo(x.saldoActual());
                pedidos++;
            } while (!anulaciones.isDone());
            anulaciones.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            throw new AssertionError("las anulaciones fallaron", ex.getCause());
        } finally {
            pool.shutdownNow();
        }
        assertThat(pedidos).isGreaterThan(0);
        Extracto fin = pedir(cliente, todo());
        assertThat(fin.saldoActual()).isEqualByComparingTo("1000");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CC-079: el extracto no escribe nada: libro, imputaciones, recibos y saldos quedan idénticos")
    void noEscribeNada() {
        Escenario e = escenario();
        Libro l = libroCompleto(e);
        String foto = "select (select count(*) from movimiento_cuenta_cliente where cliente_id = %d), "
                + "(select count(*) from imputacion where cliente_id = %d), (select count(*) from recibo where cliente_id = %d), "
                + "(select saldo from cliente where id = %d), (select coalesce(sum(vigente), 0) from imputacion where cliente_id = %d), "
                + "(select count(*) from numerador_recibo where ferreteria_id = %d)";
        String sql = foto.formatted(l.cliente(), l.cliente(), l.cliente(), l.cliente(), l.cliente(), e.f().id());
        var antes = jdbc.queryForList(sql);

        pedir(l.cliente(), todo());
        pedir(l.cliente(), new FiltroExtracto(null, null, null, null, null, true));
        pedir(l.cliente(), filtro(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), TipoMovimientoCuenta.COBRO, 0, 5));

        assertThat(jdbc.queryForList(sql)).isEqualTo(antes);
        assertInvariante(l.cliente());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CC-080 y S-5
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-080: con dos ferreterías cada extracto trae solo lo suyo, y el cliente de la otra (o uno inexistente) es 404")
    void aislamientoPorFerreteria() {
        Escenario a = escenario();
        Libro la = libroCompleto(a);
        Escenario b = escenario(); // deja el tenant en B
        long clienteB = cliente(b.f(), "5000");
        ventaCC(b, clienteB, 77, false);

        Extracto deB = pedir(clienteB, todo());
        assertThat(deB.lineas()).hasSize(1);
        assertThat(deB.saldoActual()).isEqualByComparingTo("77");
        assertThatThrownBy(() -> pedir(la.cliente(), todo())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> pedir(-5L, todo())).isInstanceOf(ResourceNotFoundException.class);

        TenantContext.setCurrentFerreteriaId(a.f().id());
        Extracto deA = pedir(la.cliente(), todo());
        assertThat(deA.lineas()).hasSize(6);
        assertThat(deA.lineas()).extracting(Linea::id).doesNotContainAnyElementsOf(deB.lineas().stream().map(Linea::id).toList());
        assertThatThrownBy(() -> pedir(clienteB, todo())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("S-5: un cliente desactivado se consulta igual (saldo 0 y libro vacío tras anular todo)")
    void clienteDesactivadoSeConsulta() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobro(e, cliente, "250");
        anulacionService.anular(recibo, MOTIVO, e.f().usuarioId());
        clienteService.cambiarActivo(cliente, false);

        Extracto x = pedir(cliente, todo());

        assertThat(x.lineas()).hasSize(2);
        assertThat(x.saldoActual()).isEqualByComparingTo("0");
        assertThat(x.lineas().get(0).saldo()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, cliente)).isFalse();
        assertInvariante(cliente);
    }
}
