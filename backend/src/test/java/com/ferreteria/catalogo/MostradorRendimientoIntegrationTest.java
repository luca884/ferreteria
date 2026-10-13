package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de CU-06 (R-012): con 5.000 y con 20.000 articulos en una ferreteria, cada busqueda del mostrador
 * (exacta, parcial de dos palabras, parcial sin resultados) tarda menos de 500 ms, tras un calentamiento.
 * Corre en una ferreteria PROPIA (creada por jdbc, con un Vendedor que reusa la contrasena demo) para no
 * ensuciar a los tenants demo. Armar los datos no se cronometra: se mide cada GET por el filtro de
 * seguridad, el servicio y MySQL reales. Se loguean mediana y maximo de cada grupo.
 */
class MostradorRendimientoIntegrationTest extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(MostradorRendimientoIntegrationTest.class);

    private static final Duration LIMITE = Duration.ofMillis(500);
    private static final int CALENTAMIENTO = 5;
    private static final int BUSQUEDAS = 20;
    private static final String[] TIPOS = {"Tornillo", "Tuerca", "Arandela", "Cable", "Pintura", "Martillo", "Taladro", "Llave"};
    private static final String[] VARIANTES = {"hexagonal", "autoperforante", "galvanizado", "reforzado", "inoxidable"};

    /** Crea la ferreteria con su Vendedor y {@code cantidad} articulos activos; devuelve el token del Vendedor. */
    private String ferreteriaConArticulos(String cuit, String email, int cantidad) throws Exception {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Rendimiento mostrador " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Rendimiento', 'VENDEDOR')", ferreteriaId, email, hash);

        List<Object[]> filas = new ArrayList<>(cantidad);
        for (int i = 1; i <= cantidad; i++) {
            String descripcion = "%s %s %05d".formatted(TIPOS[i % TIPOS.length], VARIANTES[i % VARIANTES.length], i);
            filas.add(new Object[] {ferreteriaId, "A-%05d".formatted(i), "779%010d".formatted(i), "P-%05d".formatted(i),
                    descripcion, "Estante " + (i % 40)});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, codigo_barras, codigo_proveedor, "
                + "descripcion, ubicacion, costo, precio_final, stock_actual, stock_minimo) "
                + "values (?, ?, ?, ?, ?, ?, 100.00, 170.00, 10, 2)", filas);
        long cargados = jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ?", Long.class, ferreteriaId);
        assertThat(cargados).isEqualTo(cantidad);
        return login(email);
    }

    /** Un GET con 200 cronometrado; devuelve cuanto tardo y deja el JSON en {@code respuesta[0]}. */
    private Duration medir(String token, String q, JsonNode[] respuesta) throws Exception {
        long inicio = System.nanoTime();
        String cuerpo = mvc.perform(get("/api/articulos/mostrador").param("q", q).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Duration duracion = Duration.ofNanos(System.nanoTime() - inicio);
        respuesta[0] = objectMapper.readTree(cuerpo);
        return duracion;
    }

    private void medirGrupo(String nombre, int articulos, String token, List<String> consultas, String tipoEsperado)
            throws Exception {
        List<Long> milisegundos = new ArrayList<>();
        JsonNode[] respuesta = new JsonNode[1];
        for (String q : consultas) {
            Duration duracion = medir(token, q, respuesta);
            milisegundos.add(duracion.toMillis());
            assertThat(respuesta[0].get("tipo").asText()).as("tipo de [%s]", q).isEqualTo(tipoEsperado);
            assertThat(duracion).as("[%s] con %d articulos", q, articulos).isLessThan(LIMITE);
        }
        Collections.sort(milisegundos);
        LOG.info("RENDIMIENTO CU-06 ({} articulos, {}): {} busquedas, mediana {} ms, maximo {} ms (limite {} ms)",
                articulos, nombre, milisegundos.size(), milisegundos.get(milisegundos.size() / 2),
                milisegundos.get(milisegundos.size() - 1), LIMITE.toMillis());
    }

    private void medirTodo(String token, int articulos) throws Exception {
        // Calentamiento: JIT, pool de conexiones y plan de la consulta; no se mide.
        JsonNode[] respuesta = new JsonNode[1];
        for (int i = 1; i <= CALENTAMIENTO; i++) {
            medir(token, "779%010d".formatted(i), respuesta);
            medir(token, "tornillo %05d".formatted(i), respuesta);
        }

        List<String> exactas = new ArrayList<>();
        List<String> dosPalabras = new ArrayList<>();
        List<String> muchosResultados = new ArrayList<>();
        List<String> sinResultados = new ArrayList<>();
        for (int i = 1; i <= BUSQUEDAS; i++) {
            int n = (i * 997) % articulos + 1;
            exactas.add("779%010d".formatted(n));
            // Palabra del tipo + el numero de serie: una coincidencia, pero obliga a recorrer el tenant.
            dosPalabras.add("%s %05d".formatted(TIPOS[n % TIPOS.length].toLowerCase(), n));
            // Dos palabras que comparten miles de articulos: hay que ordenar y cortar en 21.
            muchosResultados.add("%s %s".formatted(TIPOS[i % TIPOS.length].toLowerCase(), VARIANTES[i % VARIANTES.length]));
            sinResultados.add("zzqq%d xx%d".formatted(i, i));
        }
        medirGrupo("exactas", articulos, token, exactas, "EXACTO");
        medirGrupo("parciales de dos palabras", articulos, token, dosPalabras, "LISTA");
        medirGrupo("parciales con miles de coincidencias", articulos, token, muchosResultados, "LISTA");
        medirGrupo("parciales sin resultados", articulos, token, sinResultados, "SIN_RESULTADOS");
    }

    @Test
    void cincoMilArticulosBuscanEnMenosDeMediaSegundo() throws Exception {
        String token = ferreteriaConArticulos("30-76000001-1", "vendedor@rendimiento-mostrador-5k.demo", 5_000);

        medirTodo(token, 5_000);
    }

    @Test
    void veinteMilArticulosBuscanEnMenosDeMediaSegundo() throws Exception {
        String token = ferreteriaConArticulos("30-76000002-2", "vendedor@rendimiento-mostrador-20k.demo", 20_000);

        medirTodo(token, 20_000);
    }
}
