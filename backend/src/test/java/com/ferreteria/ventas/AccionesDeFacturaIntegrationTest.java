package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Reintentar» y «Dejar sin factura» del Dueño (cu-07-contingencias S5, C2 y C3; CU-16 punto 7), por HTTP contra MySQL
 * real y el simulador: el reintento vuelve a leer el cliente y la ferretería (la letra puede cambiar) y espera el CAE como
 * el cobro; la fecha nueva solo confirmada; cada rechazo con su código en {@code motivo} y su texto exacto, sin cambiar
 * nada; «sin factura» con motivo, quién y cuándo. Solo del Dueño; otra ferretería, 404.
 */
class AccionesDeFacturaIntegrationTest extends FacturaDelDuenoTestBase {

    static final String SIN_FACTURA_RECHAZADA = "Esta venta no tiene una factura rechazada para reintentar.";
    static final String FECHA_NUEVA_REQUERIDA =
            "La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.";

    // ---- Reintentar ----

    @Test
    @DisplayName("con el cliente corregido, el reintento arma la factura con la copia de hoy (la letra pasa de B a A), "
            + "espera el CAE como el cobro y deja la rechazada como historia, con sus errores")
    void reintentarConElClienteCorregidoCambiaLaLetra() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long cliente = cliente(t, "Ana Gómez", "CONSUMIDOR_FINAL", "DNI", "30123456", null);
        long venta = ventaRechazada(t, cliente);
        String cuit = cuitDeCliente();
        jdbc.update("update cliente set condicion_iva = 'RESPONSABLE_INSCRIPTO', tipo_documento = 'CUIT', "
                + "numero_documento = ?, domicilio = 'Av. Colón 2000, Mar del Plata' where id = ?", cuit, cliente);

        JsonNode fiscal = json(reintentar(login(t.emailDueno()), venta, "{\"fechaDeHoy\": false}")
                .andExpect(status().isOk()));

        assertThat(fiscal.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(fiscal.get("letra").asText()).isEqualTo("A");
        assertThat(fiscal.get("documento").get("receptor").get("numeroDocumento").asText()).isEqualTo(cuit);
        assertThat(fiscal.get("acciones")).isEmpty();
        List<Map<String, Object>> registros = registrosDe(venta);
        assertThat(registros).hasSize(2);
        assertThat(registros.get(0)).containsEntry("estado", "RECHAZADO").containsEntry("letra", "B");
        assertThat(registros.get(0).get("reemplazado_en")).isNotNull();
        assertThat(registros.get(0).get("observaciones").toString()).contains("10015");
        assertThat(registros.get(1)).containsEntry("estado", "AUTORIZADO").containsEntry("letra", "A")
                .containsEntry("cbte_tipo", 1).containsEntry("receptor_doc_nro", Long.parseLong(cuit))
                .containsEntry("reemplazado_en", null);
        assertThat(detalle(t, venta).get("fiscal").get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(ventaIds(getJson("/api/ventas?fiscal=RECHAZADAS", login(t.emailDueno())))).doesNotContain(venta);
    }

    @Test
    @DisplayName("si la fecha ya no entra, sin confirmar (o sin cuerpo) es 409 FECHA_NUEVA_REQUERIDA y no cambia nada; "
            + "confirmada, la factura sale con la fecha de hoy")
    void laFechaNuevaSoloConfirmada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        reloj.avanzar(Duration.ofDays(6));
        String dueno = login(t.emailDueno());
        List<Map<String, Object>> antes = registrosDe(venta);

        for (String cuerpo : List.of("{\"fechaDeHoy\": false}", "{}")) {
            JsonNode rechazo = json(reintentar(dueno, venta, cuerpo).andExpect(status().isConflict()));
            assertThat(rechazo.get("motivo").asText()).isEqualTo("FECHA_NUEVA_REQUERIDA");
            assertThat(rechazo.get("message").asText()).isEqualTo(FECHA_NUEVA_REQUERIDA);
            assertThat(rechazo.get("status").asInt()).isEqualTo(409);
        }
        assertThat(registrosDe(venta)).isEqualTo(antes);

        JsonNode fiscal = json(reintentar(dueno, venta, "{\"fechaDeHoy\": true}").andExpect(status().isOk()));

        assertThat(fiscal.get("estado").asText()).isEqualTo("AUTORIZADO");
        assertThat(registrosDe(venta).get(1)).containsEntry("estado", "AUTORIZADO")
                .containsEntry("cbte_fch", Date.valueOf(LocalDate.of(2026, 11, 3)));
    }

