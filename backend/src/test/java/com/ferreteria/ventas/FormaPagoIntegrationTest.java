package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** RC-001, RC-008: el contrato HTTP de {@code /api/formas-pago} contra MySQL real. */
class FormaPagoIntegrationTest extends VentasTestBase {

    private static final String CREDITO_6 =
            "{\"nombre\": \"Visa 6 cuotas\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 18.5}";

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> nombresDe(JsonNode formas) {
        return nombres(formas);
    }

    private long idDe(JsonNode formas, String nombre) {
        for (JsonNode forma : formas) {
            if (forma.get("nombre").asText().equals(nombre)) {
                return forma.get("id").asLong();
            }
        }
        throw new AssertionError("No hay una forma llamada " + nombre);
    }

    @Test
    @DisplayName("una ferreteria nueva ve las cinco formas (las cuatro de siempre y «Cuenta corriente»), en orden y sin recargo, la primera vez que las pide")
    void ferreteriaNuevaVeLasCinco() throws Exception {
        Tienda t = tienda();

        JsonNode formas = getJson("/api/formas-pago", login(t.emailVendedor()));

        assertThat(nombresDe(formas)).containsExactly("Efectivo", "Débito", "Crédito", "Transferencia",
                "Cuenta corriente");
        assertThat(formas.get(0).get("medio").asText()).isEqualTo("EFECTIVO");
        assertThat(formas.get(0).get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(formas.get(0).get("activa").asBoolean()).isTrue();
        assertThat(formas.get(0).get("orden").asInt()).isEqualTo(1);
        // las cuatro de siempre quedan activas y con sus ordenes 1 a 4
        for (int i = 0; i < 4; i++) {
            assertThat(formas.get(i).get("activa").asBoolean()).as(formas.get(i).get("nombre").asText()).isTrue();
            assertThat(formas.get(i).get("orden").asInt()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("CC-024: la sembrada «Cuenta corriente» viene inactiva, con medio CUENTA_CORRIENTE, recargo 0 y orden 5")
    void cuentaCorrienteSembradaInactiva() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        JsonNode formas = getJson("/api/formas-pago", dueno);

        JsonNode cc = formas.get(4);
        assertThat(cc.get("nombre").asText()).isEqualTo("Cuenta corriente");
        assertThat(cc.get("medio").asText()).isEqualTo("CUENTA_CORRIENTE");
        assertThat(cc.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(cc.get("activa").asBoolean()).isFalse();
        assertThat(cc.get("orden").asInt()).isEqualTo(5);
        // inactiva: no aparece en el cobro (?activas=true)
        assertThat(nombresDe(getJson("/api/formas-pago?activas=true", dueno)))
                .containsExactly("Efectivo", "Débito", "Crédito", "Transferencia");
    }

    @Test
    @DisplayName("soporte de tests: formaDe(tienda, CUENTA_CORRIENTE) devuelve la forma inactiva y activarFormaCuentaCorriente la deja activa, igual que el servicio")
    void helpersDeLaFormaCuentaCorriente() throws Exception {
        Tienda t = tienda();
        long deLaBase = formaDe(t, "CUENTA_CORRIENTE");
        String dueno = login(t.emailDueno());

        // formaDe cargo las cinco formas como el servicio; la API ve la misma forma, inactiva
        JsonNode antes = getJson("/api/formas-pago", dueno);
        assertThat(antes).hasSize(5);
        assertThat(idDe(antes, "Cuenta corriente")).isEqualTo(deLaBase);
        assertThat(nombresDe(getJson("/api/formas-pago?activas=true", dueno))).doesNotContain("Cuenta corriente");

        activarFormaCuentaCorriente(t);

        assertThat(nombresDe(getJson("/api/formas-pago?activas=true", dueno))).contains("Cuenta corriente");
        assertThat(formaDe(t, "CUENTA_CORRIENTE")).isEqualTo(deLaBase);
    }

    @Test
    @DisplayName("CC-024: el Dueño activa la «Cuenta corriente» por PUT y desde ahí aparece activa en el cobro")
    void duenoActivaCuentaCorriente() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long cc = idDe(getJson("/api/formas-pago", dueno), "Cuenta corriente");

        JsonNode activada = cuerpo(putJson("/api/formas-pago/" + cc, dueno,
                "{\"nombre\": \"Cuenta corriente\", \"medio\": \"CUENTA_CORRIENTE\", \"activa\": true}")
                .andExpect(status().isOk()));

        assertThat(activada.get("activa").asBoolean()).isTrue();
        assertThat(activada.get("medio").asText()).isEqualTo("CUENTA_CORRIENTE");
        assertThat(nombresDe(getJson("/api/formas-pago?activas=true", dueno))).contains("Cuenta corriente");
        assertThat(jdbc.queryForObject("select medio from forma_pago where id = ?", String.class, cc))
                .isEqualTo("CUENTA_CORRIENTE");
    }

    @Test
    @DisplayName("CC-023: con medio CUENTA_CORRIENTE el recargo se guarda en 0 aunque venga 10 (al editar la sembrada, al crear y al cambiarle el medio a otra forma)")
    void cuentaCorrienteFuerzaRecargoCero() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        JsonNode formas = getJson("/api/formas-pago", dueno);
        long cc = idDe(formas, "Cuenta corriente");
        long credito = idDe(formas, "Crédito");

        JsonNode editada = cuerpo(putJson("/api/formas-pago/" + cc, dueno,
                "{\"nombre\": \"Cuenta corriente\", \"medio\": \"CUENTA_CORRIENTE\", \"recargoPorcentaje\": 10}")
                .andExpect(status().isOk()));
        JsonNode creada = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Fiado\", \"medio\": \"CUENTA_CORRIENTE\", \"recargoPorcentaje\": 7.5}")
                .andExpect(status().isCreated()));
        JsonNode convertida = cuerpo(putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CUENTA_CORRIENTE\", \"recargoPorcentaje\": 12}")
                .andExpect(status().isOk()));

