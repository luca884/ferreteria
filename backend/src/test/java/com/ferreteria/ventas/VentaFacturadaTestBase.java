package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.config.EjecutorFiscalConfig;
import com.ferreteria.facturacion.motor.MotorDePruebaConfig;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.facturacion.motor.WsfeDePrueba;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/**
 * Armado de los tests de la venta facturada (cu-07-venta-facturada, T4): el contexto del motor ({@link MotorDePruebaConfig}:
 * el reloj controlable y {@link WsfeDePrueba}, el simulador con su contador de pedidos, pausas y fallas) con 2 s de espera
 * del CAE, la misma combinación que {@code FacturacionParaVentaIntegrationTest} (así comparten el contexto de Spring). Cada
 * test arma su ferretería con los datos de CU-01 completos y una CUIT válida, y vende por HTTP como el mostrador. El reloj
 * queda fijo el miércoles 2026-10-28 a las 10:15:30 de Buenos Aires y la caja se abre ese día. La espera se mide con el
 * reloj de pared (el {@code Clock} está fijo).
 */
@Import(MotorDePruebaConfig.class)
@TestPropertySource(properties = "app.facturacion.venta.espera=2s")
abstract class VentaFacturadaTestBase extends VentasTestBase {

    static final Duration ESPERA = Duration.ofSeconds(2);
    /** Lo que puede tardar la venta por encima de la espera (la lectura final del estado, un hilo lento). */
    static final Duration MARGEN = Duration.ofMillis(1500);

    @Autowired
    RelojControlable reloj;
    @Autowired
    WsfeDePrueba wsfe;
    @Autowired
    @Qualifier(EjecutorFiscalConfig.EJECUTOR_FISCAL)
    ThreadPoolTaskExecutor ejecutor;

    @BeforeEach
    void relojFijoYSimuladorLimpio() {
        reloj.fijar(RelojControlableConfig.INICIO);
        wsfe.reiniciar();
    }

    /**
     * Lo que haya quedado en marcha en el ejecutor fiscal (una atención abandonada termina sola) termina antes del test
     * siguiente, que reinicia el contador de pedidos de {@link WsfeDePrueba}.
     */
    @AfterEach
    void sinAtencionesEnMarcha() {
        ThreadPoolExecutor pool = ejecutor.getThreadPoolExecutor();
        esperarHasta(() -> pool.getCompletedTaskCount() == pool.getTaskCount());
        wsfe.reiniciar();
    }

    /**
     * Una ferretería que factura con el simulador (punto de venta 1): datos de CU-01 completos con esa condición frente al
     * IVA, marcada de prueba, la facturación habilitada y la caja abierta el día del reloj.
     */
    protected Tienda tiendaQueFactura(String condicionIva) {
        Tienda t = tiendaConDatosFiscales(condicionIva);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
                + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-01 12:00:00')", t.id());
        return t;
    }

    /** Una ferretería con los datos de CU-01 completos y la caja abierta el día del reloj, sin configuración de ARCA. */
    protected Tienda tiendaConDatosFiscales(String condicionIva) {
        Tienda t = tiendaSinCaja();
        jdbc.update("update ferreteria set cuit = ?, condicion_iva = ?, domicilio_comercial = 'Av. Luro 1234, Mar del Plata', "
                + "inicio_actividades = '2015-03-01', ingresos_brutos = '901-123456-7' where id = ?",
                MotorFiscalTestBase.conGuiones(MotorFiscalTestBase.cuitNuevo()), condicionIva, t.id());
        cajaAbiertaEl(t.id(), LocalDateTime.now(reloj));
        return t;
    }

    /** El panel del super admin deshabilita la facturación (la fila queda, con el simulador). */
    protected void apagarFacturacion(Tienda t) {
        jdbc.update("update config_arca set facturacion_habilitada = false, habilitada_en = null where ferreteria_id = ?",
                t.id());
    }

    /** Un cliente cargado por SQL, como los que se dieron de alta antes de que CU-11 exigiera CUIT y domicilio. */
    protected long cliente(Tienda t, String razonSocial, String condicionIva, String tipoDocumento, String numero,
            String domicilio) {
        jdbc.update("insert into cliente (ferreteria_id, razon_social, condicion_iva, tipo_documento, numero_documento, "
                + "domicilio) values (?, ?, ?, ?, ?, ?)", t.id(), razonSocial, condicionIva, tipoDocumento, numero, domicilio);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razonSocial);
    }

    /** Una CUIT de persona jurídica válida, solo dígitos (como la guarda el cliente). */
    protected static String cuitDeCliente() {
        return String.valueOf(MotorFiscalTestBase.cuitNuevo());
    }

    /** Cobra como el Vendedor de la tienda, con ese cliente (o ninguno) y la marca de facturable (o la de siempre). */
    protected ResultActions venderA(Tienda t, Long clienteId, Boolean facturable, String clave, String total,
            Linea... lineas) throws Exception {
        return postJson("/api/ventas", login(t.emailVendedor()), cuerpoDe(t, clienteId, facturable, clave, total, lineas));
    }

    protected String cuerpoDe(Tienda t, Long clienteId, Boolean facturable, String clave, String total, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));
        if (clienteId != null) {
            cuerpo.put("clienteId", clienteId);
        }
        if (facturable != null) {
            cuerpo.put("facturable", facturable);
        }
        return objectMapper.writeValueAsString(cuerpo);
    }

    /** El registro fiscal de la venta como lo ve la base. */
    protected Map<String, Object> registroDe(long ventaId) {
        return jdbc.queryForMap("select * from comprobante_fiscal where comprobante_id = ?", ventaId);
    }

    protected String estadoFiscal(long ventaId) {
        List<String> estados = jdbc.queryForList("select estado from comprobante_fiscal where comprobante_id = ?",
                String.class, ventaId);
        return estados.isEmpty() ? null : estados.get(0);
    }

    protected long registrosFiscales(Tienda t) {
        return jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ?", Long.class, t.id());
    }

    protected static void assertSinFiscal(JsonNode venta) {
        org.assertj.core.api.Assertions.assertThat(venta.has("fiscal")).as("la venta no trae «fiscal»").isFalse();
    }

    protected static void esperarHasta(BooleanSupplier condicion) {
        long tope = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > tope) {
                throw new AssertionError("No se cumplió la condición en 10 s");
            }
            dormir(Duration.ofMillis(25));
        }
    }

    protected static void dormir(Duration duracion) {
        try {
            Thread.sleep(duracion.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrumpido", e);
        }
    }
}