    @Test
    @DisplayName("sin una rechazada (autorizada, pendiente, sin registro o ya sin factura) es 409 SIN_FACTURA_RECHAZADA")
    void sinUnaRechazadaParaReintentar() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long autorizada = ventaAutorizada(t);
        long sinFactura = ventaRechazada(t);
        sinFactura(sinFactura, "Se fue", usuario(t.emailDueno()), reloj.instant());
        long sinRegistro = json(venderA(t, null, false, clave(), "121.00",
                linea(articulo(t, "Clavo", "UNIDAD", "121.00", "10"), "1", "121.00")).andExpect(status().isCreated()))
                .get("ventaId").asLong();
        long pendiente = ventaPendiente(t, t.emailVendedor());
        String dueno = login(t.emailDueno());

        for (long venta : List.of(autorizada, sinFactura, sinRegistro, pendiente)) {
            JsonNode rechazo = json(reintentar(dueno, venta, "{\"fechaDeHoy\": true}").andExpect(status().isConflict()));
            assertThat(rechazo.get("motivo").asText()).isEqualTo("SIN_FACTURA_RECHAZADA");
            assertThat(rechazo.get("message").asText()).isEqualTo(SIN_FACTURA_RECHAZADA);
        }
        assertThat(registrosFiscales(t)).isEqualTo(3);
    }

    @Test
    @DisplayName("con la facturación frenada es 409 FACTURACION_FRENADA «La facturación está frenada: <motivo>.» y no cambia nada")
    void conLaFacturacionFrenada() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        apagarFacturacion(t);
        List<Map<String, Object>> antes = registrosDe(venta);

        JsonNode rechazo = json(reintentar(login(t.emailDueno()), venta, "{\"fechaDeHoy\": true}")
                .andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("FACTURACION_FRENADA");
        assertThat(rechazo.get("message").asText()).isEqualTo("La facturación está frenada: no está habilitada en esta "
                + "ferretería (la habilita el super admin).");
        assertThat(registrosDe(venta)).isEqualTo(antes);
    }

    @Test
    @DisplayName("si el cliente de hoy no cumple D4 es el mismo 422 que el cobro, y no cambia nada")
    void elClienteDeHoyNoCumpleD4() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long cliente = cliente(t, "Corralón Sur SA", "CONSUMIDOR_FINAL", "DNI", "30123456", null);
        long conCliente = ventaRechazada(t, cliente);
        long anonima = ventaRechazada(t);
        jdbc.update("update cliente set condicion_iva = 'RESPONSABLE_INSCRIPTO', tipo_documento = 'CUIT', "
                + "numero_documento = ?, domicilio = null where id = ?", cuitDeCliente(), cliente);
        jdbc.update("update ferreteria set tope_consumidor_final = 100.00 where id = ?", t.id());
        String dueno = login(t.emailDueno());
        long antes = registrosFiscales(t);

        JsonNode sinDatos = json(reintentar(dueno, conCliente, "{\"fechaDeHoy\": false}").andExpect(status().is(422)));
        JsonNode sinIdentificar = json(reintentar(dueno, anonima, "{\"fechaDeHoy\": false}").andExpect(status().is(422)));

        assertThat(sinDatos.get("motivo").asText()).isEqualTo("CLIENTE_SIN_DATOS_FISCALES");
        assertThat(sinDatos.get("message").asText())
                .isEqualTo("Para facturarle a Corralón Sur SA falta su CUIT o su domicilio: completalos en Clientes.");
        assertThat(sinIdentificar.get("motivo").asText()).isEqualTo("CONSUMIDOR_FINAL_SIN_DATOS");
        assertThat(sinIdentificar.get("message").asText()).isEqualTo("Desde $ 100,00 la venta a Consumidor Final "
                + "necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.");
        assertThat(registrosFiscales(t)).isEqualTo(antes);
        assertThat(estadoFiscal(conCliente)).isEqualTo("RECHAZADO");
    }

    // ---- Dejar sin factura ----

    @Test
    @DisplayName("«Dejar sin factura» responde el resumen fiscal SIN_FACTURA con el motivo, quién y cuándo; sale de la "
            + "bandeja y ya no se puede reintentar")
    void dejarSinFactura() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        String dueno = login(t.emailDueno());

        JsonNode fiscal = json(sinFactura(dueno, venta, "{\"motivo\": \"  El cliente no volvió  \"}")
                .andExpect(status().isOk()));

        assertThat(fiscal.get("estado").asText()).isEqualTo("SIN_FACTURA");
        assertThat(fiscal.get("motivoSinFactura").asText()).isEqualTo("El cliente no volvió");
        assertThat(fiscal.get("sinFacturaPor").asText()).isEqualTo("Dueno Ventas");
        assertThat(fiscal.get("sinFacturaEn").asText()).isEqualTo("2026-10-28T10:15:30");
        assertThat(fiscal.get("acciones")).isEmpty();
        assertThat(ventaIds(getJson("/api/ventas?fiscal=RECHAZADAS", dueno))).doesNotContain(venta);
        assertThat(detalle(t, venta).get("fiscal").get("motivoSinFactura").asText()).isEqualTo("El cliente no volvió");
        JsonNode rechazo = json(reintentar(dueno, venta, "{\"fechaDeHoy\": true}").andExpect(status().isConflict()));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("SIN_FACTURA_RECHAZADA");
        JsonNode otraVez = json(sinFactura(dueno, venta, "{\"motivo\": \"Otra vez\"}").andExpect(status().isConflict()));
        assertThat(otraVez.get("motivo").asText()).isEqualTo("SIN_FACTURA_RECHAZADA");
        assertThat(otraVez.get("message").asText()).isEqualTo(SIN_FACTURA_RECHAZADA);
    }

    @Test
    @DisplayName("el motivo es obligatorio y de hasta 200 caracteres (400 en «motivo»); sin una rechazada es 409; "
            + "nada cambia")
    void losRechazosDeDejarSinFactura() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long rechazada = ventaRechazada(t);
        long autorizada = ventaAutorizada(t);
        String dueno = login(t.emailDueno());

        for (String cuerpo : List.of("{\"motivo\": \"   \"}", "{}", "{\"motivo\": null}")) {
            JsonNode error = json(sinFactura(dueno, rechazada, cuerpo).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("motivo").asText()).isEqualTo("Contá por qué la venta queda sin factura.");
        }
        JsonNode largo = json(sinFactura(dueno, rechazada, "{\"motivo\": \"" + "x".repeat(201) + "\"}")
                .andExpect(status().isBadRequest()));
        assertThat(largo.get("errors").get("motivo").asText()).isEqualTo("El motivo tiene hasta 200 caracteres.");
        JsonNode conflicto = json(sinFactura(dueno, autorizada, "{\"motivo\": \"No\"}").andExpect(status().isConflict()));
        assertThat(conflicto.get("motivo").asText()).isEqualTo("SIN_FACTURA_RECHAZADA");
        assertThat(estadoFiscal(rechazada)).isEqualTo("RECHAZADO");
        assertThat(estadoFiscal(autorizada)).isEqualTo("AUTORIZADO");
    }

    // ---- Roles y aislamiento ----

    @Test
    @DisplayName("las dos acciones son solo del Dueño: el Vendedor y el super admin reciben 403 y no cambia nada")
    void soloElDueno() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        List<Map<String, Object>> antes = registrosDe(venta);

        for (String token : List.of(login(t.emailVendedor()), superAdmin())) {
            reintentar(token, venta, "{\"fechaDeHoy\": true}").andExpect(status().isForbidden());
            sinFactura(token, venta, "{\"motivo\": \"No\"}").andExpect(status().isForbidden());
        }
        assertThat(registrosDe(venta)).isEqualTo(antes);
    }

    @Test
    @DisplayName("una venta de otra ferretería o que no existe es 404 «Venta no encontrada» en las dos acciones")
    void otraFerreteriaEs404() throws Exception {
        Tienda t = tiendaQueFactura(RI);
        long venta = ventaRechazada(t);
        Tienda otra = tiendaQueFactura(RI);
        String duenoDeLaOtra = login(otra.emailDueno());

        for (long id : List.of(venta, 999_999_999L)) {
            assertThat(json(reintentar(duenoDeLaOtra, id, "{\"fechaDeHoy\": true}").andExpect(status().isNotFound()))
                    .get("message").asText()).isEqualTo(VENTA_NO_ENCONTRADA);
            assertThat(json(sinFactura(duenoDeLaOtra, id, "{\"motivo\": \"No\"}").andExpect(status().isNotFound()))
                    .get("message").asText()).isEqualTo(VENTA_NO_ENCONTRADA);
        }
        assertThat(estadoFiscal(venta)).isEqualTo("RECHAZADO");
    }
}
