package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Limite de 25 MB de la subida de listas contra un Tomcat real (MockMvc no pasa por el parser de
 * multipart del contenedor). Levanta un segundo contexto con servidor en un puerto al azar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LimiteDeSubidaIntegrationTest extends IntegrationTestBase {

    private static final String LIMITE = "El archivo es demasiado grande. El máximo es 25 MB.";
    private static final String BORDE = "----limite-de-subida";
    private static final int MB = 1024 * 1024;

    @LocalServerPort
    int puerto;

    private final HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** CSV de una fila de datos cuya descripcion se rellena hasta llegar al tamano pedido. */
    private static byte[] csvDeTamano(String codigo, int bytes) {
        String cabecera = "Codigo;Descripcion;Costo\r\n" + codigo + ";";
        String cola = ";100\r\n";
        int relleno = bytes - cabecera.length() - cola.length();
        byte[] contenido = new byte[bytes];
        byte[] inicio = cabecera.getBytes(StandardCharsets.US_ASCII);
        byte[] fin = cola.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(inicio, 0, contenido, 0, inicio.length);
        Arrays.fill(contenido, inicio.length, inicio.length + relleno, (byte) 'a');
        System.arraycopy(fin, 0, contenido, inicio.length + relleno, fin.length);
        return contenido;
    }

    private HttpResponse<String> subir(String ruta, String token, byte[] archivo, String datosJson) throws Exception {
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(("--" + BORDE + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\"lista.csv\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        cuerpo.write(archivo);
        if (datosJson != null) {
            cuerpo.write(("\r\n--" + BORDE + "\r\nContent-Disposition: form-data; name=\"datos\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + datosJson).getBytes(StandardCharsets.UTF_8));
        }
        cuerpo.write(("\r\n--" + BORDE + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BORDE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                .build();
        return cliente.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String datos(long proveedorId, boolean aplicar) {
        String base = "\"proveedorId\":" + proveedorId + ",\"mapeo\":{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,"
                + "\"columnaDescripcion\":1,\"columnaCosto\":2}";
        String conteos = "\"conteosEsperados\":{\"suben\":0,\"bajan\":0,\"nuevos\":1,\"sinCambio\":0,\"noEstan\":0,\"conError\":0}";
        return "{" + base + (aplicar ? "," + conteos : "") + "}";
    }

    private long crearProveedor(String token) throws Exception {
        String cuerpo = postJson("/api/proveedores", token, "{\"nombre\":\"" + unico("PL") + "\"}")
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpo).get("id").asLong();
    }

    private long contar(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void unArchivoDe25Coma5MBDa413ConElMensajeEnLosTresEndpointsYLaBaseNoCambia() throws Exception {
        String token = login("dueno@tornillo.demo");
        long proveedor = crearProveedor(token);
        long articulos = contar("select count(*) from articulo");
        long historial = contar("select count(*) from historial_precio");
        long importaciones = contar("select count(*) from importacion_lista");
        byte[] grande = csvDeTamano("GRANDE", 25 * MB + MB / 2);

        for (String[] caso : new String[][] {
                {"/api/importaciones/muestra", null}, {"/api/importaciones/vista-previa", "vp"}, {"/api/importaciones", "ap"}}) {
            String datosJson = caso[1] == null ? null : datos(proveedor, "ap".equals(caso[1]));

            HttpResponse<String> respuesta = subir(caso[0], token, grande, datosJson);

            assertThat(respuesta.statusCode()).as(caso[0]).isEqualTo(413);
            JsonNode cuerpo = objectMapper.readTree(respuesta.body());
            assertThat(cuerpo.get("message").asText()).as(caso[0]).isEqualTo(LIMITE);
        }

        assertThat(contar("select count(*) from articulo")).isEqualTo(articulos);
        assertThat(contar("select count(*) from historial_precio")).isEqualTo(historial);
        assertThat(contar("select count(*) from importacion_lista")).isEqualTo(importaciones);
    }

    /** CSV con encabezado y {@code filas} filas de datos (codigo, descripcion, costo). */
    private static byte[] csvDeFilas(int filas) {
        StringBuilder csv = new StringBuilder("Codigo;Descripcion;Costo\r\n");
        for (int i = 1; i <= filas; i++) {
            csv.append("COD-").append(i).append(";Tornillo hexagonal numero ").append(i).append(";")
                    .append(i % 997).append(",50\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private HttpResponse<byte[]> muestraComprimida(String token, byte[] archivo) throws Exception {
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(("--" + BORDE + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\"lista.csv\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        cuerpo.write(archivo);
        cuerpo.write(("\r\n--" + BORDE + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + "/api/importaciones/muestra"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token)
                .header("Accept-Encoding", "gzip")
                .header("Content-Type", "multipart/form-data; boundary=" + BORDE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                .build();
        // El cliente de Java no descomprime: el cuerpo llega tal cual lo mando el servidor.
        return cliente.send(pedido, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void laMuestraDeUnaHojaGrandeViajaComprimidaConGzip() throws Exception {
        String token = login("dueno@tornillo.demo");

        HttpResponse<byte[]> respuesta = muestraComprimida(token, csvDeFilas(20_000));

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.headers().firstValue("Content-Encoding")).contains("gzip");
        byte[] plano;
        try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(respuesta.body()))) {
            plano = gzip.readAllBytes();
        }
        JsonNode cuerpo = objectMapper.readTree(plano);
        assertThat(cuerpo.get("hojas").get(0).get("filas")).hasSize(20_001);
        assertThat(respuesta.body().length).isLessThan(plano.length / 2);
    }

    @Test
    void unArchivoDe9MBPasaElLimite() throws Exception {
        String token = login("dueno@tornillo.demo");
        long proveedor = crearProveedor(token);
        byte[] casiGrande = csvDeTamano(unico("NG"), 9 * MB);

        HttpResponse<String> muestra = subir("/api/importaciones/muestra", token, casiGrande, null);
        HttpResponse<String> vistaPrevia = subir("/api/importaciones/vista-previa", token, casiGrande, datos(proveedor, false));
        HttpResponse<String> aplicar = subir("/api/importaciones", token, casiGrande, datos(proveedor, true));

        assertThat(muestra.statusCode()).isEqualTo(200);
        assertThat(vistaPrevia.statusCode()).isEqualTo(200);
        assertThat(aplicar.statusCode()).isEqualTo(201);
    }

    @Test
    void unArchivoDe10Coma5MBQueAntesDaba413AhoraSeAceptaEnLosTresEndpoints() throws Exception {
        String token = login("dueno@tornillo.demo");
        long proveedor = crearProveedor(token);
        byte[] deAntes = csvDeTamano(unico("NG"), 10 * MB + MB / 2);

        HttpResponse<String> muestra = subir("/api/importaciones/muestra", token, deAntes, null);
        HttpResponse<String> vistaPrevia = subir("/api/importaciones/vista-previa", token, deAntes, datos(proveedor, false));
        HttpResponse<String> aplicar = subir("/api/importaciones", token, deAntes, datos(proveedor, true));

        assertThat(muestra.statusCode()).isEqualTo(200);
        assertThat(vistaPrevia.statusCode()).isEqualTo(200);
        assertThat(aplicar.statusCode()).isEqualTo(201);
    }

    @Test
    void unArchivoDeExactamente25MBSeAceptaEnLosTresEndpoints() throws Exception {
        String token = login("dueno@tornillo.demo");
        long proveedor = crearProveedor(token);
        byte[] enElLimite = csvDeTamano(unico("NG"), 25 * MB);

        HttpResponse<String> muestra = subir("/api/importaciones/muestra", token, enElLimite, null);
        HttpResponse<String> vistaPrevia = subir("/api/importaciones/vista-previa", token, enElLimite, datos(proveedor, false));
        HttpResponse<String> aplicar = subir("/api/importaciones", token, enElLimite, datos(proveedor, true));

        assertThat(enElLimite).hasSize(25 * MB);
        assertThat(muestra.statusCode()).isEqualTo(200);
        assertThat(vistaPrevia.statusCode()).isEqualTo(200);
        assertThat(aplicar.statusCode()).isEqualTo(201);
    }

    @Test
    void unPdfDanadoDa400ConElMensajeDelLector() throws Exception {
        String token = login("dueno@tornillo.demo");
        byte[] pdf = ("%PDF-1.7\n" + "contenido de una lista en pdf ".repeat(100)).getBytes(StandardCharsets.US_ASCII);

        HttpResponse<String> respuesta = subir("/api/importaciones/muestra", token, pdf, null);

        assertThat(respuesta.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(respuesta.body()).get("message").asText())
                .isEqualTo("No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @Test
    void unPedidoConVeinteMilFilasElegidasCabeDentroDeLosMBDelPedido() throws Exception {
        // Un archivo de 25 MB mas una parte "datos" de unos 450 KB (20.000 filas elegidas) entra en los 27 MB del
        // pedido. Lo que se prueba es que el contenedor no lo rechace por tamano: lo que responda el servicio con
        // esa seleccion es asunto del endpoint.
        String token = login("dueno@tornillo.demo");
        long proveedor = crearProveedor(token);
        byte[] enElLimite = csvDeTamano(unico("NG"), 25 * MB);
        StringBuilder elegidas = new StringBuilder("[");
        for (int fila = 2; fila < 20_002; fila++) {
            elegidas.append(fila == 2 ? "" : ",").append("{\"hoja\":0,\"fila\":").append(fila).append("}");
        }
        elegidas.append("]");
        String datos = datos(proveedor, true).replaceFirst("\\}$", ",\"filasElegidas\":" + elegidas + "}");

        HttpResponse<String> respuesta = subir("/api/importaciones", token, enElLimite, datos);

        assertThat(datos.length()).isBetween(300_000, 600_000);
        assertThat(respuesta.statusCode()).isNotEqualTo(413);
    }
}
