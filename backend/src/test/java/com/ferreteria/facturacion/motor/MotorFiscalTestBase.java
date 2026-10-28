package com.ferreteria.facturacion.motor;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;

/**
 * Base de los tests del motor contra MySQL real: el contexto con el reloj controlable y {@link WsfeDePrueba}
 * ({@link MotorDePruebaConfig}), una ferretería nueva por test (Responsable Inscripto, con CUIT válida y los datos de CU-01
 * completos), marcada «de prueba», con el simulador (punto de venta 1) y la facturación habilitada, esa ferretería en
 * {@link TenantContext}, y el reloj el miércoles 2026-10-28 a las 10:15:30 de Buenos Aires. Las ventas se insertan por SQL
 * (hoy nadie las factura: el enganche es de la rama 3) y los registros fiscales se dan de alta con el motor.
 */
@Import(MotorDePruebaConfig.class)
abstract class MotorFiscalTestBase extends IntegrationTestBase {

    static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate HOY = LocalDate.of(2026, 10, 28);
    static final Instant AHORA = HOY.atTime(10, 15, 30).atZone(ZONA).toInstant();
    /** La serie de las Facturas B del simulador en el punto de venta 1. */
    static final int FACTURA_B = 6;
    static final int FACTURA_A = 1;

    private static final AtomicInteger VENTAS = new AtomicInteger(1_000_000);

    @Autowired
    MotorFiscal motor;
    @Autowired
    RelojControlable reloj;
    @Autowired
    WsfeDePrueba wsfe;
    /** El simulador sin decorar: lo que ARCA tiene del lado suyo (y lo que otro sistema le podría pedir). */
    @Autowired
    @Qualifier("simuladorWsfe")
    ServicioWsfe simulador;

    long ferreteriaId;
    long cuit;

    @BeforeEach
    void ferreteriaConSimulador() {
        reloj.fijar(AHORA);
        wsfe.reiniciar();
        cuit = cuitNuevo();
        ferreteriaId = ferreteriaConFacturacion(cuit);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
        wsfe.reiniciar();
    }

    /** Una ferretería RI con los datos de CU-01 completos, de prueba, con el simulador y la facturación habilitada. */
    long ferreteriaConFacturacion(long cuitEmisor) {
        long f = ferreteriaSinConfig(cuitEmisor);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
                + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-01 12:00:00')", f);
        return f;
    }

    /** Una ferretería RI con los datos de CU-01 completos y un usuario, sin configuración de ARCA. */
    long ferreteriaSinConfig(long cuitEmisor) {
        String conGuiones = conGuiones(cuitEmisor);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", "Motor " + conGuiones, conGuiones);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueño', "
                + "'DUENO')", f, "motor-" + f + "@test.local");
        return f;
    }

    /** Una venta de la ferretería por SQL (la que se factura). */
    long venta(long ferreteria) {
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
        int numero = VENTAS.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
                ferreteria, numero, "motor-" + numero, usuario);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    /** Una Factura B a Consumidor Final sin identificar, por $ 121 al 21 %, de una venta nueva. */
    long registrarFacturaB() {
        return motor.registrar(new AltaFiscal(venta(ferreteriaId), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
    }

    Atencion atenderB() {
        return motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B);
    }

    /** El registro como lo ve la base. */
    Map<String, Object> registro(long id) {
        return jdbc.queryForMap("select * from comprobante_fiscal where id = ?", id);
    }

    String estado(long registro) {
        return jdbc.queryForObject("select estado from comprobante_fiscal where id = ?", String.class, registro);
    }

    Long numero(long registro) {
        return jdbc.queryForObject("select numero from comprobante_fiscal where id = ?", Long.class, registro);
    }

    /** Los intentos del registro, en orden. */
    List<Map<String, Object>> intentos(long registro) {
        return jdbc.queryForList("select * from intento_fiscal where comprobante_fiscal_id = ? order by id", registro);
    }

    /** El último autorizado del lado del simulador (lo que ARCA tiene). */
    long ultimoEnArca(int cbteTipo) {
        return simulador.feCompUltimoAutorizado(auth(), 1, cbteTipo).cbteNro();
    }

    /** El CAE que ARCA tiene para ese número, o null. */
    String caeEnArca(int cbteTipo, long numero) {
        var resultado = simulador.feCompConsultar(auth(), cbteTipo, 1, numero).resultGet();
        return resultado == null ? null : resultado.codAutorizacion();
    }

    /** Otro sistema le pide a ARCA ese número de la serie, directo al simulador (sin el motor). */
    FeCaeResponse autorizarAfuera(PedidoDePrueba pedido) {
        return simulador.feCaeSolicitar(auth(), pedido.armar());
    }

    Auth auth() {
        return new Auth(Ambiente.SIMULADOR, cuit);
    }

    static String conGuiones(long cuit) {
        return String.valueOf(cuit).replaceFirst("(\\d{2})(\\d{8})(\\d)", "$1-$2-$3");
    }

    /** Un CUIT nuevo de persona jurídica (30), con dígito verificador válido. */
    static long cuitNuevo() {
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        while (true) {
            String base = "30" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
            int suma = 0;
            for (int i = 0; i < pesos.length; i++) {
                suma += (base.charAt(i) - '0') * pesos[i];
            }
            int verificador = 11 - suma % 11;
            if (verificador == 11) {
                verificador = 0;
            }
            if (verificador != 10) {
                return Long.parseLong(base + verificador);
            }
        }
    }
}
