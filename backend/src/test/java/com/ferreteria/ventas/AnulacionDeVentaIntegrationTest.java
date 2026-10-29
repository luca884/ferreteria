package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks.Largada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anular una venta (CU-08; cu-08-nota-de-credito T4, S2 a S6, S9, S13 y S15) por HTTP, contra MySQL real y el simulador:
 * {@code POST /api/ventas/{id}/anular}. Con la factura AUTORIZADO sale la nota de crédito con su letra, su asociado y su
 * CAE; sin CAE, la anulación interna con motivo. Las dos reponen el stock, no tocan la caja y son del Dueño.
 */
class AnulacionDeVentaIntegrationTest extends FacturaDelDuenoTestBase {

    private static final String MOTIVO = "El cliente devolvió todo";
    private static final String ESPERAR_CAE = "Esperá el CAE de la factura para anularla.";

    private ResultActions anular(String token, long venta, String cuerpo) throws Exception {
        return postJson("/api/ventas/" + venta + "/anular", token, cuerpo);
    }

    private ResultActions anular(Tienda t, long venta, String motivo) throws Exception {
        return anular(login(t.emailDueno()), venta, motivo == null ? "{}"
                : objectMapper.writeValueAsString(Map.of("motivo", motivo)));
    }

    private long notasDe(long venta) {
        return jdbc.queryForObject("select count(*) from comprobante where comprobante_asociado_id = ?", Long.class, venta);
    }

