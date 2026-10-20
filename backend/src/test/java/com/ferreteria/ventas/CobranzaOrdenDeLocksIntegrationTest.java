package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.support.SqlCapturado;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.RegistroCobranza;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * W1 + W2 del verify de cuenta-corriente-tramo-3a: el ORDEN DE LOCKS de la cobranza, fijado por el SQL REAL que Hibernate manda
 * a MySQL (con {@link SqlCapturado}, el {@code StatementInspector} del perfil test) y no por mocks ni por carreras de hilos. En
 * un solo hilo, determinista, sin tiempos.
 *
 * <p>El orden canónico (Javadoc de {@link RegistroCobranza}) es: caja {@code FOR SHARE} → cliente {@code FOR UPDATE} → recién
 * ahí el primer SELECT plano (la clave del recibo) → numerador → recibo → partidas → imputado → imputaciones → libro → saldo.
 * Dos reglas firmes de Luca se vuelven observables acá: la caja va ANTES del cliente (W2), y NINGUNA lectura plana corre antes
 * del lock del cliente (W1: la foto REPEATABLE READ se fija con la primera lectura plana y, tomada antes de esperar al cliente,
 * dejaría sobre-imputar). Las mutaciones que invierten caja/cliente o mueven un SELECT plano antes del cliente no rompían
 * ninguna prueba de integración (las absorbían otras capas del lock); esta clase sí las ve. CUIT 30-8410NNNN-1.
 */
class CobranzaOrdenDeLocksIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    RegistroCobranza registroCobranza;

    @Autowired
    CobranzaService cobranzaService;

    @Override
    protected String prefijoDeCuit() {
        return "30-8410";
    }

    /** Ferretería con caja abierta hoy y el tenant puesto en el hilo del test. */
    private record Escenario(Ferreteria f, long caja) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        return new Escenario(f, caja);
    }

    private static CobranzaPedido pedido(String clave, long cliente, String total, Imputar... imputaciones) {
        return new CobranzaPedido(clave, cliente, MedioPago.EFECTIVO, new BigDecimal(total), List.of(imputaciones));
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Qué es cada sentencia
    // ---------------------------------------------------------------------------------------------------------------

    private static final String CAJA_FOR_SHARE = "caja FOR SHARE";
    private static final String CLIENTE_FOR_UPDATE = "cliente FOR UPDATE";
    private static final String SELECT_RECIBO = "select plano de recibo (la clave)";
    private static final String NUMERADOR_INCREMENTA = "numerador_recibo incrementa";
    private static final String NUMERADOR_LEE = "numerador_recibo lee";
    private static final String RECIBO_INSERT = "insert recibo";
    private static final String DEBITOS = "select plano de débitos del libro";
    private static final String PARTIDAS_FOR_UPDATE = "partidas del libro FOR UPDATE";
    private static final String IMPUTADO_FOR_UPDATE = "imputado FOR UPDATE";
    private static final String IMPUTACION_INSERT = "insert imputacion";
    private static final String LIBRO_INSERT = "insert libro (COBRO)";
    private static final String SALDO_UPDATE = "update saldo del cliente";
    private static final String NOMBRE_COBRADOR = "select plano del nombre del cobrador";

    /** Qué hace una sentencia en el orden de locks. Una sentencia desconocida se nombra por su texto: el test la deja a la vista. */
    private static String que(String sqlCrudo) {
        String sql = sqlCrudo.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        boolean select = sql.startsWith("select");
        if (select && sql.contains(" from caja ") && sql.endsWith(" for share")) {
            return CAJA_FOR_SHARE;
        }
        if (select && sql.contains(" from cliente ") && sql.endsWith(" for update")) {
            return CLIENTE_FOR_UPDATE;
        }
        if (select && sql.contains(" from recibo ")) {
            return SELECT_RECIBO;
        }
        if (sql.startsWith("insert into numerador_recibo")) {
            return NUMERADOR_INCREMENTA;
        }
        if (select && sql.contains(" from numerador_recibo ")) {
            return NUMERADOR_LEE;
        }
        if (sql.startsWith("insert into recibo ")) {
            return RECIBO_INSERT;
        }
        if (select && sql.contains(" from movimiento_cuenta_cliente ") && sql.endsWith(" for update")) {
            return PARTIDAS_FOR_UPDATE;
        }
        if (select && sql.contains(" from movimiento_cuenta_cliente ")) {
            return DEBITOS;
        }
        if (select && sql.contains(" from imputacion ") && sql.endsWith(" for update")) {
            return IMPUTADO_FOR_UPDATE;
        }
        if (sql.startsWith("insert into imputacion ")) {
            return IMPUTACION_INSERT;
        }
        if (sql.startsWith("insert into movimiento_cuenta_cliente ")) {
            return LIBRO_INSERT;
        }
        if (sql.startsWith("update cliente set saldo")) {
            return SALDO_UPDATE;
        }
        if (select && sql.contains(" from usuario ")) {
            return NOMBRE_COBRADOR;
        }
        return "DESCONOCIDA: " + sql;
    }

    private static List<String> pasos(List<String> sql) {
        return sql.stream().map(CobranzaOrdenDeLocksIntegrationTest::que).toList();
    }

    /** Toda lectura que NO toma lock ({@code select} sin {@code for update}/{@code for share}) de la lista de pasos. */
    private static boolean esLecturaPlana(String sqlCrudo) {
        String sql = sqlCrudo.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return sql.startsWith("select") && !sql.endsWith(" for update") && !sql.endsWith(" for share");
    }

    /** El orden completo de una cobranza que imputa, de principio a fin (con el cliente tomado una segunda vez en el puerto). */
    private static final List<String> ORDEN_COMPLETO = List.of(
            CAJA_FOR_SHARE,
            CLIENTE_FOR_UPDATE,
            SELECT_RECIBO,
            NUMERADOR_INCREMENTA,
            NUMERADOR_LEE,
            RECIBO_INSERT,
            CLIENTE_FOR_UPDATE,
            DEBITOS,
            DEBITOS,
            PARTIDAS_FOR_UPDATE,
            IMPUTADO_FOR_UPDATE,
            IMPUTACION_INSERT,
            LIBRO_INSERT,
            SALDO_UPDATE,
            NOMBRE_COBRADOR);

    // ---------------------------------------------------------------------------------------------------------------
    // El orden, en el SQL real
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("W2: lo primero que la cobranza le pide a MySQL es la caja FOR SHARE y lo segundo el cliente FOR UPDATE (la caja va ANTES del cliente)")
    void cajaPrimeroYClienteSegundo() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");

        List<String> sql = SqlCapturado.durante(
                () -> registroCobranza.registrar(pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300")), e.f().usuarioId()));

        List<String> pasos = pasos(sql);
        assertThat(pasos.get(0)).as("la caja se toma primero (S): %s", pasos(sql)).isEqualTo(CAJA_FOR_SHARE);
        assertThat(pasos.get(1)).as("el cliente se toma justo después (X): %s", pasos(sql)).isEqualTo(CLIENTE_FOR_UPDATE);
        assertThat(sql.get(0).toLowerCase(Locale.ROOT)).contains("ferreteria_id").contains("abierta");
        assertThat(sql.get(1).toLowerCase(Locale.ROOT)).contains("ferreteria_id");
    }

    @Test
    @DisplayName("W1: NINGUNA lectura plana (cliente, recibos, comprobantes, imputaciones, libro, usuario) corre antes del lock del cliente")
    void nadaPlanoAntesDelLockDelCliente() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");

        List<String> sql = SqlCapturado.durante(
                () -> registroCobranza.registrar(pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300")), e.f().usuarioId()));

        int lockDelCliente = pasos(sql).indexOf(CLIENTE_FOR_UPDATE);
        assertThat(lockDelCliente).as("la cobranza toma al cliente: %s", pasos(sql)).isGreaterThanOrEqualTo(0);
        List<String> antes = sql.subList(0, lockDelCliente);
        assertThat(antes).as("antes del FOR UPDATE del cliente no hay ninguna lectura plana: %s", pasos(antes)).noneMatch(
                CobranzaOrdenDeLocksIntegrationTest::esLecturaPlana);
        assertThat(pasos(antes)).as("lo único anterior es el lock de la caja").containsExactly(CAJA_FOR_SHARE);
    }

    @Test
    @DisplayName("el orden completo: caja S → cliente X → clave del recibo → numerador → recibo → débitos y partidas FOR UPDATE → imputado FOR UPDATE → imputación → libro → saldo")
    void ordenCompleto() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");

        List<String> sql = SqlCapturado.durante(
                () -> registroCobranza.registrar(pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300")), e.f().usuarioId()));

        assertThat(pasos(sql)).as("pasos reales: %s", pasos(sql)).containsExactlyElementsOf(ORDEN_COMPLETO);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("la fachada: el único paso previo a la caja es el pre-chequeo de la clave (select de recibo); lo que sigue es el orden completo, sin nada más en el medio")
    void laFachadaSoloAgregaElPrechequeo() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");

        List<String> sql = SqlCapturado.durante(
                () -> cobranzaService.cobrar(pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300")), e.f().usuarioId()));

        List<String> pasos = pasos(sql);
        assertThat(pasos.get(0)).as("pre-chequeo de la clave en su transacción: %s", pasos(sql)).isEqualTo(SELECT_RECIBO);
        assertThat(pasos.subList(1, pasos.size())).as("pasos reales: %s", pasos(sql)).containsExactlyElementsOf(ORDEN_COMPLETO);
    }

    @Test
    @DisplayName("un adelanto (sin imputaciones) mantiene el mismo comienzo: caja S → cliente X → clave → numerador → recibo, y no lee débitos ni partidas")
    void adelantoMismoComienzo() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);

        List<String> sql = SqlCapturado.durante(() -> registroCobranza.registrar(pedido(clave(), cliente, "500"), e.f().usuarioId()));

        List<String> pasos = pasos(sql);
        assertThat(pasos.subList(0, 6)).as("pasos reales: %s", pasos(sql)).containsExactly(CAJA_FOR_SHARE, CLIENTE_FOR_UPDATE, SELECT_RECIBO,
                NUMERADOR_INCREMENTA, NUMERADOR_LEE, RECIBO_INSERT);
        assertThat(pasos).doesNotContain(DEBITOS, PARTIDAS_FOR_UPDATE, IMPUTADO_FOR_UPDATE, IMPUTACION_INSERT);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Los rechazos y la repetida respetan el mismo orden
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("la clave repetida: caja S → cliente X → recién ahí el select de la clave; no toca el numerador ni inserta nada")
    void laRepetidaLeeLaClaveDespuesDelCliente() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        CobranzaPedido pedido = pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300"));
        registroCobranza.registrar(pedido, e.f().usuarioId());

        List<String> sql = SqlCapturado.durante(() -> registroCobranza.registrar(pedido, e.f().usuarioId()));

        List<String> pasos = pasos(sql);
        assertThat(pasos.subList(0, 3)).as("pasos reales: %s", pasos(sql)).containsExactly(CAJA_FOR_SHARE, CLIENTE_FOR_UPDATE, SELECT_RECIBO);
        assertThat(pasos).doesNotContain(NUMERADOR_INCREMENTA, RECIBO_INSERT, IMPUTACION_INSERT, LIBRO_INSERT, SALDO_UPDATE);
        assertThat(cobros(cliente)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin caja abierta: se rechaza (422) tras mirar la caja y SIN tocar al cliente ni nada más")
    void sinCajaNoTocaAlCliente() {
        Ferreteria f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f.id());
        long cliente = cliente(f, "1000");
        VentaSuelta a = ventaEnLibro(f, cliente, "300");

        List<String> sql = SqlCapturado.durante(() -> assertThatThrownBy(
                () -> registroCobranza.registrar(pedido(clave(), cliente, "300", imputa(a.comprobanteId(), "300")), f.usuarioId()))
                        .isInstanceOfSatisfying(CobranzaRechazadaException.class,
                                r -> assertThat(r.motivo()).isEqualTo(Motivo.CAJA_CERRADA)));

        assertThat(pasos(sql)).as("pasos reales: %s", pasos(sql)).startsWith(CAJA_FOR_SHARE).doesNotContain(CLIENTE_FOR_UPDATE)
                .noneMatch(p -> p.equals(SELECT_RECIBO) || p.equals(NUMERADOR_INCREMENTA));
    }

    @Test
    @DisplayName("cliente desactivado: se rechaza tras caja S → cliente X, sin ninguna lectura plana ni numerador")
    void desactivadoNoLeeNadaMas() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        List<String> sql = SqlCapturado.durante(() -> assertThatThrownBy(
                () -> registroCobranza.registrar(pedido(clave(), cliente, "500"), e.f().usuarioId()))
                        .isInstanceOf(ReglaDeNegocioException.class).hasMessage("El cliente está desactivado."));

        assertThat(pasos(sql)).as("pasos reales: %s", pasos(sql)).containsExactly(CAJA_FOR_SHARE, CLIENTE_FOR_UPDATE);
    }
}