        assertThat(editada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(creada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(convertida.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select recargo_porcentaje from forma_pago where id = ?", java.math.BigDecimal.class, cc))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CC-023: el recargo de las formas de otro medio sigue siendo editable (regresión)")
    void otrosMediosConservanSuRecargo() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");

        JsonNode editada = cuerpo(putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 10}")
                .andExpect(status().isOk()));

        assertThat(editada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("pedirlas dos veces no duplica las de siempre")
    void noSeSiembraDosVeces() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        getJson("/api/formas-pago", dueno);
        JsonNode otraVez = getJson("/api/formas-pago", dueno);

        assertThat(otraVez).hasSize(5);
    }

    @Test
    @DisplayName("varios primeros pedidos a la vez de una ferreteria nueva dan todos 200 y las cinco formas una sola vez")
    void primerosPedidosEnParalelo() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch largada = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> pedidos = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String token = i % 2 == 0 ? dueno : vendedor;
            pedidos.add(pool.submit(() -> {
                largada.await();
                return getResult("/api/formas-pago", token).andReturn().getResponse().getStatus();
            }));
        }
        largada.countDown();
        try {
            for (java.util.concurrent.Future<Integer> pedido : pedidos) {
                assertThat(pedido.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, t.id()))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("el Dueno crea una forma con su recargo y queda al final")
    void duenoCrea() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        JsonNode creada = cuerpo(postJson("/api/formas-pago", dueno, CREDITO_6).andExpect(status().isCreated()));

        assertThat(creada.get("nombre").asText()).isEqualTo("Visa 6 cuotas");
        assertThat(creada.get("medio").asText()).isEqualTo("CREDITO");
        assertThat(creada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("18.5");
        assertThat(creada.get("activa").asBoolean()).isTrue();
        assertThat(creada.get("orden").asInt()).isEqualTo(6);
        assertThat(nombresDe(getJson("/api/formas-pago", dueno)))
                .containsExactly("Efectivo", "Débito", "Crédito", "Transferencia", "Cuenta corriente", "Visa 6 cuotas");
    }

    @Test
    @DisplayName("crear la primera forma de una ferreteria sin ninguna no se come las de siempre")
    void crearAntesDeListarSiembraIgual() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        postJson("/api/formas-pago", dueno, CREDITO_6).andExpect(status().isCreated());

        assertThat(nombresDe(getJson("/api/formas-pago", dueno))).hasSize(6).contains("Efectivo", "Cuenta corriente", "Visa 6 cuotas");
    }

    @Test
    @DisplayName("el Vendedor las ve pero no las crea ni las edita: 403")
    void vendedorSoloLee() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");

        postJson("/api/formas-pago", vendedor, CREDITO_6).andExpect(status().isForbidden());
        putJson("/api/formas-pago/" + credito, vendedor, CREDITO_6).andExpect(status().isForbidden());
        getResult("/api/formas-pago", vendedor).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin sesion, 401")
    void sinSesion() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/formas-pago"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("?activas=true deja afuera las desactivadas")
    void soloActivas() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long transferencia = idDe(getJson("/api/formas-pago", dueno), "Transferencia");

