package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.support.GanchoDeChoque;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * El stock y las ventas no compiten con la version del articulo (lote 5b, T-246, R-229): el stock se mueve con un UPDATE
 * nativo que no nombra {@code version}, asi que una venta o un ajuste de stock no la suben ni dan 409, y una edicion o un
 * masivo que se guardan despues no pisan el stock. Cada test crea su propia ferreteria (ver {@link VentasTestBase}).
 */
class VentaVersionIntegrationTest extends VentasTestBase {

    @SpyBean
    HistorialPrecioRepository historialPrecioRepository;

    @Autowired
    EntityManager entityManager;

    private GanchoDeChoque gancho;

    @BeforeEach
    void armarElGancho() {
        gancho = new GanchoDeChoque(historialPrecioRepository, entityManager);
    }

    @AfterEach
    void soltarElGancho() {
        gancho.soltar();
    }

    private long version(long articuloId) {
        return jdbc.queryForObject("select version from articulo where id = ?", Long.class, articuloId);
    }

    private BigDecimal precio(long articuloId) {
        return jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, articuloId);
    }

    /** Un cuerpo de PUT con lo que el formulario manda, tomado del articulo tal como esta, con la version que se leyo. */
    private ObjectNode cuerpoDe(String token, long id) throws Exception {
        JsonNode articulo = getJson("/api/articulos/" + id, token);
        ObjectNode cuerpo = objectMapper.createObjectNode();
        for (String campo : List.of("codigoInterno", "codigoBarras", "codigoProveedor", "descripcion", "rubroId",
                "proveedorId", "costo", "margenPorcentaje", "alicuotaIva", "unidad", "stockMinimo", "ubicacion",
                "activo", "moneda", "version")) {
            cuerpo.set(campo, articulo.get(campo));
        }
        return cuerpo;
    }

    /** Una edicion previa fija el precio final real (lo calcula el backend), para que las ventas no den 409. */
    private BigDecimal conPrecioReal(String dueno, long id) throws Exception {
        putJson("/api/articulos/" + id, dueno, cuerpoDe(dueno, id).put("descripcion", "Base").toString())
                .andExpect(status().isOk());
        return precio(id);
    }

    @Test
    @DisplayName("una venta de 3 sobre stock 10 deja el stock en 7 y la version igual; un PUT con la version de antes da 200, stock 7 y version +1")
    void unaVentaNoSubeLaVersionYElPutNoPisaElStock() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        BigDecimal precio = conPrecioReal(dueno, a);
        long antes = version(a);
        ObjectNode formulario = cuerpoDe(dueno, a).put("descripcion", "Editado").put("version", antes);

        vender(login(t.emailVendedor()), clave(), precio.multiply(new BigDecimal(3)).toPlainString(),
                linea(a, "3", precio.toPlainString())).andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("7");
        assertThat(version(a)).isEqualTo(antes);
        putJson("/api/articulos/" + a, dueno, formulario.toString()).andExpect(status().isOk())
                .andExpect(r -> assertThat(objectMapper.readTree(
                        r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                        .get("stockActual").decimalValue()).isEqualByComparingTo("7"));
        assertThat(stock(a)).isEqualByComparingTo("7");
        assertThat(version(a)).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("un ajuste manual de stock a 20 no cambia la version")
    void unAjusteDeStockNoSubeLaVersion() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long antes = version(a);

        postJson("/api/articulos/" + a + "/ajustes-stock", login(t.emailDueno()),
                "{\"stockReal\":20,\"motivo\":\"CONTEO\"}").andExpect(status().isCreated());

        assertThat(stock(a)).isEqualByComparingTo("20");
        assertThat(version(a)).isEqualTo(antes);
    }

    @Test
    @DisplayName("una venta de 3 mientras se aplica un masivo: el masivo da 201, la venta se cobra y el articulo queda con el costo del masivo y stock 7")
    void unaVentaDentroDeUnMasivoNoLoFrena() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "160.00", "10");
        jdbc.update("update articulo set costo = 100.00 where id = ?", a);
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        ObjectNode pedido = objectMapper.createObjectNode().put("modo", "COSTO_PORCENTAJE").put("valor", 10);
        JsonNode previa = json(postJson("/api/actualizacion-masiva/vista-previa", dueno, pedido.toString())
                .andExpect(status().isOk()));
        long antes = version(a);
        gancho.enSaveAll(() -> vender(vendedor, clave(), "480.00", linea(a, "3", "160.00"))
                .andExpect(status().isCreated()));

        postJson("/api/actualizacion-masiva", dueno,
                pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated());

        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, a))
                .isEqualByComparingTo("110.00");
        assertThat(stock(a)).isEqualByComparingTo("7");
        assertThat(version(a)).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("un ticket abierto a $ 1.420 con el precio editado despues se cobra como hoy (PRECIOS_CAMBIADOS), sin 409 por version")
    void elTicketAbiertoSigueElCaminoDeSiempre() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Martillo", "UNIDAD", "1420.00", "10");
        jdbc.update("update articulo set costo = 900.00 where id = ?", a);
        String dueno = login(t.emailDueno());
        putJson("/api/articulos/" + a, dueno, cuerpoDe(dueno, a).put("costo", new BigDecimal("950.00")).toString())
                .andExpect(status().isOk());
        assertThat(precio(a)).isNotEqualByComparingTo("1420.00");

        JsonNode rechazo = json(vender(login(t.emailVendedor()), clave(), "1420.00", linea(a, "1", "1420.00"))
                .andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }
}
