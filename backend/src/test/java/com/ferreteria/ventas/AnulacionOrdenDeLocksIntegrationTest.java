package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.support.SqlCapturado;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CCT3B1-408 (CC-085): el ORDEN DE LOCKS de la anulación, fijado por el SQL REAL que Hibernate manda a MySQL (con
 * {@link SqlCapturado}) y no por mocks ni por carreras de hilos: en un solo hilo, determinista, sin tiempos. Molde:
 * {@code CobranzaOrdenDeLocksIntegrationTest}. CUIT 30-8504NNNN-1.
 *
 * <p>El orden canónico (Javadoc de {@code RegistroAnulacion}): lectura SIN lock del cliente del recibo (transacción aparte) →
 * caja {@code FOR SHARE} → cliente {@code FOR UPDATE} → recién ahí el primer SELECT plano (el recibo) → {@code UPDATE recibo} →
 * imputaciones por PK ascendente {@code FOR UPDATE} → {@code UPDATE imputacion.vigente} → {@code INSERT} de la ANULACION →
 * {@code UPDATE cliente.saldo}; sin ninguna sentencia sobre {@code numerador_recibo} ni lectura de la caja original.
 *
 * <p>La traza es la única que ve la mutación «mover {@code findScoped} antes de {@code resolverParaAnulacion}» a nivel de la
 * transacción de la anulación sin necesitar concurrencia (la integración secuencial no la detecta: el recibo leído antes del
 * cliente es el mismo que se lee después). También fija la ruta de índice del cierre de caja (ver
 * {@code CajaRepository#abiertaPorIdExclusiva}).
 */
class AnulacionOrdenDeLocksIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Autowired
    AnulacionService anulacionService;

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    CajaService cajaService;

    @Override
    protected String prefijoDeCuit() {
        return "30-8504";
    }

    private record Escenario(Ferreteria f, long caja) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        return new Escenario(f, caja);
    }

    private long cobro(Escenario e, long cliente, String total, Imputar... imputaciones) {
        return cobranzaService.cobrar(new CobranzaPedido(UUID.randomUUID().toString(), cliente, MedioPago.EFECTIVO,
                new BigDecimal(total), List.of(imputaciones)), e.f().usuarioId()).recibo().getId();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Qué es cada sentencia
    // ---------------------------------------------------------------------------------------------------------------

    private static final String PRE_RESOLUCION = "select plano del cliente del recibo (tx aparte)";
    private static final String CAJA_FOR_SHARE = "caja FOR SHARE";
    private static final String CLIENTE_FOR_UPDATE = "cliente FOR UPDATE";
    private static final String SELECT_RECIBO = "select plano del recibo";
    private static final String UPDATE_RECIBO = "update recibo";
    private static final String LIBRO_DE_RECIBO = "select plano del libro del recibo";
    private static final String IDS_IMPUTACION = "select plano de los ids de imputación";
    private static final String IMPUTACION_FOR_UPDATE = "imputaciones FOR UPDATE por id";
    private static final String UPDATE_VIGENCIA = "update imputacion set vigente";
    private static final String NOTAS_DE_VENTAS = "select plano de las notas de las ventas liberadas";
    private static final String LIBRO_INSERT = "insert libro (ANULACION)";
    private static final String SALDO_UPDATE = "update saldo del cliente";

    private static String normal(String sqlCrudo) {
        return sqlCrudo.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Qué hace una sentencia en el orden de locks. Una sentencia desconocida se nombra por su texto: el test la deja a la vista. */
    private static String que(String sqlCrudo) {
        String sql = normal(sqlCrudo);
        boolean select = sql.startsWith("select");
        if (select && sql.contains(" from caja ") && sql.endsWith(" for share")) {
            return CAJA_FOR_SHARE;
        }
        if (select && sql.contains(" from cliente ") && sql.endsWith(" for update")) {
            return CLIENTE_FOR_UPDATE;
        }
        if (select && sql.contains(" from recibo ") && sql.startsWith("select r1_0.cliente_id from recibo")) {
            return PRE_RESOLUCION;
        }
        if (select && sql.contains(" from recibo ")) {
            return SELECT_RECIBO;
        }
        if (sql.startsWith("update recibo ")) {
            return UPDATE_RECIBO;
        }
        if (select && sql.contains(" from movimiento_cuenta_cliente ") && sql.indexOf("movimiento_cuenta_cliente")
                != sql.lastIndexOf("movimiento_cuenta_cliente")) {
            return NOTAS_DE_VENTAS; // L10: el libro contra sí mismo, NC con VENTA por el pago
        }
        if (select && sql.contains(" from movimiento_cuenta_cliente ")) {
            return LIBRO_DE_RECIBO;
        }
        if (select && sql.contains(" from imputacion ") && sql.endsWith(" for update")) {
            return IMPUTACION_FOR_UPDATE;
        }
        if (select && sql.contains(" from imputacion ")) {
            return IDS_IMPUTACION;
        }
        if (sql.startsWith("update imputacion ") && sql.contains(" set vigente")) {
            return UPDATE_VIGENCIA;
        }
        if (sql.startsWith("insert into movimiento_cuenta_cliente ")) {
            return LIBRO_INSERT;
        }
        if (sql.startsWith("update cliente set saldo")) {
            return SALDO_UPDATE;
        }
        return "DESCONOCIDA: " + sql;
    }

    private static List<String> pasos(List<String> sql) {
        return sql.stream().map(AnulacionOrdenDeLocksIntegrationTest::que).toList();
    }

    private static boolean esLecturaPlana(String sqlCrudo) {
        String sql = normal(sqlCrudo);
        return sql.startsWith("select") && !sql.endsWith(" for update") && !sql.endsWith(" for share");
    }

    /** La parte con locks de una anulación que da de baja imputaciones, de principio a fin (el cliente se retoma en el puerto). */
    private static final List<String> ORDEN_CON_IMPUTACIONES = List.of(
            PRE_RESOLUCION,
            CAJA_FOR_SHARE,
            CLIENTE_FOR_UPDATE,
            SELECT_RECIBO,
            UPDATE_RECIBO,
            CLIENTE_FOR_UPDATE,
            LIBRO_DE_RECIBO,
            IDS_IMPUTACION,
            IMPUTACION_FOR_UPDATE,
            UPDATE_VIGENCIA,
            NOTAS_DE_VENTAS,
            LIBRO_INSERT,
            SALDO_UPDATE);

    /** Hasta el UPDATE del saldo: lo que sigue son solo lecturas planas inocuas (nombres, imputaciones originales). */
    private static List<String> hastaElSaldo(List<String> pasos) {
        int saldo = pasos.indexOf(SALDO_UPDATE);
        assertThat(saldo).as("la anulación actualiza el saldo: %s", pasos).isGreaterThanOrEqualTo(0);
        return pasos.subList(0, saldo + 1);
    }

    private List<String> anularTrazando(Escenario e, long recibo) {
        return SqlCapturado.durante(() -> anulacionService.anular(recibo, MOTIVO, e.f().usuarioId()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El orden, en el SQL real
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("el orden completo con imputaciones: cliente del recibo (tx aparte) → caja S → cliente X → recibo → update recibo → libro/ids → imputaciones FOR UPDATE por id → update vigencia → notas de las ventas liberadas (L10) → insert ANULACION → update saldo")
    void ordenCompletoConImputaciones() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        long recibo = cobro(e, cliente, "300", imputa(a.comprobanteId(), "300"));

        List<String> sql = anularTrazando(e, recibo);

        List<String> pasos = pasos(sql);
        assertThat(hastaElSaldo(pasos)).as("pasos reales: %s", pasos).containsExactlyElementsOf(ORDEN_CON_IMPUTACIONES);
        assertThat(pasos.subList(pasos.indexOf(SALDO_UPDATE) + 1, pasos.size())).as("después del saldo solo lecturas planas: %s", pasos)
                .allSatisfy(p -> assertThat(p).doesNotStartWith("update").doesNotStartWith("insert").doesNotContain("FOR"));
        assertThat(saldo(cliente)).isEqualByComparingTo("300");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un adelanto (sin imputaciones) mantiene el mismo comienzo y no toca imputaciones: …→ update recibo → cliente X → libro → ids (vacíos) → insert ANULACION → update saldo")
    void ordenDeUnAdelanto() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobro(e, cliente, "500");

        List<String> sql = anularTrazando(e, recibo);

        List<String> pasos = pasos(sql);
        assertThat(hastaElSaldo(pasos)).as("pasos reales: %s", pasos).containsExactly(PRE_RESOLUCION, CAJA_FOR_SHARE, CLIENTE_FOR_UPDATE,
                SELECT_RECIBO, UPDATE_RECIBO, CLIENTE_FOR_UPDATE, LIBRO_DE_RECIBO, IDS_IMPUTACION, LIBRO_INSERT, SALDO_UPDATE);
        assertThat(pasos).doesNotContain(IMPUTACION_FOR_UPDATE, UPDATE_VIGENCIA);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("W1/CC-091: ninguna lectura plana corre antes del FOR UPDATE del cliente salvo la pre-resolución (tx aparte, la primera sentencia) y lo único con lock anterior es la caja en S")
    void nadaPlanoAntesDelLockDelCliente() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        long recibo = cobro(e, cliente, "300", imputa(a.comprobanteId(), "300"));

        List<String> sql = anularTrazando(e, recibo);

        List<String> pasos = pasos(sql);
        int lockDelCliente = pasos.indexOf(CLIENTE_FOR_UPDATE);
        assertThat(lockDelCliente).as("toma al cliente: %s", pasos).isGreaterThanOrEqualTo(0);
        List<String> antes = sql.subList(0, lockDelCliente);
        assertThat(pasos.subList(0, lockDelCliente)).as("antes del cliente: la pre-resolución y la caja, nada más: %s", pasos)
                .containsExactly(PRE_RESOLUCION, CAJA_FOR_SHARE);
        assertThat(antes.subList(1, antes.size())).as("tras la pre-resolución no hay lectura plana: %s", pasos)
                .noneMatch(AnulacionOrdenDeLocksIntegrationTest::esLecturaPlana);
        // la pre-resolución es la lectura de un solo campo (cliente_id) sobre recibo y no trae el recibo entero
        assertThat(normal(sql.get(0))).startsWith("select r1_0.cliente_id from recibo").contains("ferreteria_id");
    }

    @Test
    @DisplayName("CC-085: la anulación no toca numerador_recibo, lee la caja una sola vez (FOR SHARE) y no mira la caja original del recibo; las imputaciones se bloquean con la PK por el camino fijado")
    void sinNumeradorNiCajaOriginal() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        long recibo = cobro(e, cliente, "300", imputa(a.comprobanteId(), "300"));
        jdbc.update("update caja set abierta = null where id = ?", e.caja());
        long cajaDeHoy = cajaAbiertaHoy(e.f().id());

        List<String> sql = anularTrazando(e, recibo);

        assertThat(sql).as("ninguna sentencia sobre el numerador").noneMatch(s -> normal(s).contains("numerador_recibo"));
        assertThat(sql.stream().filter(s -> normal(s).contains(" from caja ")).toList()).as("una sola lectura de caja, con FOR SHARE")
                .hasSize(1).allSatisfy(s -> assertThat(normal(s)).endsWith(" for share"));
        assertThat(sql).as("la caja del recibo (cerrada) no se pide por id").noneMatch(s -> normal(s).contains("from caja")
                && normal(s).contains("c1_0.id=" + e.caja()));
        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, recibo)).isEqualTo(cajaDeHoy);
        assertThat(sql.stream().filter(s -> que(s).equals(IMPUTACION_FOR_UPDATE)).findFirst().orElseThrow())
                .as("FOR UPDATE por PK exacta: force index (primary) y ids").contains("force index (primary)");
        assertInvariante(cliente);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El cierre de caja toma la caja por el mismo camino de índice que los que la comparten
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("el cierre toma la caja ABIERTA por uk_caja_abierta (FOR UPDATE) antes que por la PK, el mismo camino que vender, cobrar y anular en S: evita el deadlock cierre × lectores compartidos (CC-092)")
    void elCierreTomaLaCajaPorElMismoIndiceQueLosCompartidos() {
        Escenario e = escenario();

        List<String> sql = SqlCapturado.durante(() -> cajaService.cerrar(e.caja(), BigDecimal.ZERO, e.f().usuarioId()));

        String primera = normal(sql.get(0));
        assertThat(primera).as("lo primero que hace el cierre: %s", sql).contains("from caja force index (uk_caja_abierta)")
                .contains("ferreteria_id").endsWith(" for update");
        assertThat(sql.stream().filter(s -> normal(s).contains(" from caja ") && normal(s).endsWith(" for update")).count())
                .as("una sola toma de la caja: la abierta no necesita el camino por PK").isEqualTo(1);
    }
}
