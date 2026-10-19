package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-10 Caja diaria: contrato HTTP de {@code /api/cajas} y sus reglas contra MySQL real. */
class CajaIntegrationTest extends VentasTestBase {

    private String abrirCon(String importe) {
        return "{\"efectivoInicial\": " + importe + "}";
    }

    private String movimiento(String tipo, String importe, String motivo) throws Exception {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("tipo", tipo);
        m.put("importe", importe == null ? null : new BigDecimal(importe));
        m.put("motivo", motivo);
        return objectMapper.writeValueAsString(m);
    }

    private String cierre(String contado) {
        return "{\"efectivoContado\": " + contado + "}";
    }

    private long abrir(String token, String importe) throws Exception {
        return json(postJson("/api/cajas", token, abrirCon(importe)).andExpect(status().isCreated())).get("id").asLong();
    }

    private void venderPor(String token, long articulo, String precio, String medio) throws Exception {
        postJson("/api/ventas", token, cuerpo(clave(), medio, precio, linea(articulo, "1", precio)))
                .andExpect(status().isCreated());
    }

    private long cajas(Tienda t) {
        return jdbc.queryForObject("select count(*) from caja where ferreteria_id = ?", Long.class, t.id());
    }

    private long movimientosDeCaja(Tienda t) {
        return jdbc.queryForObject("select count(*) from movimiento_caja where ferreteria_id = ?", Long.class, t.id());
    }

    // ---------------------------------------------------------------- apertura

    @Nested
    class Apertura {

        @Test
        @DisplayName("sin caja: estado SIN_CAJA y propone $ 0 si nunca hubo un cierre")
        void sinCaja() throws Exception {
            Tienda t = tiendaSinCaja();

            JsonNode actual = getJson("/api/cajas/actual", login(t.emailVendedor()));

            assertThat(actual.get("estado").asText()).isEqualTo("SIN_CAJA");
            assertThat(actual.get("caja").isNull()).isTrue();
            assertThat(actual.get("efectivoPropuesto").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("el vendedor abre la caja: 201, queda ABIERTA con su nombre y sin diferencia de apertura")
        void vendedorAbre() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());

            JsonNode caja = json(postJson("/api/cajas", vendedor, abrirCon("5000.00")).andExpect(status().isCreated()));

            assertThat(caja.get("estado").asText()).isEqualTo("ABIERTA");
            assertThat(caja.get("abiertaPor").asText()).isEqualTo("Vendedor Ventas");
            assertThat(caja.get("efectivoInicial").decimalValue()).isEqualByComparingTo("5000");
            JsonNode actual = getJson("/api/cajas/actual", vendedor);
            assertThat(actual.get("estado").asText()).isEqualTo("ABIERTA");
            assertThat(actual.get("caja").get("id").asLong()).isEqualTo(caja.get("id").asLong());
            assertThat(jdbc.queryForObject("select diferencia_apertura from caja where id = ?", BigDecimal.class,
                    caja.get("id").asLong())).isNull();
        }

        @Test
        @DisplayName("con una caja abierta (de cualquier usuario) abrir otra da 409 «Ya hay una caja abierta.»")
        void unaSolaAbierta() throws Exception {
            Tienda t = tiendaSinCaja();
            abrir(login(t.emailVendedor()), "100");

            JsonNode error = json(postJson("/api/cajas", login(t.emailDueno()), abrirCon("100"))
                    .andExpect(status().isConflict()));

            assertThat(error.get("message").asText()).isEqualTo("Ya hay una caja abierta.");
            assertThat(cajas(t)).isEqualTo(1);
        }

