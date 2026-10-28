package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La venta facturada contra el panel del super admin (cu-07-venta-facturada S3, sondas p5, p6, p10 y p11 del verificador)
 * con MySQL real: la venta toma {@code config_arca} con {@code FOR SHARE} en {@code decidir} (o el hueco, si la ferretería
 * no tiene fila) y lo sostiene hasta su commit, así el panel la espera y la venta factura con la configuración que decidió;
 * y si el Dueño vacía un dato de CU-01 con la venta ya decidida, esa venta igual factura (B1). Una venta queda frenada entre {@code decidir} y el numerador con un holder JDBC que toma {@code numerador_comprobante}
 * {@code FOR UPDATE}; {@link BarreraDeLocks} afirma qué espera cada uno y que no hubo deadlocks.
 */
class VentaFacturadaPanelConcurrenciaIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";
    private static final String ADMIN = "admin@demo.local";

    /** Resultado de un pedido: quién fue, su estado HTTP y su cuerpo. */
    private record Respuesta(String quien, int status, String cuerpo) {}

    // ---- p5 y p6: el panel espera a la venta en curso ----

    @Test
    @DisplayName("sin fila de config_arca, el panel espera en config_arca a la venta frenada antes del numerador; la venta sale sin factura y el panel con 200, sin deadlocks")
    void sinFilaElPanelEsperaALaVentaEnCurso() throws Exception {
        Tienda t = tiendaConDatosFiscales(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100");
        String vendedor = login(t.emailVendedor());
        String admin = login(ADMIN);
        // La primera venta crea la fila del numerador que va a sostener el holder.
        venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        HolderJdbc holder = BarreraDeLocks.holderJdbc(
                "select * from numerador_comprobante where ferreteria_id = " + t.id() + " for update");
        try {
            String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
            Future<Respuesta> venta = pool.submit(() -> post("venta", vendedor, cuerpo));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).containsExactly("numerador_comprobante");
            Future<Respuesta> panel = pool.submit(() -> panel(admin, t, "de-prueba", "{\"dePrueba\": true}"));
            barrera.esperarBloqueados(2);
            assertThat(barrera.objetosEnEspera()).containsExactly("config_arca", "numerador_comprobante");

            holder.rollback();
            Respuesta v = venta.get(60, TimeUnit.SECONDS);
            Respuesta p = panel.get(60, TimeUnit.SECONDS);

            assertThat(v.status()).as(v.cuerpo()).isEqualTo(201);
            assertThat(objectMapper.readTree(v.cuerpo()).has("fiscal")).isFalse();
            assertThat(p.status()).as(p.cuerpo()).isEqualTo(200);
            assertThat(registrosFiscales(t)).isZero();
            barrera.assertSinDeadlocks(antes);
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("con la facturación habilitada, apagarla desde el panel espera en config_arca a la venta en curso, que sale con «fiscal»; la siguiente sale sin, y sin deadlocks")
    void apagarLaFacturacionEsperaALaVentaEnCurso() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100");
        String vendedor = login(t.emailVendedor());
        String admin = login(ADMIN);
        venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        HolderJdbc holder = BarreraDeLocks.holderJdbc(
                "select * from numerador_comprobante where ferreteria_id = " + t.id() + " for update");
        try {
            String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
            Future<Respuesta> venta = pool.submit(() -> post("venta", vendedor, cuerpo));
            barrera.esperarBloqueados(1);
            Future<Respuesta> panel = pool.submit(() -> panel(admin, t, "facturacion", "{\"habilitada\": false}"));
            barrera.esperarBloqueados(2);
            assertThat(barrera.objetosEnEspera()).containsExactly("config_arca", "numerador_comprobante");

            holder.rollback();
            Respuesta v = venta.get(60, TimeUnit.SECONDS);
            Respuesta p = panel.get(60, TimeUnit.SECONDS);

            assertThat(v.status()).as(v.cuerpo()).isEqualTo(201);
            assertThat(objectMapper.readTree(v.cuerpo()).has("fiscal")).isTrue();
            assertThat(p.status()).as(p.cuerpo()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select facturacion_habilitada from config_arca where ferreteria_id = ?",
                    Boolean.class, t.id())).isFalse();
            barrera.assertSinDeadlocks(antes);
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }

        JsonNode despues = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        assertSinFiscal(despues);
        assertThat(registrosFiscales(t)).isEqualTo(2);
    }

    @Test
    @DisplayName("si el Dueño vacía Ingresos Brutos mientras una venta ya decidida espera el numerador, esa venta sale con factura y el emisor que vio al decidir; la siguiente, sin factura")
    void vaciarUnDatoDeCu01NoTumbaLaVentaYaDecidida() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100");
        String vendedor = login(t.emailVendedor());
        String dueno = login(t.emailDueno());
        venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00")).andExpect(status().isCreated());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        HolderJdbc holder = BarreraDeLocks.holderJdbc(
                "select * from numerador_comprobante where ferreteria_id = " + t.id() + " for update");
        Respuesta v;
        Respuesta guardar;
        try {
            String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
            Future<Respuesta> venta = pool.submit(() -> post("venta", vendedor, cuerpo));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).containsExactly("numerador_comprobante");

            // El Dueño guarda sus datos sin Ingresos Brutos. Hoy espera a la venta (al chocar con el numerador, la venta ya
            // tiene la fila de la ferretería en S por la FK), pero si entrara antes daría lo mismo: la venta que ya decidió
            // factura con lo que vio.
            Future<Respuesta> datos = pool.submit(() -> respuesta("datos fiscales", putJson("/api/datos-fiscales", dueno,
                    "{\"razonSocial\": \"Ventas SA\", \"condicionIva\": \"" + RI + "\", \"domicilioComercial\": "
                            + "\"Av. Luro 1234, Mar del Plata\", \"inicioActividades\": \"2015-03-01\", "
                            + "\"ingresosBrutos\": null}").andReturn().getResponse()));
            esperarHasta(() -> datos.isDone() || barrera.bloqueadosAhora() >= 2);
            holder.rollback();
            v = venta.get(60, TimeUnit.SECONDS);
            guardar = datos.get(60, TimeUnit.SECONDS);
            barrera.assertSinDeadlocks(antes);
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }

        assertThat(guardar.status()).as(guardar.cuerpo()).isEqualTo(200);
        assertThat(v.status()).as(v.cuerpo()).isEqualTo(201);
        JsonNode decidida = objectMapper.readTree(v.cuerpo());
        assertThat(decidida.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(registroDe(decidida.get("ventaId").asLong())).containsEntry("emisor_ingresos_brutos", "901-123456-7");
        JsonNode siguiente = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        assertSinFiscal(siguiente);
        assertThat(registrosFiscales(t)).isEqualTo(2);
    }

    // ---- p10 y p11: ventas de todo tipo a la vez con el panel ----

    @Test
    @DisplayName("ventas a Consumidor Final, a un RI, a cuenta corriente y no facturables a la vez con el panel tocando config_arca: todas 200 o 201, facturas sin huecos por serie y sin deadlocks")
    void mezclaDeVentasConElPanelSinDeadlocks() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100000");
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), "Av. Colón 2000");
        activarFormaCuentaCorriente(t);
        jdbc.update("update cliente set limite_cuenta_corriente = 1000000 where id = ?", ri);
        String vendedor = login(t.emailVendedor());
        String admin = login(ADMIN);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        int rondas = 2;

        List<Respuesta> todas = new ArrayList<>();
        for (int ronda = 0; ronda < rondas; ronda++) {
            List<Callable<Respuesta>> pedidos = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
                pedidos.add(() -> post("anónima", vendedor, cuerpo));
            }
            for (int i = 0; i < 2; i++) {
                String cuerpo = cuerpoDe(t, ri, null, clave(), "121.00", linea(a, "1", "121.00"));
                pedidos.add(() -> post("RI", vendedor, cuerpo));
            }
            for (int i = 0; i < 2; i++) {
                Map<String, Object> cc = mapa(clave(), null, "121.00", linea(a, "1", "121.00"));
                cc.put("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"));
                cc.put("clienteId", ri);
                String cuerpo = objectMapper.writeValueAsString(cc);
                pedidos.add(() -> post("cuenta corriente", vendedor, cuerpo));
            }
            String noFacturable = cuerpoDe(t, null, false, clave(), "121.00", linea(a, "1", "121.00"));
            pedidos.add(() -> post("no facturable", vendedor, noFacturable));
            pedidos.add(() -> panel(admin, t, "de-prueba", "{\"dePrueba\": true}"));
            todas.addAll(enParalelo(pedidos));
        }

        assertThat(todas).allSatisfy(r -> assertThat(r.status()).as(r.quien() + " " + r.cuerpo()).isIn(200, 201));
        barrera.assertSinDeadlocks(antes);
        esperarHasta(() -> jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ? "
                + "and estado <> 'AUTORIZADO'", Long.class, t.id()) == 0);
        assertThat(numerosFiscales(t, "B")).containsExactlyElementsOf(consecutivos(4L * rondas));
        assertThat(numerosFiscales(t, "A")).containsExactlyElementsOf(consecutivos(4L * rondas));
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, ri))
                .isEqualByComparingTo(new BigDecimal("121.00").multiply(BigDecimal.valueOf(2L * rondas)));
    }

    @Test
    @DisplayName("sin fila de config_arca, ventas a la vez con el panel que la crea: todas 200 o 201, sin factura y sin deadlocks")
    void sinFilaVentasYPanelSinDeadlocks() throws Exception {
        Tienda t = tiendaConDatosFiscales(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "100000");
        String vendedor = login(t.emailVendedor());
        String admin = login(ADMIN);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
            pedidos.add(() -> post("anónima", vendedor, cuerpo));
        }
        pedidos.add(() -> panel(admin, t, "de-prueba", "{\"dePrueba\": true}"));

        List<Respuesta> todas = enParalelo(pedidos);

        assertThat(todas).allSatisfy(r -> assertThat(r.status()).as(r.quien() + " " + r.cuerpo()).isIn(200, 201));
        barrera.assertSinDeadlocks(antes);
        assertThat(comprobantes(t)).isEqualTo(8);
        assertThat(registrosFiscales(t)).isZero();
        assertThat(jdbc.queryForObject("select de_prueba from config_arca where ferreteria_id = ?", Boolean.class, t.id()))
                .isTrue();
    }

    // ---- Ayudas ----

    private Respuesta post(String quien, String token, String cuerpo) throws Exception {
        return respuesta(quien, postJson("/api/ventas", token, cuerpo).andReturn().getResponse());
    }

    /** Un cambio del panel del super admin sobre la configuración de ARCA de la tienda. */
    private Respuesta panel(String token, Tienda t, String que, String cuerpo) throws Exception {
        return respuesta("panel", mvc.perform(patch("/api/admin/ferreterias/" + t.id() + "/" + que)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn().getResponse());
    }

    private static Respuesta respuesta(String quien, MockHttpServletResponse r) throws Exception {
        return new Respuesta(quien, r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Long> numerosFiscales(Tienda t, String letra) {
        return jdbc.queryForList("select numero from comprobante_fiscal where ferreteria_id = ? and letra = ? "
                + "order by numero", Long.class, t.id(), letra);
    }

    private static List<Long> consecutivos(long hasta) {
        return LongStream.rangeClosed(1, hasta).boxed().toList();
    }

    /** Todos los pedidos con una largada común (molde de {@code VentaConcurrenciaIntegrationTest}). */
    private static List<Respuesta> enParalelo(List<Callable<Respuesta>> pedidos) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(pedidos.size());
        BarreraDeLocks.Largada largada = new BarreraDeLocks.Largada(pedidos.size());
        try {
            List<Future<Respuesta>> futuros = new ArrayList<>();
            for (Callable<Respuesta> pedido : pedidos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return pedido.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<Respuesta> respuestas = new ArrayList<>();
            for (Future<Respuesta> futuro : futuros) {
                respuestas.add(futuro.get(90, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }
}
