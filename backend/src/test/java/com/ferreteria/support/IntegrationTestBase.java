package com.ferreteria.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de los tests de integracion con MySQL real (Testcontainers). Necesita Docker corriendo:
 * si no hay Docker, falla (a proposito: no se saltea en silencio).
 *
 * Un solo MySQL para todas las clases que la extienden, arrancado en un bloque static. No se usa
 * @Container: JUnit lo pararia al terminar la primera clase y Spring seguiria reusando el contexto
 * cacheado apuntando a un contenedor muerto. Ryuk lo limpia al terminar la JVM.
 *
 * La base no se revierte entre tests (y los datos demo de db/dev-data ya estan cargados): cada test
 * crea sus propios datos con nombres y codigos unicos (ver {@link #unico(String)}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    /** Devuelve el JWT del usuario demo (contrasena demo1234). */
    protected String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    /** GET que espera 200 y devuelve el cuerpo como JSON. */
    protected JsonNode getJson(String url, String token) throws Exception {
        String body = getResult(url, token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** GET sin expectativas, para probar codigos de error. */
    protected ResultActions getResult(String url, String token) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    protected ResultActions postJson(String url, String token, String json) throws Exception {
        return mvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /**
     * POST multipart: la parte "archivo" y, si no es null, la parte "datos" como JSON. Se llama
     * postMultipart y no multipart para no tapar al builder estatico de MockMvcRequestBuilders.
     */
    protected ResultActions postMultipart(String url, String token, MockMultipartFile archivo, Object datos)
            throws Exception {
        MockMultipartHttpServletRequestBuilder pedido = MockMvcRequestBuilders.multipart(url).file(archivo);
        if (datos != null) {
            byte[] json = datos instanceof byte[] crudo ? crudo : objectMapper.writeValueAsBytes(datos);
            pedido.file(new MockMultipartFile("datos", "", MediaType.APPLICATION_JSON_VALUE, json));
        }
        return mvc.perform(pedido.header("Authorization", "Bearer " + token));
    }

    /** La parte "archivo" de una importacion. */
    protected static MockMultipartFile archivo(String nombre, byte[] contenido) {
        return new MockMultipartFile("archivo", nombre, MediaType.APPLICATION_OCTET_STREAM_VALUE, contenido);
    }

    protected ResultActions putJson(String url, String token, String json) throws Exception {
        return mvc.perform(put(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions delete(String url, String token) throws Exception {
        // Calificado: un metodo propio llamado delete tapa al import static del mismo nombre.
        return mvc.perform(MockMvcRequestBuilders.delete(url).header("Authorization", "Bearer " + token));
    }

    /** Valores del campo "nombre" de cada elemento de un array JSON. */
    protected static List<String> nombres(JsonNode array) {
        List<String> nombres = new ArrayList<>();
        array.forEach(n -> nombres.add(n.get("nombre").asText()));
        return nombres;
    }

    /** Prefijo + sufijo aleatorio: codigos y nombres que no chocan con los de otros tests. */
    protected static String unico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
