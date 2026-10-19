package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CL-001 a CL-003 y CC-007, CC-019 a CC-021: el contrato HTTP de {@code /api/clientes} contra MySQL real, con el aislamiento
 * por ferretería, el saldo y el límite de cuenta corriente solo para el Dueño y desactivar con saldo.
 */
class ClienteIntegrationTest extends IntegrationTestBase {

    record Tienda(long id, String emailDueno, String emailVendedor) {}

    /** Una ferretería propia con Dueño y Vendedor: la base no se revierte, así que cada test arma la suya. */
    private Tienda tienda() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String cuit = "30-8%07d-%d".formatted(ThreadLocalRandom.current().nextInt(10_000_000),
                ThreadLocalRandom.current().nextInt(10));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Clientes " + sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        String dueno = "dueno-" + sufijo + "@clientes.demo";
        String vendedor = "vendedor-" + sufijo + "@clientes.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Clientes', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Clientes', 'VENDEDOR')", id, vendedor, hash);
        return new Tienda(id, dueno, vendedor);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions crear(String token, String cuerpo) throws Exception {
        return postJson("/api/clientes", token, cuerpo);
    }

    private long crearOk(String token, String cuerpo) throws Exception {
        return json(crear(token, cuerpo).andExpect(status().isCreated())).get("id").asLong();
    }

    private ResultActions desactivar(String token, long id, boolean activo) throws Exception {
        return mvc.perform(patch("/api/clientes/" + id + "/activo")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\": " + activo + "}"));
    }

    private static String cliente(String razonSocial) {
        return "{\"razonSocial\": \"" + razonSocial + "\"}";
    }

    private static String clienteConDoc(String razonSocial, String tipo, String numero) {
        return "{\"razonSocial\": \"" + razonSocial + "\", \"tipoDocumento\": \"" + tipo
                + "\", \"numeroDocumento\": \"" + numero + "\"}";
    }

    private List<String> razonesSociales(JsonNode pagina) {
        List<String> razones = new ArrayList<>();
        pagina.get("items").forEach(c -> razones.add(c.get("razonSocial").asText()));
        return razones;
    }

    @Test
    @DisplayName("el Vendedor crea un cliente con CUIT con guiones: se guarda con dígitos, Consumidor Final y activo")
    void creaConCuitConGuiones() throws Exception {
        Tienda t = tienda();

        JsonNode creado = json(crear(login(t.emailVendedor()),
                clienteConDoc("Ferretería Norte SRL", "CUIT", "30-71234567-1")).andExpect(status().isCreated()));

        assertThat(creado.get("id").asLong()).isPositive();
        assertThat(creado.get("razonSocial").asText()).isEqualTo("Ferretería Norte SRL");
        assertThat(creado.get("tipoDocumento").asText()).isEqualTo("CUIT");
        assertThat(creado.get("numeroDocumento").asText()).isEqualTo("30712345671");
        assertThat(creado.get("condicionIva").asText()).isEqualTo("CONSUMIDOR_FINAL");
        assertThat(creado.get("activo").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("guarda todos los datos de contacto y la condición de IVA elegida")
    void guardaTodosLosDatos() throws Exception {
        Tienda t = tienda();
        String cuerpo = """
                {"razonSocial": "Juan Pérez", "tipoDocumento": "DNI", "numeroDocumento": "12.345.678",
                 "condicionIva": "MONOTRIBUTISTA", "domicilio": "Av. Colón 1234, Mar del Plata",
                 "telefono": "223 456-7890", "whatsapp": "+54 9 223 555-0000", "email": "juan@correo.com"}""";

        JsonNode creado = json(crear(login(t.emailDueno()), cuerpo).andExpect(status().isCreated()));

        assertThat(creado.get("numeroDocumento").asText()).isEqualTo("12345678");
        assertThat(creado.get("condicionIva").asText()).isEqualTo("MONOTRIBUTISTA");
        assertThat(creado.get("domicilio").asText()).isEqualTo("Av. Colón 1234, Mar del Plata");
        assertThat(creado.get("telefono").asText()).isEqualTo("223 456-7890");
        assertThat(creado.get("whatsapp").asText()).isEqualTo("+54 9 223 555-0000");
        assertThat(creado.get("email").asText()).isEqualTo("juan@correo.com");
    }

    @Test
    @DisplayName("varios clientes sin documento conviven")
    void variosSinDocumento() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());

        crearOk(token, cliente("Juan, el del taller"));
        crearOk(token, cliente("Pedro, el de la obra"));

        assertThat(razonesSociales(getJson("/api/clientes", token)))
                .containsExactly("Juan, el del taller", "Pedro, el de la obra");
    }

    @Test
    @DisplayName("la razón social es obligatoria y tiene tope de 120")
    void razonSocialObligatoria() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());

        JsonNode vacia = json(crear(token, cliente("   ")).andExpect(status().isBadRequest()));
        JsonNode larga = json(crear(token, cliente("x".repeat(121))).andExpect(status().isBadRequest()));

        assertThat(vacia.get("errors").get("razonSocial").asText()).isEqualTo("La razón social es obligatoria.");
        assertThat(larga.get("errors").get("razonSocial").asText()).isEqualTo("La razón social admite hasta 120 caracteres.");
    }

    @Test
    @DisplayName("un documento inválido es 400 con el motivo en numeroDocumento")
    void documentoInvalido() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());

        JsonNode cuit = json(crear(token, clienteConDoc("A", "CUIT", "20123456787")).andExpect(status().isBadRequest()));
        JsonNode dni = json(crear(token, clienteConDoc("B", "DNI", "123")).andExpect(status().isBadRequest()));

        assertThat(cuit.get("errors").get("numeroDocumento").asText())
                .isEqualTo("El CUIT no es válido: revisá el dígito verificador.");
        assertThat(dni.get("errors").get("numeroDocumento").asText()).isEqualTo("El DNI tiene que tener 7 u 8 dígitos.");
        assertThat(jdbc.queryForObject("select count(*) from cliente where ferreteria_id = ?", Long.class, t.id()))
                .isZero();
    }

    @Test
    @DisplayName("el tipo y el número de documento van juntos: uno solo es 400")
    void documentoIncompleto() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());

        JsonNode sinNumero = json(crear(token, "{\"razonSocial\": \"A\", \"tipoDocumento\": \"DNI\"}")
                .andExpect(status().isBadRequest()));
        JsonNode sinTipo = json(crear(token, "{\"razonSocial\": \"B\", \"numeroDocumento\": \"12345678\"}")
                .andExpect(status().isBadRequest()));

        assertThat(sinNumero.get("errors").get("numeroDocumento").asText()).isEqualTo("Falta el número de documento.");
        assertThat(sinTipo.get("errors").get("tipoDocumento").asText()).isEqualTo("Elegí el tipo de documento.");
    }

    @Test
    @DisplayName("el correo tiene que parecer un correo")
    void correoInvalido() throws Exception {
        Tienda t = tienda();

        JsonNode respuesta = json(crear(login(t.emailVendedor()),
                "{\"razonSocial\": \"A\", \"email\": \"no-es-un-correo\"}").andExpect(status().isBadRequest()));

        assertThat(respuesta.get("errors").get("email").asText()).isEqualTo("El correo no es válido.");
    }

    @Test
    @DisplayName("un documento repetido en la ferretería es 409, aunque el otro cliente esté desactivado")
    void documentoRepetido() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long primero = crearOk(dueno, clienteConDoc("Ana", "DNI", "12345678"));

        crear(dueno, clienteConDoc("Otra Ana", "DNI", "12.345.678")).andExpect(status().isConflict());
        desactivar(dueno, primero, false).andExpect(status().isOk());
        JsonNode conflicto = json(crear(dueno, clienteConDoc("Otra Ana", "DNI", "12345678"))
                .andExpect(status().isConflict()));

        assertThat(conflicto.get("message").asText()).isEqualTo("Ya hay un cliente con ese documento.");
    }

    @Test
    @DisplayName("otra ferretería puede tener un cliente con el mismo documento")
    void mismoDocumentoEnOtraFerreteria() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();

        crearOk(login(a.emailDueno()), clienteConDoc("Ana", "DNI", "12345678"));
        crearOk(login(b.emailDueno()), clienteConDoc("Ana", "DNI", "12345678"));

        assertThat(jdbc.queryForObject("select count(*) from cliente where numero_documento = '12345678'", Long.class))
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("lista por razón social, con la forma de página de siempre")
    void listaOrdenada() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        crearOk(token, cliente("Zapatería Sur"));
        crearOk(token, cliente("Almacén Centro"));
        crearOk(token, cliente("Metalúrgica Oeste"));

        JsonNode pagina = getJson("/api/clientes", token);

        assertThat(razonesSociales(pagina)).containsExactly("Almacén Centro", "Metalúrgica Oeste", "Zapatería Sur");
        assertThat(pagina.get("page").asInt()).isZero();
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("pagina: size 2 devuelve 2 y el resto en la página siguiente")
    void paginacion() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        for (String n : List.of("A", "B", "C")) {
            crearOk(token, cliente(n));
        }

        JsonNode primera = getJson("/api/clientes?size=2", token);
        JsonNode segunda = getJson("/api/clientes?size=2&page=1", token);

        assertThat(razonesSociales(primera)).containsExactly("A", "B");
        assertThat(razonesSociales(segunda)).containsExactly("C");
        assertThat(primera.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("q busca por razón social: contiene, sin distinguir mayúsculas ni tildes")
    void busquedaPorNombre() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        crearOk(token, cliente("Ferretería Norte"));
        crearOk(token, cliente("Pérez Hermanos"));
        crearOk(token, cliente("Almacén Centro"));

        assertThat(razonesSociales(getJson("/api/clientes?q=ferreteria", token))).containsExactly("Ferretería Norte");
        assertThat(razonesSociales(getJson("/api/clientes?q=PEREZ", token))).containsExactly("Pérez Hermanos");
        assertThat(razonesSociales(getJson("/api/clientes?q=ac", token))).containsExactly("Almacén Centro");
        assertThat(razonesSociales(getJson("/api/clientes?q=zzz", token))).isEmpty();
    }

    @Test
    @DisplayName("q con dígitos busca por documento: empieza con eso, con o sin guiones")
    void busquedaPorDocumento() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        crearOk(token, clienteConDoc("Con CUIT", "CUIT", "30712345671"));
        crearOk(token, clienteConDoc("Con DNI", "DNI", "12345678"));
        crearOk(token, cliente("Sin documento"));

        assertThat(razonesSociales(getJson("/api/clientes?q=3071", token))).containsExactly("Con CUIT");
        assertThat(razonesSociales(getJson("/api/clientes?q=30-71234", token))).containsExactly("Con CUIT");
        assertThat(razonesSociales(getJson("/api/clientes?q=12.345", token))).containsExactly("Con DNI");
        assertThat(razonesSociales(getJson("/api/clientes?q=345", token))).isEmpty();
    }

    @Test
    @DisplayName("activos=true no trae los desactivados; sin el filtro vienen todos")
    void filtroDeActivos() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        crearOk(dueno, cliente("Activo"));
        long viejo = crearOk(dueno, cliente("Viejo"));
        desactivar(dueno, viejo, false).andExpect(status().isOk());

        assertThat(razonesSociales(getJson("/api/clientes?activos=true", dueno))).containsExactly("Activo");
        assertThat(razonesSociales(getJson("/api/clientes", dueno))).containsExactly("Activo", "Viejo");
    }

    @Test
    @DisplayName("el detalle trae el cliente, y el de otra ferretería es 404")
    void detalleYAislamiento() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long id = crearOk(login(a.emailDueno()), clienteConDoc("Ana", "DNI", "12345678"));

        assertThat(getJson("/api/clientes/" + id, login(a.emailVendedor())).get("razonSocial").asText()).isEqualTo("Ana");
        getResult("/api/clientes/" + id, login(b.emailDueno())).andExpect(status().isNotFound());
        assertThat(razonesSociales(getJson("/api/clientes", login(b.emailDueno())))).isEmpty();
    }

    @Test
    @DisplayName("el Vendedor edita; guardar con su propio documento no choca consigo mismo")
    void edita() throws Exception {
        Tienda t = tienda();
        String vendedor = login(t.emailVendedor());
        long id = crearOk(vendedor, clienteConDoc("Ana", "DNI", "12345678"));

        JsonNode editado = json(putJson("/api/clientes/" + id, vendedor, """
                {"razonSocial": "Ana Gómez", "tipoDocumento": "DNI", "numeroDocumento": "12345678",
                 "condicionIva": "RESPONSABLE_INSCRIPTO", "telefono": "2234567890"}""").andExpect(status().isOk()));

        assertThat(editado.get("razonSocial").asText()).isEqualTo("Ana Gómez");
        assertThat(editado.get("condicionIva").asText()).isEqualTo("RESPONSABLE_INSCRIPTO");
        assertThat(editado.get("telefono").asText()).isEqualTo("2234567890");
        assertThat(editado.get("activo").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("editar puede sacar el documento, y no puede tomar el de otro cliente (409)")
    void editaElDocumento() throws Exception {
        Tienda t = tienda();
        String token = login(t.emailVendedor());
        long ana = crearOk(token, clienteConDoc("Ana", "DNI", "12345678"));
        crearOk(token, clienteConDoc("Beto", "DNI", "87654321"));

        putJson("/api/clientes/" + ana, token, clienteConDoc("Ana", "DNI", "87654321")).andExpect(status().isConflict());
        JsonNode sinDoc = json(putJson("/api/clientes/" + ana, token, cliente("Ana")).andExpect(status().isOk()));

        assertThat(sinDoc.has("numeroDocumento")).isFalse();
        assertThat(sinDoc.has("tipoDocumento")).isFalse();
    }

    @Test
    @DisplayName("editar un cliente de otra ferretería es 404 y no cambia nada")
    void editarAjenoEs404() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long id = crearOk(login(a.emailDueno()), cliente("Ana"));

        putJson("/api/clientes/" + id, login(b.emailDueno()), cliente("Hackeada")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, id)).isEqualTo("Ana");
    }

    @Test
    @DisplayName("solo el Dueño desactiva y reactiva; el Vendedor es 403")
    void soloElDuenoDesactiva() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        long id = crearOk(vendedor, cliente("Ana"));

        desactivar(vendedor, id, false).andExpect(status().isForbidden());
        assertThat(json(desactivar(dueno, id, false).andExpect(status().isOk())).get("activo").asBoolean()).isFalse();
        assertThat(json(desactivar(dueno, id, true).andExpect(status().isOk())).get("activo").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("desactivar un cliente de otra ferretería es 404")
    void desactivarAjenoEs404() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long id = crearOk(login(a.emailDueno()), cliente("Ana"));

        desactivar(login(b.emailDueno()), id, false).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("sin token no se ve nada: 401")
    void sinToken() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/clientes"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- cuenta corriente en la respuesta (CC-007, CC-019)

    /** Siembra el límite y el saldo del cliente (con su movimiento en el libro). */
    private void cuenta(Tienda t, long id, String limite, String saldo) {
        SemillaCuentaCliente.sembrar(jdbc, t.id(), id, limite, saldo);
    }

    private static void assertConCifras(JsonNode cliente, String limite, String saldo) {
        assertThat(cliente.get("cuentaCorrienteHabilitada").asBoolean()).isEqualTo(limite != null);
        if (limite == null) {
            assertThat(cliente.has("limiteCuentaCorriente")).isFalse();
        } else {
            assertThat(cliente.get("limiteCuentaCorriente").decimalValue()).isEqualByComparingTo(limite);
        }
        assertThat(cliente.get("saldo").decimalValue()).isEqualByComparingTo(saldo);
    }

    private static void assertSinCifras(JsonNode cliente, boolean habilitada) {
        assertThat(cliente.get("cuentaCorrienteHabilitada").asBoolean()).isEqualTo(habilitada);
        assertThat(cliente.has("limiteCuentaCorriente")).as("limiteCuentaCorriente en %s", cliente).isFalse();
        assertThat(cliente.has("saldo")).as("saldo en %s", cliente).isFalse();
    }

    @Test
    @DisplayName("el Dueño ve el saldo y el límite en la lista, el detalle y al editar y al desactivar; crear trae el saldo en 0")
    void duenoVeLasCifras() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long habilitado = crearOk(dueno, cliente("Con cuenta"));
        cuenta(t, habilitado, "1000.00", "0.00");
        long con = crearOk(dueno, cliente("Con deuda"));
        cuenta(t, con, "5000.00", "1250.50");

        JsonNode creado = json(crear(dueno, cliente("Recién creado")).andExpect(status().isCreated()));
        JsonNode lista = getJson("/api/clientes?q=Con", dueno);
        JsonNode detalle = getJson("/api/clientes/" + con, dueno);
        JsonNode editado = json(putJson("/api/clientes/" + con, dueno, cliente("Con deuda SA")).andExpect(status().isOk()));
        long sinDeuda = habilitado;
        JsonNode desactivado = json(desactivar(dueno, sinDeuda, false).andExpect(status().isOk()));

        assertConCifras(creado, null, "0");
        assertConCifras(detalle, "5000.00", "1250.50");
        assertConCifras(editado, "5000.00", "1250.50");
        assertConCifras(desactivado, "1000.00", "0");
        assertThat(lista.get("items")).hasSize(2);
        lista.get("items").forEach(c -> assertThat(c.has("saldo")).isTrue());
    }

    @Test
    @DisplayName("el Vendedor no ve el saldo ni el límite en ningún lado, pero sí si la cuenta está habilitada")
    void vendedorNoVeLasCifras() throws Exception {
        Tienda t = tienda();
        String vendedor = login(t.emailVendedor());
        String dueno = login(t.emailDueno());
        long con = crearOk(vendedor, cliente("Con deuda"));
        cuenta(t, con, "5000.00", "1250.50");
        long sin = crearOk(vendedor, cliente("Sin cuenta"));

        JsonNode creado = json(crear(vendedor, cliente("Recién creado")).andExpect(status().isCreated()));
        JsonNode lista = getJson("/api/clientes", vendedor);
        JsonNode detalleCon = getJson("/api/clientes/" + con, vendedor);
        JsonNode detalleSin = getJson("/api/clientes/" + sin, vendedor);
        JsonNode editado = json(putJson("/api/clientes/" + con, vendedor, cliente("Con deuda SA")).andExpect(status().isOk()));
        long libre = crearOk(dueno, cliente("Libre"));
        JsonNode desactivadoPorElDueno = json(desactivar(dueno, libre, false).andExpect(status().isOk()));

        assertSinCifras(creado, false);
        assertSinCifras(detalleCon, true);
        assertSinCifras(detalleSin, false);
        assertSinCifras(editado, true);
        lista.get("items").forEach(c -> assertThat(c.has("saldo") || c.has("limiteCuentaCorriente")).isFalse());
        assertThat(lista.get("items")).hasSize(3);
        assertThat(desactivadoPorElDueno.has("saldo")).isTrue(); // control: el mismo cliente, visto por el Dueño, sí trae el saldo
    }

    @Test
    @DisplayName("un limiteCuentaCorriente (o saldo) en el cuerpo de crear o editar se ignora: el cliente conserva su cuenta")
    void elRequestNoTocaLaCuenta() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long id = crearOk(dueno, cliente("Ana"));
        cuenta(t, id, "1000.00", "300.00");

        JsonNode editado = json(putJson("/api/clientes/" + id, dueno,
                "{\"razonSocial\": \"Ana Gómez\", \"limiteCuentaCorriente\": 999999, \"saldo\": 0}")
                .andExpect(status().isOk()));
        JsonNode creado = json(crear(dueno, "{\"razonSocial\": \"Beto\", \"limiteCuentaCorriente\": 5000, \"saldo\": 70}")
                .andExpect(status().isCreated()));

        assertConCifras(editado, "1000.00", "300.00");
        assertConCifras(creado, null, "0");
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", java.math.BigDecimal.class,
                creado.get("id").asLong())).isNull();
    }

    @Test
    @DisplayName("renombrar o editar un cliente NO pisa su saldo ni su límite, ni toca el libro")
    void editarNoPisaLaCuenta() throws Exception {
        Tienda t = tienda();
        String vendedor = login(t.emailVendedor());
        long id = crearOk(vendedor, clienteConDoc("Ana", "DNI", "12345678"));
        cuenta(t, id, "1000.00", "300.00");

        putJson("/api/clientes/" + id, vendedor, clienteConDoc("Ana Gómez", "DNI", "12345678")).andExpect(status().isOk());

        var fila = jdbc.queryForMap("select razon_social, saldo, limite_cuenta_corriente from cliente where id = ?", id);
        assertThat(fila.get("razon_social")).isEqualTo("Ana Gómez");
        assertThat((java.math.BigDecimal) fila.get("saldo")).isEqualByComparingTo("300.00");
        assertThat((java.math.BigDecimal) fila.get("limite_cuenta_corriente")).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class, id))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select coalesce(sum(debe - haber), 0) from movimiento_cuenta_cliente "
                + "where cliente_id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("300.00");
    }

    // ---------------------------------------------------------------- desactivar con saldo (CC-020)

    private static final String CON_SALDO = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";

    @Test
    @DisplayName("desactivar con saldo 500 es 422 con el mensaje exacto y el cliente sigue activo")
    void desactivarConDeuda() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long id = crearOk(dueno, cliente("Deudor"));
        cuenta(t, id, "1000.00", "500.00");

        JsonNode error = json(desactivar(dueno, id, false).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("message").asText()).isEqualTo(CON_SALDO);
        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("desactivar con saldo a favor (−50) también es 422")
    void desactivarConSaldoAFavor() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long id = crearOk(dueno, cliente("Acreedor"));
        cuenta(t, id, null, "-50.00");

        JsonNode error = json(desactivar(dueno, id, false).andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("message").asText()).isEqualTo(CON_SALDO);
        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("desactivar con saldo 0 es 200 (aunque tenga cuenta habilitada) y reactivar con saldo es siempre 200")
    void desactivarConSaldoCeroYReactivar() throws Exception {
        Tienda t = tienda();
        String dueno = login(t.emailDueno());
        long id = crearOk(dueno, cliente("Al día"));
        cuenta(t, id, "1000.00", "0.00");

        assertThat(json(desactivar(dueno, id, false).andExpect(status().isOk())).get("activo").asBoolean()).isFalse();
        // queda debiendo estando desactivado (p. ej. una venta que entró antes): reactivar nunca se rechaza
        cuenta(t, id, null, "200.00");
        assertThat(json(desactivar(dueno, id, true).andExpect(status().isOk())).get("activo").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("desactivar un cliente ajeno o que no existe es 404, antes del 422; el Vendedor sigue en 403 con saldo")
    void desactivarAjenoInexistenteYVendedor() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deAjena = crearOk(login(a.emailDueno()), cliente("Deudor ajeno"));
        cuenta(a, deAjena, "1000.00", "500.00");

        desactivar(login(b.emailDueno()), deAjena, false).andExpect(status().isNotFound());
        desactivar(login(b.emailDueno()), Long.MAX_VALUE, false).andExpect(status().isNotFound());
        desactivar(login(a.emailVendedor()), deAjena, false).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, deAjena)).isTrue();
    }
}
