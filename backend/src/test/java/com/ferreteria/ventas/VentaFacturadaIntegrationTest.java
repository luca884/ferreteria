package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-07 pasos 2, 3 y 5 con la facturación (cu-07-venta-facturada S1 b y c, S2, S3 y S5) contra MySQL real y el simulador:
 * la venta facturable de una ferretería con la facturación habilitada crea su registro fiscal en su transacción y responde
 * con el CAE; la que no factura responde como siempre, sin «fiscal»; los dos controles que dependen de la facturación (el
 * cliente sin datos fiscales y el tope de ARCA) no guardan nada; y la venta, sus movimientos y su registro llevan la misma
 * hora. La autorización después del commit (repetida, timeout, simulador lento y concurrencia) está en
 * {@link VentaFacturadaAutorizacionIntegrationTest}.
 */
class VentaFacturadaIntegrationTest extends VentaFacturadaTestBase {

    private static final String RI = "RESPONSABLE_INSCRIPTO";

    @Autowired
    ComprobanteFiscalRepository registros;

    // ---- S3 y S4: la venta facturada ----

    @Test
    @DisplayName("la venta facturable a Consumidor Final sale con Factura B autorizada: 201 con «fiscal», número 1 y CAE simulado")
    void facturaBAConsumidorFinal() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");

        JsonNode venta = json(venderA(t, null, null, clave(), "242.00", linea(a, "2", "121.00"))
                .andExpect(status().isCreated()));

        JsonNode fiscal = venta.get("fiscal");
        Map<String, Object> registro = registroDe(venta.get("ventaId").asLong());
        assertThat(fiscal.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(fiscal.get("clase").asText()).isEqualTo("FACTURA");
        assertThat(fiscal.get("letra").asText()).isEqualTo("B");
        assertThat(fiscal.get("puntoVenta").asInt()).isEqualTo(1);
        assertThat(fiscal.get("numero").asLong()).isEqualTo(1);
        assertThat(fiscal.get("cae").asText()).matches("\\d{14}").isEqualTo(registro.get("cae"));
        assertThat(fiscal.get("caeVencimiento").asText()).isEqualTo(registro.get("cae_vencimiento").toString());
        assertThat(fiscal.get("ambiente").asText()).isEqualTo("SIMULADOR");
        assertThat(registro).containsEntry("estado", "AUTORIZADO").containsEntry("ferreteria_id", t.id())
                .containsEntry("receptor_doc_tipo", 99).containsEntry("receptor_doc_nro", 0L);
        assertThat((BigDecimal) registro.get("imp_total")).isEqualByComparingTo("242.00");
        assertThat(venta.get("facturable").asBoolean()).isTrue();
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("a un Responsable Inscripto con CUIT y domicilio sale Factura A con su receptor, y lo que va a ARCA suma lo cobrado con descuento y recargo")
    void facturaAAUnResponsableInscripto() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long martillo = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long panDulce = articulo(t, "Pan dulce", "UNIDAD", "110.50", "10", "IVA_10_5", true);
        String cuit = cuitDeCliente();
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuit, "Av. Colón 2000");
        jdbc.update("update forma_pago set recargo_porcentaje = 10 where id = ?", formaDe(t, "CREDITO"));
        // 121,00 y 110,50 con el 10 % de descuento global: 108,90 + 99,45 = 208,35; con el 10 % de recargo (20,84), 229,19.
        Map<String, Object> cuerpo = mapa(clave(), "CREDITO", "229.19", linea(martillo, "1", "121.00"),
                linea(panDulce, "1", "110.50"));
        cuerpo.put("descuentoPorcentaje", new BigDecimal("10"));
        cuerpo.put("clienteId", ri);

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("229.19");
        assertThat(venta.get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(venta.get("fiscal").get("letra").asText()).isEqualTo("A");
        assertThat(venta.get("fiscal").get("numero").asLong()).isEqualTo(1);
        long id = venta.get("ventaId").asLong();
        Map<String, Object> registro = registroDe(id);
        assertThat(registro).containsEntry("receptor_doc_tipo", 80).containsEntry("receptor_doc_nro", Long.parseLong(cuit))
                .containsEntry("receptor_condicion_iva_id", 1).containsEntry("receptor_nombre", "Corralón Sur SA")
                .containsEntry("receptor_domicilio", "Av. Colón 2000");
        assertThat((BigDecimal) registro.get("imp_total")).isEqualByComparingTo("229.19");
        assertThat(jdbc.queryForList("select a.iva_id from comprobante_fiscal_alicuota a join comprobante_fiscal r "
                + "on r.id = a.comprobante_fiscal_id where r.comprobante_id = ? order by a.iva_id", Integer.class, id))
                .containsExactly(4, 5);
    }

    // ---- S2 (D5): sin facturación, la venta de siempre ----