        @Test
        @DisplayName("la base no deja dos cajas abiertas en la misma ferreteria (UNIQUE ferreteria_id, abierta)")
        void laBaseLoGarantiza() {
            Tienda t = tiendaSinCaja();
            cajaAbiertaHoy(t.id());

            assertThatThrownBy(() -> cajaAbiertaEl(t.id(), LocalDateTime.now(HoraArgentina.ZONA)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("efectivo inicial negativo, faltante o con 3 decimales da 400 con el mensaje en el campo")
        void efectivoInvalido() throws Exception {
            Tienda t = tiendaSinCaja();
            String token = login(t.emailVendedor());

            JsonNode negativo = json(postJson("/api/cajas", token, abrirCon("-1")).andExpect(status().isBadRequest()));
            JsonNode falta = json(postJson("/api/cajas", token, "{}").andExpect(status().isBadRequest()));
            JsonNode decimales = json(postJson("/api/cajas", token, abrirCon("1.001")).andExpect(status().isBadRequest()));

            assertThat(negativo.get("errors").get("efectivoInicial").asText())
                    .isEqualTo("El efectivo inicial no puede ser negativo.");
            assertThat(falta.get("errors").get("efectivoInicial").asText()).isEqualTo("Cargá el efectivo inicial.");
            assertThat(decimales.get("errors").get("efectivoInicial").asText())
                    .isEqualTo("El efectivo inicial admite hasta 2 decimales.");
            assertThat(cajas(t)).isZero();
        }

        @Test
        @DisplayName("propone el contado del ultimo cierre; abrir con otro monto guarda la diferencia y quien abrio")
        void proponeElUltimoContado() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long primera = abrir(vendedor, "1000");
            postJson("/api/cajas/" + primera + "/cierre", vendedor, cierre("7000.00")).andExpect(status().isOk());

            assertThat(getJson("/api/cajas/actual", vendedor).get("efectivoPropuesto").decimalValue())
                    .isEqualByComparingTo("7000");

            long segunda = abrir(login(t.emailDueno()), "6500.00");
            var fila = jdbc.queryForMap("select efectivo_cierre_anterior, diferencia_apertura, usuario_apertura_id "
                    + "from caja where id = ?", segunda);
            assertThat((BigDecimal) fila.get("efectivo_cierre_anterior")).isEqualByComparingTo("7000");
            assertThat((BigDecimal) fila.get("diferencia_apertura")).isEqualByComparingTo("-500");
            assertThat(fila.get("usuario_apertura_id")).isEqualTo(jdbc.queryForObject(
                    "select id from usuario where email = ?", Long.class, t.emailDueno()));
        }
    }

    // ---------------------------------------------------------------- movimientos

    @Nested
    class Movimientos {

        @Test
        @DisplayName("el vendedor carga ingresos y gastos: 201 con el movimiento")
        void vendedorCargaIngresoYGasto() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");

            JsonNode ingreso = json(postJson("/api/cajas/" + caja + "/movimientos", vendedor,
                    movimiento("INGRESO", "300.00", "Cambio")).andExpect(status().isCreated()));
            postJson("/api/cajas/" + caja + "/movimientos", vendedor, movimiento("GASTO", "150", "Cadete"))
                    .andExpect(status().isCreated());

            assertThat(ingreso.get("tipo").asText()).isEqualTo("INGRESO");
            assertThat(ingreso.get("importe").decimalValue()).isEqualByComparingTo("300");
            assertThat(ingreso.get("motivo").asText()).isEqualTo("Cambio");
            assertThat(ingreso.get("usuario").asText()).isEqualTo("Vendedor Ventas");
            assertThat(movimientosDeCaja(t)).isEqualTo(2);
        }

        @Test
        @DisplayName("el retiro es solo del dueño: el vendedor recibe 403 y no se guarda nada")
        void retiroSoloDueno() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");

            postJson("/api/cajas/" + caja + "/movimientos", vendedor, movimiento("RETIRO", "100", "Me llevo"))
                    .andExpect(status().isForbidden());
            assertThat(movimientosDeCaja(t)).isZero();

            postJson("/api/cajas/" + caja + "/movimientos", login(t.emailDueno()), movimiento("RETIRO", "100", "Banco"))
                    .andExpect(status().isCreated());
            assertThat(movimientosDeCaja(t)).isEqualTo(1);
        }

        @Test
        @DisplayName("importe cero o sin motivo da 400 con un mensaje por campo")
        void datosInvalidos() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");

            JsonNode error = json(postJson("/api/cajas/" + caja + "/movimientos", vendedor,
                    movimiento("GASTO", "0", " ")).andExpect(status().isBadRequest()));

