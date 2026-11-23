package com.ferreteria.menu;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MenuIntegrationTest extends IntegrationTestBase {

    private static final String MENU = "/api/menu";
    private static final String ROLES = MENU + "/disposiciones/roles/";
    private static final String USUARIOS = MENU + "/disposiciones/usuarios/";
    private static final String ORIGINAL = "{\"origen\":\"ORIGINAL\",\"disposicion\":null}";
    private static final String BASE = """
            {"grupos":[{"id":"ventas","rotulo":"Mostrador diario","tono":"ventas","claves":["presupuestos","caja"]}],
             "ocultas":["remitos"]}
            """;
    private static final String AJUSTE = BASE.replace("Mostrador diario", "Mi menú");
    private static final String MENSAJE_USUARIO = "Usuario no encontrado";
    private static final String MENSAJE_PROHIBIDO = "No tenés permiso para hacer esto";
    private static final String MENSAJE_PANTALLA = "El menú de un Vendedor no puede incluir opciones de solo Dueño.";

    @Test
    void resuelveUsuarioRolOriginalYRestaurarBajaUnNivel() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String vendedor = login(tienda.vendedor());
        String rol = ROLES + "VENDEDOR";
        String usuario = USUARIOS + tienda.vendedorId();

        original(getResult(MENU, vendedor));
        original(getResult(MENU, dueno));
        original(getResult(rol, dueno));
        original(getResult(usuario, dueno));

        respuesta(putJson(rol, dueno, BASE), "ROL", BASE);
        respuesta(getResult(rol, dueno), "ROL", BASE);
        respuesta(getResult(MENU, vendedor), "ROL", BASE);
        respuesta(getResult(usuario, dueno), "ROL", BASE);
        original(getResult(MENU, dueno));

        respuesta(putJson(usuario, dueno, AJUSTE), "USUARIO", AJUSTE);
        respuesta(getResult(usuario, dueno), "USUARIO", AJUSTE);
        respuesta(getResult(MENU, vendedor), "USUARIO", AJUSTE);
        respuesta(getResult(rol, dueno), "ROL", BASE);

        borrar(usuario, dueno);
        borrar(usuario, dueno);
        respuesta(getResult(MENU, vendedor), "ROL", BASE);
        respuesta(getResult(usuario, dueno), "ROL", BASE);
        borrar(rol, dueno);
        borrar(rol, dueno);
        original(getResult(MENU, vendedor));
        original(getResult(usuario, dueno));
        original(getResult(rol, dueno));
        assertThat(filas(tienda)).isEmpty();
    }

    @Test
    void vendedorNoAdministraDisposicionesNiConUnCuerpoIlegible() throws Exception {
        Tienda tienda = tienda();
        String vendedor = login(tienda.vendedor());
        String dueno = login(tienda.dueno());
        List<String> rutas = List.of(ROLES + "VENDEDOR", USUARIOS + tienda.vendedorId());
        respuesta(putJson(rutas.get(0), dueno, BASE), "ROL", BASE);
        respuesta(putJson(rutas.get(1), dueno, AJUSTE), "USUARIO", AJUSTE);
        var antes = filas(tienda);
        for (String ruta : rutas) {
            error(getResult(ruta, vendedor), 403, MENSAJE_PROHIBIDO);
            error(putJson(ruta, vendedor, BASE), 403, MENSAJE_PROHIBIDO);
            error(putJson(ruta, vendedor, "{"), 403, MENSAJE_PROHIBIDO);
            error(delete(ruta, vendedor), 403, MENSAJE_PROHIBIDO);
        }
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    @Test
    void superAdminNoTieneMenuDeFerreteria() throws Exception {
        error(getResult(MENU, login("admin@demo.local")), 403, "Este recurso requiere una ferretería");
    }

    @Test
    void vendedorNoPuedeLeerNiCambiarLaBaseDelDueno() throws Exception {
        Tienda tienda = tienda();
        String ruta = ROLES + "DUENO";
        respuesta(putJson(ruta, login(tienda.dueno()), AJUSTE), "ROL", AJUSTE);
        String vendedor = login(tienda.vendedor());
        var antes = filas(tienda);

        error(getResult(ruta, vendedor), 403, MENSAJE_PROHIBIDO);
        assertThat(filas(tienda)).isEqualTo(antes);
        error(putJson(ruta, vendedor, BASE), 403, MENSAJE_PROHIBIDO);
        assertThat(filas(tienda)).isEqualTo(antes);
        error(delete(ruta, vendedor), 403, MENSAJE_PROHIBIDO);
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    @Test
    void superAdminNoPuedeLeerNiCambiarDisposiciones() throws Exception {
        Tienda tienda = tienda();
        respuesta(putJson(ROLES + "VENDEDOR", login(tienda.dueno()), AJUSTE), "ROL", AJUSTE);
        String admin = login("admin@demo.local");
        // /usuarios/1 no apunta a la tienda creada para la prueba, por eso se compara toda la tabla.
        String consulta = "select * from menu_disposicion order by id";
        var antes = jdbc.queryForList(consulta);
        for (String ruta : List.of(ROLES + "VENDEDOR", USUARIOS + "1")) {
            error(getResult(ruta, admin), 403, MENSAJE_PROHIBIDO);
            assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
            error(putJson(ruta, admin, BASE), 403, MENSAJE_PROHIBIDO);
            assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
            error(delete(ruta, admin), 403, MENSAJE_PROHIBIDO);
            assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        }
    }

    @Test
    void tiendasAisladasEnLosDosAlcances() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        String duenoA = login(a.dueno());
        String duenoB = login(b.dueno());
        String vendedorB = login(b.vendedor());
        String rol = ROLES + "VENDEDOR";
        String usuarioA = USUARIOS + a.vendedorId();
        respuesta(putJson(rol, duenoA, BASE), "ROL", BASE);
        respuesta(putJson(usuarioA, duenoA, AJUSTE), "USUARIO", AJUSTE);
        var antesA = filas(a);
        var antesB = filas(b);
        original(getResult(rol, duenoB));
        original(getResult(MENU, vendedorB));
        for (String ruta : List.of(usuarioA, USUARIOS + Long.MAX_VALUE)) {
            error(getResult(ruta, duenoB), 404, MENSAJE_USUARIO);
            error(putJson(ruta, duenoB, BASE), 404, MENSAJE_USUARIO);
            error(delete(ruta, duenoB), 404, MENSAJE_USUARIO);
        }
        assertThat(filas(a)).isEqualTo(antesA);
        assertThat(filas(b)).isEqualTo(antesB);

        respuesta(putJson(rol, duenoB, AJUSTE), "ROL", AJUSTE);
        respuesta(getResult(MENU, vendedorB), "ROL", AJUSTE);
        respuesta(getResult(rol, duenoA), "ROL", BASE);
        borrar(rol, duenoB);
        original(getResult(rol, duenoB));
        respuesta(getResult(rol, duenoA), "ROL", BASE);
        respuesta(getResult(usuarioA, duenoA), "USUARIO", AJUSTE);
        assertThat(filas(a)).isEqualTo(antesA);
        assertThat(filas(b)).isEmpty();
    }

    @Test
    void rolInvalidoDa404EnLosTresMetodos() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        for (String rol : List.of("SUPER_ADMIN", "inexistente")) {
            String ruta = ROLES + rol;
            error(getResult(ruta, dueno), 404, "Rol no encontrado");
            error(putJson(ruta, dueno, BASE), 404, "Rol no encontrado");
            error(delete(ruta, dueno), 404, "Rol no encontrado");
        }
        assertThat(filas(tienda)).isEmpty();
    }

    @Test
    void rechazaPantallasDeDuenoEnAmbosAlcancesDeVendedorSinCambiarElMenu() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String rol = ROLES + "VENDEDOR";
        String usuario = USUARIOS + tienda.vendedorId();
        respuesta(putJson(rol, dueno, BASE), "ROL", BASE);
        respuesta(putJson(usuario, dueno, AJUSTE), "USUARIO", AJUSTE);
        var antes = filas(tienda);
        for (String ruta : List.of(rol, usuario)) {
            error(putJson(ruta, dueno, BASE.replace("\"caja\"", "\"ventas\"")), 400, MENSAJE_PANTALLA);
            error(putJson(ruta, dueno, BASE.replace("\"remitos\"", "\"usuarios\"")), 400, MENSAJE_PANTALLA);
        }
        assertThat(filas(tienda)).isEqualTo(antes);
        respuesta(getResult(rol, dueno), "ROL", BASE);
        respuesta(getResult(usuario, dueno), "USUARIO", AJUSTE);
    }

    @Test
    void rechazaGruposYClavesInvalidosEnAmbosAlcancesSinCambiarLasFilas() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String rol = ROLES + "VENDEDOR";
        String usuario = USUARIOS + tienda.vendedorId();
        respuesta(putJson(rol, dueno, BASE), "ROL", BASE);
        respuesta(putJson(usuario, dueno, AJUSTE), "USUARIO", AJUSTE);
        var antes = filas(tienda);

        String dosGrupos = """
                {"grupos":[{"id":"uno","rotulo":"Primero","tono":"ventas","claves":["caja"]},
                           {"id":"dos","rotulo":"%s","tono":"ventas","claves":["%s"]}],"ocultas":[]}
                """;
        String onceGrupos = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> """
                        {"id":"grupo-%d","rotulo":"Grupo %d","tono":"ventas","claves":[]}
                        """.formatted(i, i))
                .collect(Collectors.joining(",", "{\"grupos\":[", "],\"ocultas\":[]}"));
        record Caso(String cuerpo, String mensaje) {}
        var casos = List.of(
                new Caso(BASE.replace("Mostrador diario", ""), "Poné un nombre para el grupo."),
                new Caso(BASE.replace("Mostrador diario", "   "), "Poné un nombre para el grupo."),
                new Caso(dosGrupos.formatted("pRIMERO", "remitos"), "Ya hay un grupo con ese nombre."),
                new Caso(onceGrupos, "Podés tener hasta 10 grupos."),
                new Caso(BASE.replace("Mostrador diario", "x".repeat(31)),
                        "El nombre del grupo puede tener hasta 30 caracteres."),
                new Caso(BASE.replace("\"tono\":\"ventas\"", "\"tono\":\"azul\""), "Elegí un color válido para el grupo."),
                new Caso(BASE.replace("\"caja\"", "\"inventada\""), "Hay una opción de menú que no existe."),
                new Caso(dosGrupos.formatted("Segundo", "caja"), "Ya pusiste esa opción en el menú."),
                new Caso(BASE.replace("\"remitos\"", "\"caja\""), "Ya pusiste esa opción en el menú."));
        for (String ruta : List.of(rol, usuario)) {
            for (Caso caso : casos) {
                error(putJson(ruta, dueno, caso.cuerpo()), 400, caso.mensaje());
                assertThat(filas(tienda)).isEqualTo(antes);
            }
        }
    }

    @Test
    void rechazaCuerposInvalidosYUnIdNoNumericoSinCambiarLasFilas() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String rol = ROLES + "VENDEDOR";
        String usuario = USUARIOS + tienda.vendedorId();
        respuesta(putJson(rol, dueno, BASE), "ROL", BASE);
        respuesta(putJson(usuario, dueno, AJUSTE), "USUARIO", AJUSTE);
        var antes = filas(tienda);

        for (String ruta : List.of(rol, usuario)) {
            error(putJson(ruta, dueno, "{"), 400, "El cuerpo del pedido no es válido");
            assertThat(filas(tienda)).isEqualTo(antes);
            error(putJson(ruta, dueno, "{\"grupos\":null,\"ocultas\":[]}"), 400,
                    "La disposición del menú no está completa.");
            assertThat(filas(tienda)).isEqualTo(antes);
        }
        error(putJson(USUARIOS + "abc", dueno, BASE), 400, "Parámetro inválido: usuarioId");
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    @Test
    void putReemplazaConJsonCanonicoSinDuplicarInclusoSiElUsuarioEstaInactivo() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        jdbc.update("update usuario set activo = false where id = ?", tienda.vendedorId());
        String usuario = USUARIOS + tienda.vendedorId();
        String rol = ROLES + "DUENO";
        String baseDueno = BASE.replace("\"caja\"", "\"ventas\"");
        String ajusteDueno = baseDueno.replace("Mostrador diario", "Mi menú");
        respuesta(putJson(rol, dueno, baseDueno), "ROL", baseDueno);
        respuesta(putJson(usuario, dueno, BASE), "USUARIO", BASE);
        var antes = filas(tienda);
        for (int i = 0; i < 2; i++) {
            respuesta(putJson(rol, dueno, ajusteDueno), "ROL", ajusteDueno);
            respuesta(putJson(usuario, dueno, AJUSTE.replace("Mi menú", "   Mi menú   ")), "USUARIO", AJUSTE);
        }
        var despues = filas(tienda);
        assertThat(despues).hasSize(2);
        assertThat(despues.stream().map(fila -> fila.get("id")).toList())
                .containsExactlyElementsOf(antes.stream().map(fila -> fila.get("id")).toList());
        assertThat(objectMapper.readTree((String) despues.get(1).get("contenido")))
                .isEqualTo(objectMapper.readTree(AJUSTE));
        respuesta(getResult(MENU, dueno), "ROL", ajusteDueno);
        respuesta(getResult(usuario, dueno), "USUARIO", AJUSTE);
        borrar(usuario, dueno);
        original(getResult(usuario, dueno));
    }

    @Test
    void contenidoIlegibleRespondeOriginalAunqueExistaUnaBase() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String vendedor = login(tienda.vendedor());
        respuesta(putJson(ROLES + "VENDEDOR", dueno, BASE), "ROL", BASE);
        respuesta(putJson(USUARIOS + tienda.vendedorId(), dueno, AJUSTE), "USUARIO", AJUSTE);
        // MySQL no admite JSON sintácticamente roto, pero sí uno que no tiene la forma del DTO.
        jdbc.update("update menu_disposicion set contenido = ? where ferreteria_id = ? and usuario_id = ?",
                "{\"grupos\":\"ilegible\",\"ocultas\":[]}", tienda.id(), tienda.vendedorId());
        var antes = filas(tienda);
        original(getResult(MENU, vendedor));
        original(getResult(USUARIOS + tienda.vendedorId(), dueno));
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    @Test
    void alLeerIgnoraClavesRetiradasYNoEntregaPantallasDeDuenoALVendedor() throws Exception {
        Tienda tienda = tienda();
        String dueno = login(tienda.dueno());
        String vendedor = login(tienda.vendedor());
        String usuario = USUARIOS + tienda.vendedorId();
        respuesta(putJson(usuario, dueno, BASE), "USUARIO", BASE);
        // Simula una opción retirada del catálogo y una antigua disposición de cuando era Dueño.
        jdbc.update("update menu_disposicion set contenido = ? where ferreteria_id = ? and usuario_id = ?",
                BASE.replace("\"caja\"", "\"caja\",\"retirada\",\"ventas\"")
                        .replace("\"remitos\"", "\"remitos\",\"retirada-oculta\",\"usuarios\""),
                tienda.id(), tienda.vendedorId());
        var antes = filas(tienda);
        respuesta(getResult(MENU, vendedor), "USUARIO", BASE);
        respuesta(getResult(usuario, dueno), "USUARIO", BASE);
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    @Test
    void alCambiarDeDuenoAVendedorElAjustePropioNoEntregaPantallasDeDueno() throws Exception {
        Tienda tienda = tienda();
        long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, tienda.dueno());
        String dueno = login(tienda.dueno());
        String ajusteDueno = BASE.replace("\"caja\"", "\"caja\",\"ventas\"")
                .replace("\"remitos\"", "\"remitos\",\"usuarios\"");
        respuesta(putJson(USUARIOS + usuarioId, dueno, ajusteDueno), "USUARIO", ajusteDueno);
        respuesta(getResult(MENU, dueno), "USUARIO", ajusteDueno);
        var antes = filas(tienda);

        assertThat(jdbc.update("update usuario set rol = 'VENDEDOR' where id = ? and rol = 'DUENO'", usuarioId))
                .isOne();
        String vendedor = login(tienda.dueno());
        respuesta(getResult(MENU, vendedor), "USUARIO", BASE);
        assertThat(filas(tienda)).isEqualTo(antes);
    }

    private void original(ResultActions resultado) throws Exception {
        assertThat(cuerpo(resultado.andExpect(status().isOk()))).isEqualTo(objectMapper.readTree(ORIGINAL));
    }

    private void respuesta(ResultActions resultado, String origen, String disposicion) throws Exception {
        JsonNode esperado = objectMapper.createObjectNode().put("origen", origen)
                .set("disposicion", objectMapper.readTree(disposicion));
        assertThat(cuerpo(resultado.andExpect(status().isOk()))).isEqualTo(esperado);
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void borrar(String ruta, String token) throws Exception {
        delete(ruta, token).andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    private static void error(ResultActions resultado, int codigo, String mensaje) throws Exception {
        resultado.andExpect(status().is(codigo)).andExpect(jsonPath("$.status").value(codigo))
                .andExpect(jsonPath("$.message").value(mensaje));
    }

    private List<Map<String, Object>> filas(Tienda tienda) {
        return jdbc.queryForList("select * from menu_disposicion where ferreteria_id = ? order by id", tienda.id());
    }

    private Tienda tienda() {
        String sufijo = unico("menu");
        String cuit = "30-8%07d-%d".formatted(ThreadLocalRandom.current().nextInt(10_000_000),
                ThreadLocalRandom.current().nextInt(10));
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String dueno = "dueno-" + sufijo + "@menu.demo";
        String vendedor = "vendedor-" + sufijo + "@menu.demo";
        jdbc.update("""
                insert into usuario (ferreteria_id, email, password_hash, nombre, rol)
                select ?, ?, password_hash, 'Dueño Menú', 'DUENO' from usuario where email = 'dueno@tornillo.demo'
                """, id, dueno);
        jdbc.update("""
                insert into usuario (ferreteria_id, email, password_hash, nombre, rol)
                select ?, ?, password_hash, 'Vendedor Menú', 'VENDEDOR' from usuario where email = 'vendedor@tornillo.demo'
                """, id, vendedor);
        long vendedorId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, vendedor);
        return new Tienda(id, dueno, vendedor, vendedorId);
    }

    private record Tienda(long id, String dueno, String vendedor, long vendedorId) {}
}
