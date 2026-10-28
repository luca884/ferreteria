package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.motor.WsfeDePrueba;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-07 paso 5 (cu-07-venta-facturada S3 y S4) contra MySQL real y el simulador: después del commit, sin transacción, la venta
 * pide el CAE con la espera acotada (2 s acá) y responde con el estado que haya; la repetida devuelve el estado actual sin
 * volver a pedirlo; con la serie OCUPADA por otra instancia reintenta dentro de la espera; nada de ARCA (ni una excepción
 * sin traducir del puerto) la hace responder 5xx; y varias ventas a la vez numeran las facturas sin huecos ni deadlocks. El
 * contador de {@link WsfeDePrueba} cuenta cada {@code FECAESolicitar}.
 */
class VentaFacturadaAutorizacionIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";
    /** La serie de las Facturas B del simulador. */
    private static final int FACTURA_B = 6;

    /** Resultado de un POST: estado HTTP y cuerpo. */
    private record Respuesta(int status, JsonNode cuerpo) {}

    @Test
    @DisplayName("la venta repetida con el CAE en vuelo y después ya autorizado devuelve 200 con el estado actual, con un solo FECAESolicitar")
    void repetidaEnVueloYAutorizada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();

        JsonNode primera = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isCreated()));
        long id = primera.get("ventaId").asLong();
        assertThat(primera.get("fiscal").get("estado").asText()).isIn("PENDIENTE", "EN_CURSO");
        pausa.esperarQueLlegue();

        long inicio = System.nanoTime();
        JsonNode enVuelo = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isOk()));
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(ESPERA.dividedBy(2));
        assertThat(enVuelo.get("ventaId").asLong()).isEqualTo(id);
        assertThat(enVuelo.get("fiscal").get("estado").asText()).isEqualTo("EN_CURSO");
        assertThat(enVuelo.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(enVuelo.get("fiscal").get("cae").isNull()).isTrue();

        pausa.soltar();
        esperarHasta(() -> "AUTORIZADO".equals(estadoFiscal(id)));
        JsonNode autorizada = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isOk()));
        assertThat(autorizada.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(autorizada.get("fiscal").get("cae").asText()).isEqualTo(registroDe(id).get("cae"));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("la venta repetida con el CAE pendiente (la serie tomada por otra instancia) devuelve 200 PENDIENTE al instante, sin pedirlo")
    void repetidaPendiente() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        jdbc.update("insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo, lease_dueno, lease_vence) "
                + "values (?, 'SIMULADOR', 1, ?, 'otra-instancia', '2030-01-01 00:00:00')", t.id(), FACTURA_B);
        String token = login(t.emailVendedor());
        String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));

        JsonNode primera = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isCreated()));
        long inicio = System.nanoTime();
        JsonNode repetida = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isOk()));

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(ESPERA.dividedBy(2));
        assertThat(primera.get("fiscal").get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(repetida.get("ventaId")).isEqualTo(primera.get("ventaId"));
        assertThat(repetida.get("fiscal").get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(repetida.get("fiscal").get("numero").isNull()).isTrue();
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    @DisplayName("un timeout después de autorizar responde EN_VERIFICACION; la venta siguiente de la serie adopta su CAE y sale con el número 2")
    void elTimeoutDespuesDeAutorizarLoRecuperaLaVentaSiguiente() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        String cuerpoPrimera = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);

        JsonNode primera = json(postJson("/api/ventas", token, cuerpoPrimera).andExpect(status().isCreated()));
        assertThat(primera.get("fiscal").get("estado").asText()).isEqualTo("EN_VERIFICACION");
        assertThat(primera.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(primera.get("fiscal").get("cae").isNull()).isTrue();

        JsonNode segunda = json(postJson("/api/ventas", token,
                cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))).andExpect(status().isCreated()));
        assertThat(segunda.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(segunda.get("fiscal").get("numero").asLong()).isEqualTo(2);

        JsonNode primeraRepetida = json(postJson("/api/ventas", token, cuerpoPrimera).andExpect(status().isOk()));
        assertThat(primeraRepetida.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(primeraRepetida.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(primeraRepetida.get("fiscal").get("cae").asText()).matches("\\d{14}")
                .isEqualTo(registroDe(primera.get("ventaId").asLong()).get("cae"));
        assertThat(wsfe.solicitudes()).isEqualTo(2);
    }

    @Test
    @DisplayName("con el simulador más lento que la espera, la venta responde a tiempo con 201 PENDIENTE o EN_CURSO (nunca 503) y después se autoriza")
    void conElSimuladorLentoRespondeATiempo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String token = login(t.emailVendedor());
        String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();

        long inicio = System.nanoTime();
        MockHttpServletResponse respuesta = postJson("/api/ventas", token, cuerpo).andReturn().getResponse();
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(respuesta.getStatus()).isEqualTo(201);
        assertThat(tardo).isBetween(ESPERA.minusMillis(100), ESPERA.plus(MARGEN));
        JsonNode venta = objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8));
        assertThat(venta.get("fiscal").get("estado").asText()).isIn("PENDIENTE", "EN_CURSO");
        pausa.esperarQueLlegue();
        pausa.soltar();
        long id = venta.get("ventaId").asLong();
        esperarHasta(() -> "AUTORIZADO".equals(estadoFiscal(id)));
        assertThat(registroDe(id)).containsEntry("numero", 1L);
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("con la serie OCUPADA por otra instancia que la suelta a mitad de la espera, la venta reintenta y responde 201 AUTORIZADO")
    void conLaSerieOcupadaQueSeLiberaResponde201Autorizado() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        jdbc.update("insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo, lease_dueno, lease_vence) "
                + "values (?, 'SIMULADOR', 1, ?, 'otra-instancia', '2030-01-01 00:00:00')", t.id(), FACTURA_B);
        String cuerpo = cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"));
        String token = login(t.emailVendedor());
        CompletableFuture<Void> soltar = CompletableFuture.runAsync(() -> {
            dormir(Duration.ofMillis(600));
            jdbc.update("update serie_fiscal set lease_dueno = null, lease_vence = null where ferreteria_id = ?", t.id());
        });

        long inicio = System.nanoTime();
        MockHttpServletResponse respuesta = postJson("/api/ventas", token, cuerpo).andReturn().getResponse();
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);
        soltar.join();

        assertThat(respuesta.getStatus()).isEqualTo(201);
        JsonNode venta = objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8));
        assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(venta.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(tardo).isBetween(Duration.ofMillis(500), ESPERA.plus(MARGEN));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("una excepción sin traducir del puerto de ARCA no sale de la venta: 201 con el registro EN_CURSO, nunca 5xx")
    void unaExcepcionSinTraducirDelPuertoResponde201() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        wsfe.lanzarEnElSiguienteFeCaeSolicitar(new IllegalStateException("Socket cerrado sin traducir (inyectada)."));

        MockHttpServletResponse respuesta = venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andReturn().getResponse();

        assertThat(respuesta.getStatus()).isEqualTo(201);
        JsonNode venta = objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8));
        assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("EN_CURSO");
        assertThat(estadoFiscal(venta.get("ventaId").asLong())).isEqualTo("EN_CURSO");
        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("ventas facturables a la vez (B a Consumidor Final y A a un RI): todas 201, números fiscales consecutivos por serie y sin deadlocks")
    void enParaleloNumerosConsecutivosSinDeadlocks() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "1000");
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), "Av. Colón 2000");
        String token = login(t.emailVendedor());
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pedidos.add(post(token, cuerpoDe(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))));
        }
        for (int i = 0; i < 2; i++) {
            pedidos.add(post(token, cuerpoDe(t, ri, null, clave(), "121.00", linea(a, "1", "121.00"))));
        }
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long antes = barrera.deadlocksAntes();

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(respuestas).extracting(Respuesta::status).containsOnly(201);
        assertThat(respuestas).allSatisfy(r -> assertThat(r.cuerpo().has("fiscal")).isTrue());
        esperarHasta(() -> jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ? "
                + "and estado = 'AUTORIZADO'", Long.class, t.id()) == 8);
        barrera.assertSinDeadlocks(antes);
        assertThat(numerosFiscales(t, "B")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(numerosFiscales(t, "A")).containsExactly(1L, 2L);
        assertThat(respuestas.stream().map(r -> r.cuerpo().get("numero").asLong()).sorted().toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(wsfe.solicitudes()).isEqualTo(8);
    }

    private List<Long> numerosFiscales(Tienda t, String letra) {
        return jdbc.queryForList("select numero from comprobante_fiscal where ferreteria_id = ? and letra = ? "
                + "order by numero", Long.class, t.id(), letra);
    }

    private Callable<Respuesta> post(String token, String cuerpo) {
        return () -> {
            MockHttpServletResponse resultado = postJson("/api/ventas", token, cuerpo).andReturn().getResponse();
            return new Respuesta(resultado.getStatus(),
                    objectMapper.readTree(resultado.getContentAsString(StandardCharsets.UTF_8)));
        };
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
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }
}
