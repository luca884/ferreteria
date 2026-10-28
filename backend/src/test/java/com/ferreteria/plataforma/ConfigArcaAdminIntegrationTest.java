package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * CU-01 paso 2 (D3, S2 y S3): el super admin pone la marca «de prueba», asigna o quita el simulador (punto de venta 1) y
 * prende o apaga la facturación de una ferretería, con el cerrojo del ambiente. Cada test crea sus ferreterías: ninguno
 * toca la configuración de ARCA de las demo.
 */
class ConfigArcaAdminIntegrationTest extends PlataformaTestBase {

    private static final AtomicInteger COMPROBANTES = new AtomicInteger(500000);
    private static final String SIN_MARCA = "El simulador solo se asigna a una ferretería marcada «de prueba».";
    private static final String MARCA_CON_SIMULADOR =
            "No se puede sacar la marca «de prueba» mientras la ferretería use el simulador: quitale el simulador primero.";
    private static final String CON_REGISTROS_REALES =
            "La ferretería ya tiene comprobantes de homologación o producción: no se le puede asignar el simulador.";
    private static final String CON_REGISTROS_ABIERTOS =
            "La ferretería tiene comprobantes fiscales sin terminar: el ambiente no se puede cambiar hasta que se resuelvan.";
    private static final String QUITAR_CON_FACTURACION =
            "Primero deshabilitá la facturación: no se puede quitar el ambiente mientras esté habilitada.";
    private static final String HABILITAR_SIN_AMBIENTE =
            "Para habilitar la facturación, la ferretería necesita un ambiente de ARCA y un punto de venta.";

    private String admin;

    @BeforeEach
    void entrar() throws Exception {
        admin = tokenSuperAdmin();
    }

    // ---- ayudas ----

    private static String url(long ferreteria, String ruta) {
        return BASE + "/" + ferreteria + "/" + ruta;
    }

    private ResultActions dePrueba(long ferreteria, boolean valor) throws Exception {
        return patchJson(url(ferreteria, "de-prueba"), admin, json("dePrueba", valor));
    }

    private ResultActions simulador(long ferreteria, boolean valor) throws Exception {
        return patchJson(url(ferreteria, "simulador"), admin, json("simulador", valor));
    }

    private ResultActions facturacion(long ferreteria, boolean valor) throws Exception {
        return patchJson(url(ferreteria, "facturacion"), admin, json("habilitada", valor));
    }

    private static ResultActions regla(ResultActions r, String mensaje) throws Exception {
        return r.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message", is(mensaje)));
    }

    private JsonNode cuerpo(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Una ferretería de prueba con el simulador asignado. */
    private long conSimulador() throws Exception {
        long f = crearFerreteria("Simulador");
        dePrueba(f, true).andExpect(status().isOk());
        simulador(f, true).andExpect(status().isOk());
        return f;
    }

    /** Los datos de CU-01 que el alta deja vacíos: domicilio comercial, inicio de actividades e Ingresos Brutos. */
    private void datosCompletos(long ferreteria) {
        jdbc.update("update ferreteria set domicilio_comercial = 'Av. Independencia 1234, Mar del Plata', "
                + "inicio_actividades = '2015-03-01', ingresos_brutos = '901-123456-7' where id = ?", ferreteria);
    }

    private Map<String, Object> fila(long ferreteria) {
        return jdbc.queryForMap("select de_prueba, ambiente, punto_venta, facturacion_habilitada, habilitada_en "
                + "from config_arca where ferreteria_id = ?", ferreteria);
    }

    private long filas(long ferreteria) {
        return jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Long.class, ferreteria);
    }

    private JsonNode enLaLista(long ferreteria) throws Exception {
        for (JsonNode f : getJson(BASE, admin).get("ferreterias")) {
            if (f.get("id").asLong() == ferreteria) {
                return f;
            }
        }
        throw new AssertionError("la ferretería " + ferreteria + " no está en la lista");
    }