    /** Cobra $ 242 (2 × $ 121 del artículo) como el Vendedor, con ese cliente (o ninguno). */
    private long venderDos(Tienda t, long articulo, Long clienteId, Boolean facturable) throws Exception {
        return json(venderA(t, clienteId, facturable, clave(), "242.00", linea(articulo, "2", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
    }

    // ---- S3: la NC de una factura autorizada ----

    @ParameterizedTest(name = "factura {1}")
    @CsvSource({"RESPONSABLE_INSCRIPTO, A, 1, 3, true", "RESPONSABLE_INSCRIPTO, B, 6, 8, false",
            "MONOTRIBUTISTA, C, 11, 13, false"})
    @DisplayName("S3: la NC de una factura AUTORIZADO sale con su letra, su asociado y su CAE; repone el stock y la venta "
            + "queda «Anulada por la NC N.º n»")
    void laNcDeUnaFacturaAutorizada(String condicionTienda, String letra, int tipoFactura, int tipoNota,
            boolean conClienteRi) throws Exception {
        Tienda t = tiendaQueFactura(condicionTienda);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        Long cliente = conClienteRi ? cliente(t, unico("Corralón Sur SA"), RI, "CUIT", cuitDeCliente(), "Av. Colón 2000")
                : null;
        long venta = venderDos(t, a, cliente, null);
        assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
        assertThat(stock(a)).isEqualByComparingTo("8");

        JsonNode nota = json(anular(t, venta, null).andExpect(status().isCreated()));

        long notaId = nota.get("ventaId").asLong();
        assertThat(nota.get("tipo").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(nota.get("ventaAnulada").get("id").asLong()).isEqualTo(venta);
        assertThat(nota.get("total").decimalValue()).isEqualByComparingTo("242.00");
        assertThat(nota.get("pagos")).isEmpty();
        assertThat(nota.get("fiscal").get("clase").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(nota.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(nota.get("fiscal").get("letra").asText()).isEqualTo(letra);
        assertThat(nota.get("fiscal").get("documento").get("comprobanteAsociado").asText()).isNotBlank();
        assertThat(registroDe(notaId)).containsEntry("clase", "NOTA_CREDITO").containsEntry("cbte_tipo", tipoNota)
                .containsEntry("asociado_cbte_tipo", tipoFactura).containsEntry("asociado_numero", 1L);
        assertThat(jdbc.queryForMap("select caja_id, motivo_anulacion from comprobante where id = ?", notaId))
                .containsEntry("caja_id", null).containsEntry("motivo_anulacion", null);
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select cantidad from movimiento_stock where comprobante_id = ? and tipo = "
                + "'NOTA_CREDITO'", BigDecimal.class, notaId)).isEqualByComparingTo("2");

        JsonNode anulada = detalle(t, venta);
        assertThat(anulada.get("tipo").asText()).isEqualTo("VENTA");
        assertThat(anulada.get("anulacion").get("notaId").asLong()).isEqualTo(notaId);
        assertThat(anulada.get("anulacion").get("numero").asLong()).isEqualTo(nota.get("numero").asLong());
        assertThat(anulada.get("anulacion").get("interna").asBoolean()).isFalse();
        assertThat(anulada.get("anulacion").get("usuario").asText()).isEqualTo("Dueno Ventas");
    }

    @Test
    @DisplayName("S3: una factura de otro ambiente no admite NC: 409 OTRO_AMBIENTE y no se guarda nada")
    void unaFacturaDeOtroAmbienteNoAdmiteNc() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaAutorizada(t);
        jdbc.update("update config_arca set ambiente = 'HOMOLOGACION', de_prueba = false where ferreteria_id = ?", t.id());
        long antes = comprobantes(t);

        JsonNode rechazo = json(anular(t, venta, null).andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("OTRO_AMBIENTE");
        assertThat(comprobantes(t)).isEqualTo(antes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EN_CURSO", "EN_VERIFICACION", "CAE_NO_COINCIDE"})
    @DisplayName("S3, S9: con la factura en envío, en verificación o en revisión, 409 «Esperá el CAE de la factura para "
            + "anularla.» y no se guarda nada")
    void conUnEnvioAbiertoHayQueEsperarElCae(String estado) throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta;
        switch (estado) {
            case "EN_CURSO" -> {
                venta = ventaPendiente(t, t.emailVendedor());
                assertThat(jdbc.update("update comprobante_fiscal set estado = 'EN_CURSO', numero = 1, cbte_fch = "
                        + "'2026-10-28' where comprobante_id = ?", venta)).isOne();
            }
            case "EN_VERIFICACION" -> {
                wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);
                venta = cobrar(t);
                assertThat(estadoFiscal(venta)).isEqualTo("EN_VERIFICACION");
            }
            default -> {
                venta = ventaAutorizada(t);
                enRevision(venta);
            }
        }
        long antes = comprobantes(t);

        JsonNode rechazo = json(anular(t, venta, MOTIVO).andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("ESPERAR_CAE");
        assertThat(rechazo.get("message").asText()).isEqualTo(ESPERAR_CAE);
        assertThat(comprobantes(t)).isEqualTo(antes);
        assertThat(estadoFiscal(venta)).isEqualTo(estado);
    }

    // ---- S9, S13: la anulación interna ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"no facturable", "facturación apagada", "pendiente sin enviar", "rechazada"})
    @DisplayName("S9: una venta sin CAE se anula con la anulación interna: con motivo, sin documento fiscal, repone el stock "
            + "y su registro (si tiene) queda SIN_FACTURA")
    void unaVentaSinCaeSeAnulaPorDentro(String caso) throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = switch (caso) {
            case "no facturable" -> venderDos(t, a, null, false);
            case "facturación apagada" -> {
                apagarFacturacion(t);
                yield venderDos(t, a, null, null);
            }
            case "pendiente sin enviar" -> {
                wsfe.fallas().programar(Falla.NO_LLEGA);
                yield venderDos(t, a, null, null);
            }
            default -> {
                wsfe.fallas().rechazarConObservaciones(new Obs(10015, "El receptor no está identificado."));
                yield venderDos(t, a, null, null);
            }
        };
        String estadoAntes = estadoFiscal(venta);
        int pedidos = wsfe.solicitudes();

        JsonNode nota = json(anular(t, venta, "  " + MOTIVO + "  ").andExpect(status().isCreated()));

        long notaId = nota.get("ventaId").asLong();
        assertThat(nota.get("tipo").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(nota.has("fiscal")).as("la anulación interna no tiene documento fiscal").isFalse();
        assertThat(nota.get("facturable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select motivo_anulacion from comprobante where id = ?", String.class, notaId))
                .isEqualTo(MOTIVO);
        assertThat(registrosFiscalesDe(notaId)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(wsfe.solicitudes()).as("no se le pide nada a ARCA").isEqualTo(pedidos);
        if (estadoAntes != null) {
            assertThat(estadoFiscal(venta)).isEqualTo("SIN_FACTURA");
        }
        JsonNode anulacion = detalle(t, venta).get("anulacion");
        assertThat(anulacion.get("interna").asBoolean()).isTrue();
        assertThat(anulacion.get("motivo").asText()).isEqualTo(MOTIVO);
    }

    @Test
    @DisplayName("S13: la anulación interna pide el motivo (400 «Contá por qué se anula la venta.») de hasta 200 caracteres; "
            + "no se guarda nada")
    void laAnulacionInternaPideElMotivo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = venderDos(t, a, null, false);
        long antes = comprobantes(t);

        JsonNode sinMotivo = json(anular(t, venta, null).andExpect(status().isBadRequest()));
        JsonNode enBlanco = json(anular(t, venta, "   ").andExpect(status().isBadRequest()));
        JsonNode largo = json(anular(t, venta, "x".repeat(201)).andExpect(status().isBadRequest()));

        assertThat(sinMotivo.get("errors").get("motivo").asText()).isEqualTo("Contá por qué se anula la venta.");
        assertThat(enBlanco.get("errors").get("motivo").asText()).isEqualTo("Contá por qué se anula la venta.");
        assertThat(largo.get("errors").get("motivo").asText()).isEqualTo("El motivo tiene hasta 200 caracteres.");
        assertThat(comprobantes(t)).isEqualTo(antes);
        assertThat(stock(a)).isEqualByComparingTo("8");
        anular(t, venta, "x".repeat(200)).andExpect(status().isCreated());
    }

    // ---- S4, S15: una sola anulación por venta ----

    @Test
    @DisplayName("S4, S15: anular otra vez da 409 «La venta ya fue anulada por <usuario> el <fecha>.», y una NC no se "
            + "anula (409 «Una nota de crédito no se anula.»)")
    void unaVentaSeAnulaUnaSolaVez() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = venderDos(t, a, null, false);
        long nota = json(anular(t, venta, MOTIVO).andExpect(status().isCreated())).get("ventaId").asLong();

        JsonNode otraVez = json(anular(t, venta, MOTIVO).andExpect(status().isConflict()));
        JsonNode deLaNota = json(anular(t, nota, MOTIVO).andExpect(status().isConflict()));

        assertThat(otraVez.get("motivo").asText()).isEqualTo("VENTA_YA_ANULADA");
        assertThat(otraVez.get("message").asText())
                .isEqualTo("La venta ya fue anulada por Dueno Ventas el 28/10/2026 10:15.");
        assertThat(deLaNota.get("motivo").asText()).isEqualTo("NOTA_DE_CREDITO_NO_SE_ANULA");
        assertThat(deLaNota.get("message").asText()).isEqualTo("Una nota de crédito no se anula.");
        assertThat(notasDe(venta)).isOne();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("S4: dos anulaciones a la vez de la misma venta dan una 201 y una 409 con quién y cuándo; una sola NC y el "
            + "stock vuelve una sola vez")
    void dosAnulacionesALaVez() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        long venta = venderDos(t, a, null, null);
        assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
        String token = login(t.emailDueno());
        Callable<MvcResult> anulacion = () -> mvc.perform(MockMvcRequestBuilders.post("/api/ventas/" + venta + "/anular")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        List<MvcResult> respuestas = enParalelo(List.of(anulacion, anulacion));

        assertThat(respuestas).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        MvcResult conflicto = respuestas.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        JsonNode rechazo = objectMapper.readTree(conflicto.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("VENTA_YA_ANULADA");
        assertThat(rechazo.get("message").asText()).startsWith("La venta ya fue anulada por Dueno Ventas el ");
        assertThat(notasDe(venta)).isOne();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    // ---- S5: el stock, también lo del remito ----

    @Test
    @DisplayName("S5: después de la NC de una venta que salió de un remito, el stock vuelve a lo que había antes del remito")
    void laNcDeUnaVentaDeUnRemitoReponeTodo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "100");
        String vendedor = login(t.emailVendedor());
        // El remito saca 2; la venta del remito lleva 3: la diferencia (1) sale con la venta.
        long remitoId = remito(vendedor, a, "2", "121.00", "242.00").get("remitoId").asLong();
        Map<String, Object> desdeRemito = mapa(clave(), null, "363.00", linea(a, "3", "121.00"));
        desdeRemito.put("formaPagoId", formaDe(t, "EFECTIVO"));
        desdeRemito.put("remitoId", remitoId);
        long venta = json(postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(desdeRemito))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        assertThat(stock(a)).isEqualByComparingTo("97");

        anular(t, venta, null).andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select estado from remito where id = ?", String.class, remitoId))
                .as("S16: el remito sigue facturado").isEqualTo("FACTURADO");
    }

    // ---- S6: la plata ----

    @Test
    @DisplayName("S6: la caja no cambia con la NC")
    void laCajaNoCambia() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaAutorizada(t);
        String dueno = login(t.emailDueno());
        JsonNode cajaAntes = getJson("/api/cajas/actual", dueno);
        long pagosAntes = jdbc.queryForObject("select count(*) from pago where ferreteria_id = ?", Long.class, t.id());

        anular(t, venta, null).andExpect(status().isCreated());

        assertThat(getJson("/api/cajas/actual", dueno)).isEqualTo(cajaAntes);
        assertThat(jdbc.queryForObject("select count(*) from pago where ferreteria_id = ?", Long.class, t.id()))
                .isEqualTo(pagosAntes);
    }

    @Test
    @DisplayName("S6: una venta con un pago a cuenta corriente da 422 «La venta tiene un pago a cuenta corriente: todavía "
            + "no se puede anular.» sin guardar nada")
    void unaVentaACuentaCorrienteTodaviaNoSeAnula() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, unico("Martillo"), "UNIDAD", "121.00", "10");
        activarFormaCuentaCorriente(t);
        long ri = cliente(t, unico("Corralón Sur SA"), RI, "CUIT", cuitDeCliente(), "Av. Colón 2000");
        jdbc.update("update cliente set limite_cuenta_corriente = 1000 where id = ?", ri);
        Map<String, Object> aCuenta = mapa(clave(), null, "242.00", linea(a, "2", "121.00"));
        aCuenta.put("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"));
        aCuenta.put("clienteId", ri);
        long venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(aCuenta))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        long antes = comprobantes(t);

        JsonNode rechazo = json(anular(t, venta, MOTIVO).andExpect(status().isUnprocessableEntity()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("VENTA_A_CUENTA_CORRIENTE");
        assertThat(rechazo.get("message").asText())
                .isEqualTo("La venta tiene un pago a cuenta corriente: todavía no se puede anular.");
        assertThat(comprobantes(t)).isEqualTo(antes);
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(estadoFiscal(venta)).isEqualTo("AUTORIZADO");
    }

    // ---- S2: quién ----

    @Test
    @DisplayName("S2: el Vendedor recibe 403; la venta de otra ferretería y la que no existe, 404 «Venta no encontrada»")
    void soloElDuenoYSoloLoSuyo() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        Tienda otra = tiendaQueFactura(RI);
        long venta = ventaAutorizada(t);
        long antes = comprobantes(t);

        anular(login(t.emailVendedor()), venta, "{}").andExpect(status().isForbidden());
        JsonNode ajena = json(anular(login(otra.emailDueno()), venta, "{}").andExpect(status().isNotFound()));
        JsonNode inexistente = json(anular(login(t.emailDueno()), 999_999_999L, "{}").andExpect(status().isNotFound()));

        assertThat(ajena.get("message").asText()).isEqualTo(VENTA_NO_ENCONTRADA);
        assertThat(inexistente.get("message").asText()).isEqualTo(VENTA_NO_ENCONTRADA);
        assertThat(comprobantes(t)).isEqualTo(antes);
        assertThat(notasDe(venta)).isZero();
    }

    // ---- S3: la NC rechazada se reenvía ----

    @Test
    @DisplayName("S3: una NC que ARCA rechaza sigue siendo la única: «Reintentar» la reenvía sin crear otra, y «Dejar sin "
            + "factura» no vale sobre una NC")
    void unaNcRechazadaSeReintenta() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaAutorizada(t);
        wsfe.fallas().rechazarConObservaciones(new Obs(10015, "El receptor no está identificado."));
        JsonNode nota = json(anular(t, venta, null).andExpect(status().isCreated()));
        long notaId = nota.get("ventaId").asLong();
        assertThat(nota.get("fiscal").get("estado").asText()).isEqualTo("RECHAZADO");
        assertThat(nota.get("fiscal").get("acciones")).extracting(JsonNode::asText).containsExactly("REINTENTAR");
        String dueno = login(t.emailDueno());

        JsonNode reintento = json(reintentar(dueno, notaId, "{}").andExpect(status().isOk()));

        assertThat(reintento.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(reintento.get("clase").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(notasDe(venta)).isOne();
        assertThat(registrosFiscalesDe(notaId)).isEqualTo(2);
        sinFactura(dueno, notaId, "{\"motivo\":\"" + MOTIVO + "\"}").andExpect(status().isNotFound());
    }

    private long registrosFiscalesDe(long comprobanteId) {
        return jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ?", Long.class,
                comprobanteId);
    }

    /** Los pedidos con largada común (todos listos antes de que arranque el primero), en el orden en que se pasaron. */
    private static List<MvcResult> enParalelo(List<Callable<MvcResult>> trabajos) throws Exception {
        Largada largada = new Largada(trabajos.size());
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (Callable<MvcResult> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return trabajo.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<MvcResult> respuestas = new ArrayList<>();
            for (Future<MvcResult> futuro : futuros) {
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }
}