        putJson("/api/formas-pago/" + transferencia, dueno,
                "{\"nombre\": \"Transferencia\", \"medio\": \"TRANSFERENCIA\", \"activa\": false}")
                .andExpect(status().isOk());

        assertThat(nombresDe(getJson("/api/formas-pago?activas=true", dueno)))
                .containsExactly("Efectivo", "Débito", "Crédito");
        assertThat(nombresDe(getJson("/api/formas-pago", dueno))).hasSize(5);
    }

    @Test
    @DisplayName("el Dueno cambia el recargo y el nombre de una forma")
    void duenoEdita() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");

        JsonNode editada = cuerpo(putJson("/api/formas-pago/" + credito, dueno, CREDITO_6)
                .andExpect(status().isOk()));

        assertThat(editada.get("id").asLong()).isEqualTo(credito);
        assertThat(editada.get("nombre").asText()).isEqualTo("Visa 6 cuotas");
        assertThat(editada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("18.5");
        assertThat(editada.get("orden").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("un nombre repetido, aunque cambie en mayusculas o tildes, es 409")
    void nombreRepetido() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");

        JsonNode alCrear = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"DEBITO\", \"medio\": \"DEBITO\"}").andExpect(status().isConflict()));
        JsonNode alEditar = cuerpo(putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"efectivo\", \"medio\": \"CREDITO\"}").andExpect(status().isConflict()));

        assertThat(alCrear.get("message").asText()).isEqualTo("Ya hay una forma de pago con ese nombre.");
        assertThat(alEditar.get("message").asText()).isEqualTo("Ya hay una forma de pago con ese nombre.");
    }

    @Test
    @DisplayName("la misma ferreteria puede guardar la forma con su propio nombre sin que choque consigo misma")
    void propioNombreNoChoca() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");

        putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 10}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("otra ferreteria puede usar el mismo nombre")
    void otraFerreteriaMismoNombre() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();

        postJson("/api/formas-pago", login(a.emailDueno()), CREDITO_6).andExpect(status().isCreated());
        postJson("/api/formas-pago", login(b.emailDueno()), CREDITO_6).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("la forma de otra ferreteria da 404, como si no existiera")
    void formaAjena() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deB = idDe(getJson("/api/formas-pago", login(b.emailDueno())), "Crédito");

        putJson("/api/formas-pago/" + deB, login(a.emailDueno()), CREDITO_6).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select nombre from forma_pago where id = ?", String.class, deB))
                .isEqualTo("Crédito");
    }

    @Test
    @DisplayName("descuento en efectivo: el Dueño lo guarda y la lista lo muestra; fuera de 0 a 99,99 es 400; con recargo es 422; el de otra ferretería es 404")
    void descuentoDeLaForma() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        String dueno = login(a.emailDueno());
        long efectivo = idDe(getJson("/api/formas-pago", dueno), "Efectivo");
        long credito = idDe(getJson("/api/formas-pago", dueno), "Crédito");
        long deB = idDe(getJson("/api/formas-pago", login(b.emailDueno())), "Efectivo");
        String conDescuento = "{\"nombre\": \"Efectivo\", \"medio\": \"EFECTIVO\", \"descuentoPorcentaje\": 10.50}";

        JsonNode guardada = cuerpo(putJson("/api/formas-pago/" + efectivo, dueno, conDescuento)
                .andExpect(status().isOk()));
        JsonNode alto = cuerpo(putJson("/api/formas-pago/" + efectivo, dueno,
                "{\"nombre\": \"Efectivo\", \"medio\": \"EFECTIVO\", \"descuentoPorcentaje\": 100}")
                .andExpect(status().isBadRequest()));
        JsonNode negativo = cuerpo(putJson("/api/formas-pago/" + efectivo, dueno,
                "{\"nombre\": \"Efectivo\", \"medio\": \"EFECTIVO\", \"descuentoPorcentaje\": -1}")
                .andExpect(status().isBadRequest()));
        JsonNode largo = cuerpo(putJson("/api/formas-pago/" + efectivo, dueno,
                "{\"nombre\": \"Efectivo\", \"medio\": \"EFECTIVO\", \"descuentoPorcentaje\": 10.123}")
                .andExpect(status().isBadRequest()));
        JsonNode ambos = cuerpo(putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 5, \"descuentoPorcentaje\": 5}")
                .andExpect(status().isUnprocessableEntity()));
        putJson("/api/formas-pago/" + deB, dueno, conDescuento).andExpect(status().isNotFound());
        putJson("/api/formas-pago/" + efectivo, login(a.emailVendedor()), conDescuento)
                .andExpect(status().isForbidden());

        assertThat(guardada.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("10.50");
        JsonNode lista = getJson("/api/formas-pago", dueno);
        for (JsonNode forma : lista) {
            assertThat(forma.get("descuentoPorcentaje").decimalValue()).as(forma.get("nombre").asText())
                    .isEqualByComparingTo(forma.get("id").asLong() == efectivo ? "10.50" : "0");
        }
        assertThat(alto.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento tiene que estar entre 0 y 99,99 %.");
        assertThat(negativo.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento tiene que estar entre 0 y 99,99 %.");
        assertThat(largo.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento admite hasta 2 decimales.");
        assertThat(ambos.get("message").asText()).isEqualTo("Una forma de pago lleva recargo o descuento, no los dos.");
        assertThat(jdbc.queryForObject("select descuento_porcentaje from forma_pago where id = ?", BigDecimal.class, deB))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("desactivar la unica forma en efectivo es 422, y con otra en efectivo activa se puede")
    void siempreUnaEnEfectivo() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long efectivo = idDe(getJson("/api/formas-pago", dueno), "Efectivo");
        String desactivar = "{\"nombre\": \"Efectivo\", \"medio\": \"EFECTIVO\", \"activa\": false}";

        JsonNode rechazo = cuerpo(putJson("/api/formas-pago/" + efectivo, dueno, desactivar)
                .andExpect(status().isUnprocessableEntity()));
        assertThat(rechazo.get("message").asText()).isEqualTo("Tiene que quedar al menos una forma de pago en efectivo.");
        putJson("/api/formas-pago/" + efectivo, dueno,
                "{\"nombre\": \"Efectivo\", \"medio\": \"DEBITO\"}").andExpect(status().isUnprocessableEntity());

        postJson("/api/formas-pago", dueno, "{\"nombre\": \"Efectivo con descuento\", \"medio\": \"EFECTIVO\"}")
                .andExpect(status().isCreated());
        putJson("/api/formas-pago/" + efectivo, dueno, desactivar).andExpect(status().isOk());
    }

    @Test
    @DisplayName("los datos invalidos son 400 con el mensaje de cada campo")
    void datosInvalidos() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        JsonNode sinNombre = cuerpo(postJson("/api/formas-pago", dueno, "{\"nombre\": \"  \", \"medio\": \"CREDITO\"}")
                .andExpect(status().isBadRequest()));
        JsonNode nombreLargo = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"" + "x".repeat(61) + "\", \"medio\": \"CREDITO\"}").andExpect(status().isBadRequest()));
        JsonNode sinMedio = cuerpo(postJson("/api/formas-pago", dueno, "{\"nombre\": \"Algo\"}")
                .andExpect(status().isBadRequest()));
        JsonNode medioMalo = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Algo\", \"medio\": \"CHEQUE\"}").andExpect(status().isBadRequest()));
        JsonNode recargoAlto = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Algo\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 200.01}")
                .andExpect(status().isBadRequest()));
        JsonNode recargoNegativo = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Algo\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": -1}")
                .andExpect(status().isBadRequest()));
        JsonNode recargoLargo = cuerpo(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Algo\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 10.123}")
                .andExpect(status().isBadRequest()));

        assertThat(sinNombre.get("errors").get("nombre").asText()).isEqualTo("El nombre es obligatorio.");
        assertThat(nombreLargo.get("errors").get("nombre").asText())
                .isEqualTo("El nombre no puede superar 60 caracteres.");
        assertThat(sinMedio.get("errors").get("medio").asText()).isEqualTo("Elegí el medio de pago.");
        assertThat(medioMalo.get("errors").get("medio").asText())
                .isEqualTo("El medio de pago no es válido. Usá EFECTIVO, DEBITO, CREDITO, TRANSFERENCIA o CUENTA_CORRIENTE.");
        assertThat(recargoAlto.get("errors").get("recargoPorcentaje").asText())
                .isEqualTo("El recargo tiene que estar entre 0 y 200 %.");
        assertThat(recargoNegativo.get("errors").get("recargoPorcentaje").asText())
                .isEqualTo("El recargo tiene que estar entre 0 y 200 %.");
        assertThat(recargoLargo.get("errors").get("recargoPorcentaje").asText())
                .isEqualTo("El recargo admite hasta 2 decimales.");
        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, t.id()))
                .isZero();
    }
}