    @Test
    @DisplayName("sin fila de ARCA o con la facturación apagada la venta es la de siempre: 201 sin «fiscal», sin registro y sin controlar al cliente")
    void sinFacturacionLaVentaEsLaDeSiempre() throws Exception {
        Tienda sinFila = tiendaConDatosFiscales(RI);
        Tienda apagada = tiendaQueFactura(RI);
        apagarFacturacion(apagada);

        for (Tienda t : List.of(sinFila, apagada)) {
            long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
            long riSinDomicilio = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), null);

            JsonNode anonima = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                    .andExpect(status().isCreated()));
            JsonNode alCliente = json(venderA(t, riSinDomicilio, null, clave(), "121.00", linea(a, "1", "121.00"))
                    .andExpect(status().isCreated()));

            assertSinFiscal(anonima);
            assertSinFiscal(alCliente);
            assertThat(anonima.get("facturable").asBoolean()).isTrue();
            assertThat(registrosFiscales(t)).isZero();
        }
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    @DisplayName("con la facturación habilitada la venta no facturable no crea registro ni controla al cliente")
    void laNoFacturableNoCreaRegistro() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long riSinDomicilio = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), null);

        JsonNode anonima = json(venderA(t, null, false, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        JsonNode alCliente = json(venderA(t, riSinDomicilio, false, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));

        assertSinFiscal(anonima);
        assertSinFiscal(alCliente);
        assertThat(anonima.get("facturable").asBoolean()).isFalse();
        assertThat(registrosFiscales(t)).isZero();
        assertThat(wsfe.solicitudes()).isZero();
    }

    // ---- S1 b (D4): el cliente sin datos fiscales ----

    @ParameterizedTest(name = "emisor {0}")
    @CsvSource({"RESPONSABLE_INSCRIPTO, B", "MONOTRIBUTISTA, C"})
    @DisplayName("un cliente RI sin domicilio o Monotributista sin CUIT es 422 CLIENTE_SIN_DATOS_FISCALES sin guardar nada, también si la ferretería emite C")
    void clienteSinDatosFiscales(String emisor, String letraDeLaSiguiente) throws Exception {
        Tienda t = tiendaQueFactura(emisor);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long riSinDomicilio = cliente(t, "Corralón Sur SA", RI, "CUIT", cuitDeCliente(), "   ");
        long monotributistaConDni = cliente(t, "Ana Gómez", "MONOTRIBUTISTA", "DNI", "30123456", "Güemes 3000");
        String clave = clave();

        for (long cliente : List.of(riSinDomicilio, monotributistaConDni)) {
            JsonNode rechazo = json(venderA(t, cliente, null, clave, "121.00", linea(a, "1", "121.00"))
                    .andExpect(status().is(422)));
            String nombre = cliente == riSinDomicilio ? "Corralón Sur SA" : "Ana Gómez";
            assertThat(rechazo.get("motivo").asText()).isEqualTo("CLIENTE_SIN_DATOS_FISCALES");
            assertThat(rechazo.get("message").asText())
                    .isEqualTo("Para facturarle a " + nombre + " falta su CUIT o su domicilio: completalos en Clientes.");
        }
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(registrosFiscales(t)).isZero();
        assertThat(wsfe.solicitudes()).isZero();

        // Ni la clave ni el número se gastaron: la misma clave a Consumidor Final es la venta N.º 1, con su factura.
        JsonNode siguiente = json(venderA(t, null, null, clave, "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));
        assertThat(siguiente.get("numero").asLong()).isEqualTo(1);
        assertThat(siguiente.get("fiscal").get("letra").asText()).isEqualTo(letraDeLaSiguiente);
        assertThat(siguiente.get("fiscal").get("numero").asLong()).isEqualTo(1);
    }

    // ---- S1 c (D4): el tope de ARCA ----

    @Test
    @DisplayName("con la facturación habilitada rige el menor entre el tope del Dueño y el de ARCA, con ese tope en el mensaje; sin facturación, solo el del Dueño")
    void elMenorEntreElTopeDelDuenoYElDeArca() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        jdbc.update("update ferreteria set tope_consumidor_final = 20000000.00 where id = ?", t.id());
        long a = articulo(t, "Autoelevador", "UNIDAD", "10000000.00", "10");
        String clave = clave();

        JsonNode rechazo = json(venderA(t, null, null, clave, "10000000.00", linea(a, "1", "10000000.00"))
                .andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CONSUMIDOR_FINAL_SIN_DATOS");
        assertThat(rechazo.get("message").asText()).isEqualTo("Desde $ 10.000.000,00 la venta a Consumidor Final necesita "
                + "identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");

        // Un tope del Dueño menor que el de ARCA sigue rigiendo, con su importe en el mensaje.
        jdbc.update("update ferreteria set tope_consumidor_final = 1000.00 where id = ?", t.id());
        long b = articulo(t, "Taladro", "UNIDAD", "1000.00", "10");
        assertThat(json(venderA(t, null, null, clave(), "1000.00", linea(b, "1", "1000.00"))
                .andExpect(status().is(422))).get("message").asText()).startsWith("Desde $ 1.000,00 la venta");

        // Sin la facturación, con el tope del Dueño en 20.000.000, la misma venta entra.
        jdbc.update("update ferreteria set tope_consumidor_final = 20000000.00 where id = ?", t.id());
        apagarFacturacion(t);
        JsonNode venta = json(venderA(t, null, null, clave, "10000000.00", linea(a, "1", "10000000.00"))
                .andExpect(status().isCreated()));
        assertSinFiscal(venta);
        assertThat(wsfe.solicitudes()).isZero();
    }

    // ---- S3: lo que va a ARCA se congela ----

    @Test
    @DisplayName("editar el cliente después de vender no cambia el receptor del registro fiscal")
    void editarElClienteNoCambiaElRegistro() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        String cuit = cuitDeCliente();
        long ri = cliente(t, "Corralón Sur SA", RI, "CUIT", cuit, "Av. Colón 2000");
        long id = json(venderA(t, ri, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();

        jdbc.update("update cliente set razon_social = 'Corralón Norte SRL', domicilio = 'Juan B. Justo 500', "
                + "condicion_iva = 'EXENTO' where id = ?", ri);

        assertThat(registroDe(id)).containsEntry("receptor_nombre", "Corralón Sur SA")
                .containsEntry("receptor_domicilio", "Av. Colón 2000").containsEntry("receptor_condicion_iva_id", 1)
                .containsEntry("receptor_doc_nro", Long.parseLong(cuit)).containsEntry("letra", "A");
    }

    @Test
    @DisplayName("otra ferretería no ve el registro: la misma clave en otra ferretería es otra venta, con su propia factura o sin ninguna")
    void otraFerreteriaNoVeElRegistro() throws Exception {
        Tienda primera = tiendaQueFactura(RI);
        Tienda otra = tiendaQueFactura(RI);
        Tienda sinFacturacion = tiendaConDatosFiscales(RI);
        String clave = clave();

        JsonNode dePrimera = json(venderA(primera, null, null, clave, "121.00",
                linea(articulo(primera, "Martillo", "UNIDAD", "121.00", "10"), "1", "121.00"))
                .andExpect(status().isCreated()));
        JsonNode deOtra = json(venderA(otra, null, null, clave, "121.00",
                linea(articulo(otra, "Martillo", "UNIDAD", "121.00", "10"), "1", "121.00"))
                .andExpect(status().isCreated()));
        JsonNode deSinFacturacion = json(venderA(sinFacturacion, null, null, clave, "121.00",
                linea(articulo(sinFacturacion, "Martillo", "UNIDAD", "121.00", "10"), "1", "121.00"))
                .andExpect(status().isCreated()));

        assertThat(deOtra.get("ventaId")).isNotEqualTo(dePrimera.get("ventaId"));
        assertThat(deOtra.get("fiscal").get("numero").asLong()).isEqualTo(1);
        assertThat(registroDe(dePrimera.get("ventaId").asLong())).containsEntry("ferreteria_id", primera.id());
        assertThat(registroDe(deOtra.get("ventaId").asLong())).containsEntry("ferreteria_id", otra.id());
        assertSinFiscal(deSinFacturacion);
        assertThat(registrosFiscales(sinFacturacion)).isZero();
    }

    // ---- S5: un solo instante ----

    @Test
    @DisplayName("la fecha de la venta, la de sus movimientos de stock y la del registro fiscal salen del mismo instante del reloj")
    void laVentaSusMovimientosYSuRegistroLlevanLaMismaHora() throws Exception {
        Instant instante = Instant.parse("2026-10-28T13:15:30.123456Z");
        reloj.fijar(instante);
        Tienda t = tiendaQueFactura(RI);
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        LocalDateTime enBuenosAires = LocalDateTime.ofInstant(instante, HoraArgentina.ZONA);

        JsonNode venta = json(venderA(t, null, null, clave(), "121.00", linea(a, "1", "121.00"))
                .andExpect(status().isCreated()));

        long id = venta.get("ventaId").asLong();
        String dueno = login(t.emailDueno());
        assertThat(LocalDateTime.parse(venta.get("fecha").asText())).isEqualTo(enBuenosAires);
        assertThat(LocalDateTime.parse(getJson("/api/ventas/" + id, dueno).get("fecha").asText())).isEqualTo(enBuenosAires);
        JsonNode movimientos = getJson("/api/articulos/" + a + "/movimientos-stock", dueno).get("items");
        assertThat(movimientos).hasSize(1);
        assertThat(LocalDateTime.parse(movimientos.get(0).get("fecha").asText())).isEqualTo(enBuenosAires);
        TenantContext.setCurrentFerreteriaId(t.id());
        try {
            assertThat(registros.deComprobanteScoped(id).orElseThrow().getRegistradoEn()).isEqualTo(instante);
        } finally {
            TenantContext.clear();
        }
    }
}