            assertThat(error.get("errors").get("importe").asText()).isEqualTo("El importe tiene que ser mayor que cero.");
            assertThat(error.get("errors").get("motivo").asText()).isEqualTo("Escribí el motivo del movimiento.");
            assertThat(movimientosDeCaja(t)).isZero();
        }

        @Test
        @DisplayName("un tipo que no existe da 400 con el mensaje del campo")
        void tipoInvalido() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");

            JsonNode error = json(postJson("/api/cajas/" + caja + "/movimientos", vendedor,
                    movimiento("PRESTAMO", "10", "x")).andExpect(status().isBadRequest()));

            assertThat(error.get("errors").get("tipo").asText())
                    .isEqualTo("El tipo de movimiento no es válido. Usá INGRESO, GASTO o RETIRO.");
        }

        @Test
        @DisplayName("una caja cerrada no admite movimientos: 422")
        void cajaCerrada() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");
            postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("0")).andExpect(status().isOk());

            JsonNode error = json(postJson("/api/cajas/" + caja + "/movimientos", vendedor,
                    movimiento("INGRESO", "10", "Cambio")).andExpect(status().isUnprocessableEntity()));

            assertThat(error.get("message").asText()).isEqualTo("La caja está cerrada: no admite movimientos.");
            assertThat(movimientosDeCaja(t)).isZero();
        }

        @Test
        @DisplayName("la caja abierta de otro dia no admite movimientos: 422 con la fecha de la caja")
        void cajaDeOtroDia() throws Exception {
            Tienda t = tiendaSinCaja();
            LocalDateTime ayer = LocalDateTime.now(HoraArgentina.ZONA).minusDays(1);
            long caja = cajaAbiertaEl(t.id(), ayer);

            JsonNode error = json(postJson("/api/cajas/" + caja + "/movimientos", login(t.emailVendedor()),
                    movimiento("INGRESO", "10", "Cambio")).andExpect(status().isUnprocessableEntity()));

            assertThat(error.get("message").asText()).isEqualTo("La caja del "
                    + ayer.format(DateTimeFormatter.ofPattern("dd/MM")) + " sigue abierta. Cerrala antes de cargar movimientos.");
        }
    }

    // ---------------------------------------------------------------- cierre ciego

    @Nested
    class Cierre {

        @Test
        @DisplayName("cierre ciego: manda solo el contado y recien la respuesta trae totales, esperado y diferencia")
        void cierreCiego() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            String dueno = login(t.emailDueno());
            long caja = abrir(vendedor, "5000.00");
            long a = articulo(t, "Martillo", "UNIDAD", "1000.00", "50");
            long b = articulo(t, "Pinza", "UNIDAD", "800.00", "50");
            venderPor(vendedor, a, "1000.00", "EFECTIVO");
            venderPor(vendedor, b, "800.00", "EFECTIVO");
            venderPor(vendedor, a, "1000.00", "DEBITO");
            venderPor(vendedor, b, "800.00", "CREDITO");
            venderPor(dueno, a, "1000.00", "TRANSFERENCIA");
            postJson("/api/cajas/" + caja + "/movimientos", vendedor, movimiento("INGRESO", "200", "Cambio"))
                    .andExpect(status().isCreated());
            postJson("/api/cajas/" + caja + "/movimientos", vendedor, movimiento("GASTO", "300", "Cadete"))
                    .andExpect(status().isCreated());
            postJson("/api/cajas/" + caja + "/movimientos", dueno, movimiento("RETIRO", "1000", "Banco"))
                    .andExpect(status().isCreated());

            JsonNode resumen = json(postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("5650.00"))
                    .andExpect(status().isOk()));

            assertThat(resumen.get("estado").asText()).isEqualTo("CERRADA");
            assertThat(resumen.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("1800");
            assertThat(resumen.get("ventas").get("DEBITO").decimalValue()).isEqualByComparingTo("1000");
            assertThat(resumen.get("ventas").get("CREDITO").decimalValue()).isEqualByComparingTo("800");
            assertThat(resumen.get("ventas").get("TRANSFERENCIA").decimalValue()).isEqualByComparingTo("1000");
            assertThat(resumen.get("totalVentas").decimalValue()).isEqualByComparingTo("4600");
            assertThat(resumen.get("ingresos").decimalValue()).isEqualByComparingTo("200");
            assertThat(resumen.get("gastos").decimalValue()).isEqualByComparingTo("300");
            assertThat(resumen.get("retiros").decimalValue()).isEqualByComparingTo("1000");
            // 5000 + 1800 + 200 - 300 - 1000
            assertThat(resumen.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("5700");
            assertThat(resumen.get("efectivoContado").decimalValue()).isEqualByComparingTo("5650");
            assertThat(resumen.get("diferencia").decimalValue()).isEqualByComparingTo("-50");
            assertThat(resumen.get("cerradaPor").asText()).isEqualTo("Vendedor Ventas");
            assertThat(resumen.get("movimientos")).hasSize(3);
            var fila = jdbc.queryForMap("select abierta, efectivo_esperado, diferencia from caja where id = ?", caja);
            assertThat(fila.get("abierta")).isNull();
            assertThat((BigDecimal) fila.get("efectivo_esperado")).isEqualByComparingTo("5700");
            assertThat((BigDecimal) fila.get("diferencia")).isEqualByComparingTo("-50");
        }

        @Test
        @DisplayName("mientras la caja esta abierta el vendedor no ve lo esperado ni los totales; el dueño si, en vivo")
        void elVendedorNoVeLoEsperado() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "100");
            long a = articulo(t, "Martillo", "UNIDAD", "1000.00", "50");
            venderPor(vendedor, a, "1000.00", "EFECTIVO");

            JsonNode actual = getJson("/api/cajas/actual", vendedor);
            assertThat(actual.get("caja").has("efectivoEsperado")).isFalse();
            assertThat(actual.get("caja").has("ventas")).isFalse();
            assertThat(actual.get("caja").has("totalVentas")).isFalse();
            getResult("/api/cajas/" + caja, vendedor).andExpect(status().isForbidden());

            JsonNode enVivo = getJson("/api/cajas/" + caja, login(t.emailDueno()));
            assertThat(enVivo.get("estado").asText()).isEqualTo("ABIERTA");
            assertThat(enVivo.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("1000");
            assertThat(enVivo.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("1100");
            assertThat(enVivo.get("efectivoContado").isNull()).isTrue();
            assertThat(enVivo.get("diferencia").isNull()).isTrue();
        }

        @Test
        @DisplayName("una caja cerrada es inmutable: cerrarla de nuevo da 409 y no cambia el contado")
        void noSeCierraDosVeces() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");
            postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("10")).andExpect(status().isOk());

            JsonNode error = json(postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("20"))
                    .andExpect(status().isConflict()));

            assertThat(error.get("message").asText()).isEqualTo("Esta caja ya está cerrada.");
            assertThat(jdbc.queryForObject("select efectivo_contado from caja where id = ?", BigDecimal.class, caja))
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("despues del cierre no se vende hasta abrir otra caja")
        void cerradaNoAdmiteVentas() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");
            long a = articulo(t, "Martillo", "UNIDAD", "1000.00", "50");
            postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("0")).andExpect(status().isOk());

            JsonNode error = json(postJson("/api/ventas", vendedor, cuerpo(clave(), "EFECTIVO", "1000.00",
                    linea(a, "1", "1000.00"))).andExpect(status().isUnprocessableEntity()));

            assertThat(error.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
            assertThat(getJson("/api/cajas/actual", vendedor).get("estado").asText()).isEqualTo("SIN_CAJA");
        }

        @Test
        @DisplayName("contado negativo o faltante da 400 y la caja sigue abierta")
        void contadoInvalido() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "0");

            JsonNode error = json(postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("-5"))
                    .andExpect(status().isBadRequest()));
            postJson("/api/cajas/" + caja + "/cierre", vendedor, "{}").andExpect(status().isBadRequest());

            assertThat(error.get("errors").get("efectivoContado").asText())
                    .isEqualTo("El efectivo contado no puede ser negativo.");
            assertThat(jdbc.queryForObject("select abierta from caja where id = ?", Boolean.class, caja)).isTrue();
        }

        @Test
        @DisplayName("la caja de otro dia se puede cerrar, y despues se abre una nueva para vender")
        void cajaDeOtroDiaSeCierra() throws Exception {
            Tienda t = tiendaSinCaja();
            long vieja = cajaAbiertaEl(t.id(), LocalDateTime.now(HoraArgentina.ZONA).minusDays(2));
            String vendedor = login(t.emailVendedor());
            assertThat(getJson("/api/cajas/actual", vendedor).get("estado").asText()).isEqualTo("DE_OTRO_DIA");

            postJson("/api/cajas/" + vieja + "/cierre", vendedor, cierre("0")).andExpect(status().isOk());
            abrir(vendedor, "0");

            assertThat(getJson("/api/cajas/actual", vendedor).get("estado").asText()).isEqualTo("ABIERTA");
        }
    }

    // ---------------------------------------------------------------- historial

    @Nested
    class Historial {

        @Test
        @DisplayName("el dueño lista las cajas cerradas, mas nuevas primero, sin la abierta, y ve el detalle de una")
        void historialDelDueno() throws Exception {
            Tienda t = tiendaSinCaja();
            String vendedor = login(t.emailVendedor());
            String dueno = login(t.emailDueno());
            long primera = abrir(vendedor, "0");
            postJson("/api/cajas/" + primera + "/movimientos", vendedor, movimiento("GASTO", "50", "Luz"))
                    .andExpect(status().isCreated());
            postJson("/api/cajas/" + primera + "/cierre", vendedor, cierre("0")).andExpect(status().isOk());
            long segunda = abrir(dueno, "0");
            postJson("/api/cajas/" + segunda + "/cierre", dueno, cierre("10")).andExpect(status().isOk());
            abrir(vendedor, "10");

            JsonNode pagina = getJson("/api/cajas?page=0&size=20", dueno);

            assertThat(pagina.get("totalElements").asLong()).isEqualTo(2);
            List<Long> ids = new java.util.ArrayList<>();
            pagina.get("items").forEach(c -> ids.add(c.get("id").asLong()));
            assertThat(ids).containsExactly(segunda, primera);
            assertThat(pagina.get("items").get(1).get("diferencia").decimalValue()).isEqualByComparingTo("50");

            JsonNode detalle = getJson("/api/cajas/" + primera, dueno);
            assertThat(detalle.get("abiertaPor").asText()).isEqualTo("Vendedor Ventas");
            assertThat(detalle.get("cerradaPor").asText()).isEqualTo("Vendedor Ventas");
            assertThat(detalle.get("movimientos").get(0).get("motivo").asText()).isEqualTo("Luz");
            assertThat(detalle.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("-50");
        }

        @Test
        @DisplayName("el vendedor no ve el historial: 403")
        void vendedorNoVeElHistorial() throws Exception {
            Tienda t = tiendaSinCaja();

            getResult("/api/cajas", login(t.emailVendedor())).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- cuenta corriente (CC-025)

    @Nested
    class CuentaCorriente {

        /** Una ferretería sin caja y con la forma «Cuenta corriente» activa. */
        private Tienda tiendaConCuenta() {
            Tienda t = tiendaSinCaja();
            activarFormaCuentaCorriente(t);
            return t;
        }

        private long clienteConLimite(Tienda t, String limite) {
            String razon = "Cliente " + unico("caja");
            jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, ?)", t.id(),
                    razon, new BigDecimal(limite));
            return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                    t.id(), razon);
        }

        /** Vende {@code efectivo} en efectivo y {@code aCuenta} a cuenta corriente (pago mixto) a nombre del cliente. */
        private void venderMixto(Tienda t, String token, long articulo, long cliente, String efectivo, String aCuenta)
                throws Exception {
            String total = new BigDecimal(efectivo).add(new BigDecimal(aCuenta)).toPlainString();
            java.util.Map<String, Object> cuerpo = mapa(clave(), null, total, linea(articulo, "1", total));
            cuerpo.put("clienteId", cliente);
            cuerpo.put("pagos", List.of(
                    java.util.Map.of("formaPagoId", formaDe(t, "EFECTIVO"), "monto", new BigDecimal(efectivo)),
                    java.util.Map.of("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"), "monto", new BigDecimal(aCuenta))));
            postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo)).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("una venta mixta ($ 1.000 efectivo + $ 2.000 a cuenta) suma a las ventas del cierre pero NO al efectivo esperado")
        void ventaMixtaEnElCierre() throws Exception {
            Tienda t = tiendaConCuenta();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "5000.00");
            long articulo = articulo(t, "Martillo", "UNIDAD", "3000.00", "50");
            venderMixto(t, vendedor, articulo, clienteConLimite(t, "10000.00"), "1000.00", "2000.00");

            JsonNode resumen = json(postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("6000.00"))
                    .andExpect(status().isOk()));

            assertThat(resumen.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("2000");
            assertThat(resumen.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("1000");
            assertThat(resumen.get("totalVentas").decimalValue()).isEqualByComparingTo("3000");
            assertThat(resumen.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("6000");
            assertThat(resumen.get("diferencia").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("el Dueño ve en vivo el renglón informativo; con gastos y retiros el efectivo esperado sigue sin contarlo")
        void renglonEnVivoParaElDueno() throws Exception {
            Tienda t = tiendaConCuenta();
            String vendedor = login(t.emailVendedor());
            String dueno = login(t.emailDueno());
            long caja = abrir(vendedor, "5000.00");
            long articulo = articulo(t, "Martillo", "UNIDAD", "3000.00", "50");
            venderMixto(t, vendedor, articulo, clienteConLimite(t, "10000.00"), "1000.00", "2000.00");
            postJson("/api/cajas/" + caja + "/movimientos", vendedor, movimiento("GASTO", "300", "Cadete"))
                    .andExpect(status().isCreated());
            postJson("/api/cajas/" + caja + "/movimientos", dueno, movimiento("RETIRO", "1000", "Banco"))
                    .andExpect(status().isCreated());

            JsonNode enVivo = getJson("/api/cajas/" + caja, dueno);

            assertThat(enVivo.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("2000");
            assertThat(enVivo.get("totalVentas").decimalValue()).isEqualByComparingTo("3000");
            // 5000 + 1000 de efectivo - 300 - 1000; los 2000 a cuenta nunca fueron efectivo
            assertThat(enVivo.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("4700");
        }

        @Test
        @DisplayName("una venta ÍNTEGRA a cuenta no pone nada en el efectivo esperado, y sin ventas a cuenta la clave viene en 0")
        void todoAcuentaYSinVentasACuenta() throws Exception {
            Tienda t = tiendaConCuenta();
            String vendedor = login(t.emailVendedor());
            String dueno = login(t.emailDueno());
            long caja = abrir(vendedor, "1000.00");
            long articulo = articulo(t, "Martillo", "UNIDAD", "800.00", "50");

            JsonNode sinCuenta = getJson("/api/cajas/" + caja, dueno);
            assertThat(sinCuenta.get("ventas").has("CUENTA_CORRIENTE")).isTrue();
            assertThat(sinCuenta.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("0");

            java.util.Map<String, Object> cuerpo = mapa(clave(), "CUENTA_CORRIENTE", "800.00", linea(articulo, "1", "800.00"));
            cuerpo.put("clienteId", clienteConLimite(t, "5000.00"));
            postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(cuerpo)).andExpect(status().isCreated());

            JsonNode conCuenta = getJson("/api/cajas/" + caja, dueno);
            assertThat(conCuenta.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("800");
            assertThat(conCuenta.get("totalVentas").decimalValue()).isEqualByComparingTo("800");
            assertThat(conCuenta.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("el cierre ciego no cambia: mientras está abierta el Vendedor no ve ventas, totales ni esperado, con ventas a cuenta")
        void elVendedorSigueSinVer() throws Exception {
            Tienda t = tiendaConCuenta();
            String vendedor = login(t.emailVendedor());
            long caja = abrir(vendedor, "5000.00");
            long articulo = articulo(t, "Martillo", "UNIDAD", "3000.00", "50");
            venderMixto(t, vendedor, articulo, clienteConLimite(t, "10000.00"), "1000.00", "2000.00");

            JsonNode actual = getJson("/api/cajas/actual", vendedor);

            assertThat(actual.get("caja").has("ventas")).isFalse();
            assertThat(actual.get("caja").has("totalVentas")).isFalse();
            assertThat(actual.get("caja").has("efectivoEsperado")).isFalse();
            assertThat(actual.get("caja").has("diferencia")).isFalse();
            getResult("/api/cajas/" + caja, vendedor).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("el historial no se rompe: la caja cerrada con ventas a cuenta se lista y su detalle trae el renglón")
        void historialConVentasACuenta() throws Exception {
            Tienda t = tiendaConCuenta();
            String vendedor = login(t.emailVendedor());
            String dueno = login(t.emailDueno());
            long caja = abrir(vendedor, "5000.00");
            long articulo = articulo(t, "Martillo", "UNIDAD", "3000.00", "50");
            venderMixto(t, vendedor, articulo, clienteConLimite(t, "10000.00"), "1000.00", "2000.00");
            postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("6000.00")).andExpect(status().isOk());

            JsonNode pagina = getJson("/api/cajas?page=0&size=20", dueno);
            JsonNode detalle = getJson("/api/cajas/" + caja, dueno);

            assertThat(pagina.get("totalElements").asLong()).isEqualTo(1);
            assertThat(pagina.get("items").get(0).get("diferencia").decimalValue()).isEqualByComparingTo("0");
            assertThat(detalle.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("2000");
            assertThat(detalle.get("totalVentas").decimalValue()).isEqualByComparingTo("3000");
            assertThat(detalle.get("efectivoEsperado").decimalValue()).isEqualByComparingTo("6000");
        }
    }

    // ---------------------------------------------------------------- aislamiento entre ferreterias

    @Nested
    class Aislamiento {

        @Test
        @DisplayName("otra ferreteria no ve ni toca la caja: 404 en el detalle, los movimientos y el cierre")
        void otraFerreteriaRecibe404() throws Exception {
            Tienda propia = tiendaSinCaja();
            Tienda ajena = tiendaSinCaja();
            long caja = abrir(login(propia.emailVendedor()), "100");
            String duenoAjeno = login(ajena.emailDueno());

            getResult("/api/cajas/" + caja, duenoAjeno).andExpect(status().isNotFound());
            postJson("/api/cajas/" + caja + "/movimientos", duenoAjeno, movimiento("RETIRO", "10", "x"))
                    .andExpect(status().isNotFound());
            postJson("/api/cajas/" + caja + "/cierre", duenoAjeno, cierre("0")).andExpect(status().isNotFound());

            assertThat(getJson("/api/cajas/actual", duenoAjeno).get("estado").asText()).isEqualTo("SIN_CAJA");
            assertThat(movimientosDeCaja(propia)).isZero();
            assertThat(jdbc.queryForObject("select abierta from caja where id = ?", Boolean.class, caja)).isTrue();
        }

        @Test
        @DisplayName("el historial y la propuesta de apertura son solo de la ferreteria del usuario")
        void historialYPropuestaPropios() throws Exception {
            Tienda propia = tiendaSinCaja();
            Tienda ajena = tiendaSinCaja();
            String vendedor = login(propia.emailVendedor());
            long caja = abrir(vendedor, "0");
            postJson("/api/cajas/" + caja + "/cierre", vendedor, cierre("9999")).andExpect(status().isOk());
            String duenoAjeno = login(ajena.emailDueno());

            assertThat(getJson("/api/cajas", duenoAjeno).get("totalElements").asLong()).isZero();
            assertThat(getJson("/api/cajas/actual", duenoAjeno).get("efectivoPropuesto").decimalValue())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("la caja abierta de otra ferreteria no impide abrir la propia")
        void abrirNoMiraOtraFerreteria() throws Exception {
            Tienda propia = tiendaSinCaja();
            Tienda ajena = tienda();

            abrir(login(propia.emailVendedor()), "0");

            assertThat(cajas(propia)).isEqualTo(1);
            assertThat(cajas(ajena)).isEqualTo(1);
        }
    }
}
