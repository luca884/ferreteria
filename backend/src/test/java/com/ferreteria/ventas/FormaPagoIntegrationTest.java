package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
    @DisplayName("una ferreteria nueva ve las cuatro formas de siempre, en orden y sin recargo, la primera vez que las pide")
    void ferreteriaNuevaVeLasCuatro() throws Exception {
        Tienda t = tienda();

        JsonNode formas = getJson("/api/formas-pago", login(t.emailVendedor()));

        assertThat(nombresDe(formas)).containsExactly("Efectivo", "Débito", "Crédito", "Transferencia");
        assertThat(formas.get(0).get("medio").asText()).isEqualTo("EFECTIVO");
        assertThat(formas.get(0).get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(formas.get(0).get("activa").asBoolean()).isTrue();
        assertThat(formas.get(0).get("orden").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("pedirlas dos veces no duplica las de siempre")
    void noSeSiembraDosVeces() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        getJson("/api/formas-pago", dueno);
        JsonNode otraVez = getJson("/api/formas-pago", dueno);

        assertThat(otraVez).hasSize(4);
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
        assertThat(creada.get("orden").asInt()).isEqualTo(5);
        assertThat(nombresDe(getJson("/api/formas-pago", dueno)))
                .containsExactly("Efectivo", "Débito", "Crédito", "Transferencia", "Visa 6 cuotas");
    }

    @Test
    @DisplayName("crear la primera forma de una ferreteria sin ninguna no se come las de siempre")
    void crearAntesDeListarSiembraIgual() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());

        postJson("/api/formas-pago", dueno, CREDITO_6).andExpect(status().isCreated());

        assertThat(nombresDe(getJson("/api/formas-pago", dueno))).hasSize(5).contains("Efectivo", "Visa 6 cuotas");
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
        assertThat(nombresDe(getJson("/api/formas-pago", dueno))).hasSize(4);
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
                .isEqualTo("El medio de pago no es válido. Usá EFECTIVO, DEBITO, CREDITO o TRANSFERENCIA.");
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