    /**
     * Un registro fiscal de la ferretería (por SQL: el motor llega en T6) en ese ambiente y estado, sobre un comprobante
     * propio. AUTORIZADO lleva número, CAE y resultado A; RECHAZADO, resultado R; los demás, nada.
     */
    private void registro(long ferreteria, String ambiente, String estado) {
        int numero = COMPROBANTES.incrementAndGet();
        long usuario = crearUsuario(ferreteria, unico("arca") + "@registro.test", "Vendedor", "VENDEDOR");
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)",
                ferreteria, numero, unico("arca"), usuario);
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?",
                Long.class, ferreteria, numero);
        boolean autorizado = estado.equals("AUTORIZADO");
        String resultado = autorizado ? "A" : estado.equals("RECHAZADO") ? "R" : null;
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, numero, cbte_fch, estado, cae, cae_vencimiento, resultado, receptor_doc_tipo, "
                + "receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, emisor_razon_social, emisor_condicion_iva, "
                + "emisor_domicilio_comercial, emisor_ingresos_brutos, emisor_inicio_actividades, imp_total, imp_neto, "
                + "imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
                + "values (?, ?, ?, 'FACTURA', 'B', 6, 1, ?, ?, ?, ?, ?, ?, 99, 0, 5, '30-00000001-5', 'Ferretería', "
                + "'RESPONSABLE_INSCRIPTO', 'Av. Independencia 1234', '901-123456-7', '2015-03-01', 121.00, 100.00, "
                + "21.00, 0, 0, '2026-10-28 13:00:00')",
                ferreteria, comprobante, ambiente, autorizado ? numero : null, autorizado ? "2026-10-28" : null, estado,
                autorizado ? "12345678901234" : null, autorizado ? "2026-11-07" : null, resultado);
    }

    // ---- roles ----

    @Test
    @DisplayName("el Dueño y el Vendedor reciben 403 en las tres rutas aun con el cuerpo inválido; sin token, 401")
    void soloElSuperAdmin() throws Exception {
        String dueno = login("dueno@tornillo.demo");
        String vendedor = login("vendedor@tornillo.demo");
        List<String> rutas = List.of(url(1, "de-prueba"), url(1, "simulador"), url(1, "facturacion"));
        List<Map<String, Object>> antes = jdbc.queryForList("select * from config_arca where ferreteria_id = 1");

        for (String token : List.of(dueno, vendedor)) {
            for (String ruta : rutas) {
                patchJson(ruta, token, "{}").andExpect(status().isForbidden());
                patchJson(ruta, token, "no es json").andExpect(status().isForbidden());
            }
        }
        for (String ruta : rutas) {
            mvc.perform(patch(ruta).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForList("select * from config_arca where ferreteria_id = 1")).isEqualTo(antes);
    }

    @Test
    @DisplayName("el Dueño de una ferretería no puede marcarla, darle el simulador ni prenderle la facturación")
    void elDuenoNoTocaLaSuya() throws Exception {
        long f = crearFerreteria("Dueño propio");
        datosCompletos(f);
        String email = unico("dueno") + "@arca.test";
        crearUsuario(f, email, "Dueño", "DUENO");
        String dueno = login(email);

        patchJson(url(f, "de-prueba"), dueno, json("dePrueba", true)).andExpect(status().isForbidden());
        patchJson(url(f, "simulador"), dueno, json("simulador", true)).andExpect(status().isForbidden());
        patchJson(url(f, "facturacion"), dueno, json("habilitada", true)).andExpect(status().isForbidden());

        assertThat(filas(f)).isZero();
    }

    // ---- marca «de prueba» ----

    @Test
    @DisplayName("sin fila la ferretería está sin marca, sin ambiente y sin facturar; marcarla crea la fila y desmarcarla la saca")
    void marca() throws Exception {
        long f = crearFerreteria("Marca");
        JsonNode antes = enLaLista(f);
        assertThat(antes.get("dePrueba").asBoolean()).isFalse();
        assertThat(antes.get("ambiente").isNull()).isTrue();
        assertThat(antes.get("puntoVenta").isNull()).isTrue();
        assertThat(antes.get("facturacionHabilitada").asBoolean()).isFalse();
        assertThat(antes.get("habilitadaEn").isNull()).isTrue();
        assertThat(filas(f)).isZero();

        dePrueba(f, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) f)))
                .andExpect(jsonPath("$.dePrueba", is(true)))
                .andExpect(jsonPath("$.ambiente", nullValue()));
        assertThat(fila(f)).containsEntry("de_prueba", true).containsEntry("ambiente", null)
                .containsEntry("facturacion_habilitada", false);

        dePrueba(f, false).andExpect(status().isOk()).andExpect(jsonPath("$.dePrueba", is(false)));
        assertThat(fila(f)).containsEntry("de_prueba", false);
    }

    @Test
    @DisplayName("con el simulador la marca no se saca: 422 y queda puesta")
    void laMarcaNoSeSacaConElSimulador() throws Exception {
        long f = conSimulador();

        regla(dePrueba(f, false), MARCA_CON_SIMULADOR);

        assertThat(fila(f)).containsEntry("de_prueba", true).containsEntry("ambiente", "SIMULADOR");
    }

    // ---- simulador ----

    @Test
    @DisplayName("asignar el simulador sin la marca da 422 y no crea la fila")
    void simuladorSinMarca() throws Exception {
        long f = crearFerreteria("Sin marca");

        regla(simulador(f, true), SIN_MARCA);

        assertThat(filas(f)).isZero();
    }

    @Test
    @DisplayName("asignar el simulador a una ferretería de prueba deja el punto de venta en 1")
    void simuladorConPuntoDeVenta1() throws Exception {
        long f = crearFerreteria("Punto 1");
        dePrueba(f, true).andExpect(status().isOk());

        simulador(f, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.ambiente", is("SIMULADOR")))
                .andExpect(jsonPath("$.puntoVenta", is(1)))
                .andExpect(jsonPath("$.facturacionHabilitada", is(false)));

        assertThat(fila(f)).containsEntry("ambiente", "SIMULADOR").containsEntry("punto_venta", 1);
    }

    @Test
    @DisplayName("un registro de homologación o producción, aunque esté cerrado, traba el simulador: 422")
    void registrosRealesTrabanElSimulador() throws Exception {
        for (String ambiente : List.of("HOMOLOGACION", "PRODUCCION")) {
            long f = crearFerreteria("Real " + ambiente);
            dePrueba(f, true).andExpect(status().isOk());
            registro(f, ambiente, "RECHAZADO");

            regla(simulador(f, true), CON_REGISTROS_REALES);

            assertThat(fila(f)).containsEntry("ambiente", null).containsEntry("punto_venta", null);
        }
    }

    @Test
    @DisplayName("un registro abierto traba asignar y quitar el simulador; los cerrados del simulador no traban")
    void registrosAbiertosTrabanElAmbiente() throws Exception {
        long cerrados = crearFerreteria("Cerrados");
        dePrueba(cerrados, true).andExpect(status().isOk());
        for (String estado : List.of("AUTORIZADO", "RECHAZADO", "FUERA_DE_FECHA")) {
            registro(cerrados, "SIMULADOR", estado);
        }
        simulador(cerrados, true).andExpect(status().isOk()).andExpect(jsonPath("$.ambiente", is("SIMULADOR")));

        registro(cerrados, "SIMULADOR", "PENDIENTE");
        regla(simulador(cerrados, false), CON_REGISTROS_ABIERTOS);
        assertThat(fila(cerrados)).containsEntry("ambiente", "SIMULADOR").containsEntry("punto_venta", 1);

        long abierta = crearFerreteria("Abierta");
        dePrueba(abierta, true).andExpect(status().isOk());
        registro(abierta, "SIMULADOR", "PENDIENTE");
        regla(simulador(abierta, true), CON_REGISTROS_ABIERTOS);
        assertThat(fila(abierta)).containsEntry("ambiente", null).containsEntry("punto_venta", null);
    }

    @Test
    @DisplayName("quitar el simulador exige la facturación apagada; apagada, lo quita junto con el punto de venta")
    void quitarElSimulador() throws Exception {
        long f = conSimulador();
        datosCompletos(f);
        facturacion(f, true).andExpect(status().isOk());

        regla(simulador(f, false), QUITAR_CON_FACTURACION);
        assertThat(fila(f)).containsEntry("ambiente", "SIMULADOR");

        facturacion(f, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.facturacionHabilitada", is(false)))
                .andExpect(jsonPath("$.habilitadaEn", nullValue()));
        simulador(f, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.ambiente", nullValue()))
                .andExpect(jsonPath("$.puntoVenta", nullValue()))
                .andExpect(jsonPath("$.dePrueba", is(true)));

        assertThat(fila(f)).containsEntry("ambiente", null).containsEntry("punto_venta", null)
                .containsEntry("facturacion_habilitada", false).containsEntry("habilitada_en", null);
    }

    // ---- facturación habilitada ----

    @Test
    @DisplayName("habilitar sin ambiente da 422 aunque los datos estén completos")
    void habilitarSinAmbiente() throws Exception {
        long f = crearFerreteria("Sin ambiente");
        datosCompletos(f);
        dePrueba(f, true).andExpect(status().isOk());

        regla(facturacion(f, true), HABILITAR_SIN_AMBIENTE);

        assertThat(fila(f)).containsEntry("facturacion_habilitada", false);
    }

    @Test
    @DisplayName("habilitar con un dato de CU-01 faltante da 422 y lo nombra; con todos, 200 con la fecha de activación")
    void habilitarExigeLosDatosDeCu01() throws Exception {
        Map<String, String> columnaPorDato = Map.of("domicilio comercial", "domicilio_comercial",
                "inicio de actividades", "inicio_actividades", "Ingresos Brutos", "ingresos_brutos");
        for (Map.Entry<String, String> dato : columnaPorDato.entrySet()) {
            long f = conSimulador();
            datosCompletos(f);
            jdbc.update("update ferreteria set " + dato.getValue() + " = null where id = ?", f);

            regla(facturacion(f, true), "Para habilitar la facturación falta un dato de la ferretería: " + dato.getKey()
                    + ". Lo carga el Dueño en «Datos de la ferretería».");
            assertThat(fila(f)).containsEntry("facturacion_habilitada", false).containsEntry("habilitada_en", null);
        }

        long f = conSimulador();
        regla(facturacion(f, true), "Para habilitar la facturación faltan datos de la ferretería: domicilio comercial, "
                + "inicio de actividades e Ingresos Brutos. Los carga el Dueño en «Datos de la ferretería».");

        datosCompletos(f);
        Instant antes = Instant.now();
        JsonNode habilitada = cuerpo(facturacion(f, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.facturacionHabilitada", is(true))));
        Instant despues = Instant.now();

        Instant habilitadaEn = Instant.parse(habilitada.get("habilitadaEn").asText());
        assertThat(habilitadaEn).isBetween(antes, despues);
        assertThat(fila(f)).containsEntry("facturacion_habilitada", true);
        assertThat(fila(f).get("habilitada_en")).isNotNull();
        assertThat(enLaLista(f).get("habilitadaEn").asText()).isEqualTo(habilitada.get("habilitadaEn").asText());
    }

    // ---- errores de forma ----

    @Test
    @DisplayName("una ferretería inexistente da 404 en las tres rutas, y un cuerpo sin el campo, 400 en ese campo")
    void inexistenteYSinCampo() throws Exception {
        dePrueba(999999999, true).andExpect(status().isNotFound());
        simulador(999999999, true).andExpect(status().isNotFound());
        facturacion(999999999, true).andExpect(status().isNotFound());

        long f = crearFerreteria("Sin campo");
        patchJson(url(f, "de-prueba"), admin, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dePrueba").exists());
        patchJson(url(f, "simulador"), admin, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.simulador").exists());
        patchJson(url(f, "facturacion"), admin, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.habilitada").exists());
        assertThat(filas(f)).isZero();
    }

    // ---- aislamiento ----

    @Test
    @DisplayName("aislamiento: cambiar la ferretería A no toca la B, y la lista muestra cada una con lo suyo")
    void aislamiento() throws Exception {
        long a = conSimulador();
        datosCompletos(a);
        long b = crearFerreteria("B");
        datosCompletos(b);
        dePrueba(b, true).andExpect(status().isOk());
        long c = crearFerreteria("C");

        facturacion(a, true).andExpect(status().isOk());

        assertThat(fila(b)).containsEntry("de_prueba", true).containsEntry("ambiente", null)
                .containsEntry("punto_venta", null).containsEntry("facturacion_habilitada", false);
        assertThat(filas(c)).isZero();
        JsonNode enA = enLaLista(a);
        assertThat(enA.get("dePrueba").asBoolean()).isTrue();
        assertThat(enA.get("ambiente").asText()).isEqualTo("SIMULADOR");
        assertThat(enA.get("puntoVenta").asInt()).isEqualTo(1);
        assertThat(enA.get("facturacionHabilitada").asBoolean()).isTrue();
        JsonNode enB = enLaLista(b);
        assertThat(enB.get("dePrueba").asBoolean()).isTrue();
        assertThat(enB.get("ambiente").isNull()).isTrue();
        assertThat(enB.get("facturacionHabilitada").asBoolean()).isFalse();
        JsonNode enC = enLaLista(c);
        assertThat(enC.get("dePrueba").asBoolean()).isFalse();
        assertThat(enC.get("ambiente").isNull()).isTrue();
        assertThat(enC.get("facturacionHabilitada").asBoolean()).isFalse();
    }
}
