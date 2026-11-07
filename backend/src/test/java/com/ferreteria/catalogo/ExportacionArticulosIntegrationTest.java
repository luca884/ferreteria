package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.common.HoraArgentina;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * GET /api/articulos/exportacion (GDS-19, como la «Exportación Rápida» de GDS G25): el Dueño baja a CSV los articulos
 * que muestra la pantalla Artículos, todos juntos, para Excel en castellano. Nada de otra ferreteria (regla 7).
 * CUIT 30-79092001-4 y 30-79092002-5.
 */
class ExportacionArticulosIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos/exportacion";
    private static final String ENCABEZADO = "Código;Código de barras;Código del proveedor;Descripción;Rubro;Proveedor;"
            + "Unidad;Moneda;Costo;IVA %;Margen %;Precio final;Precio manual;Stock;Stock mínimo;Ubicación;Activo";

    private Long a;
    private Long b;
    private String duenoA;
    private Long bulones;
    private Long sur;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79092001-4", "Exporta A");
        b = ferreteria("30-79092002-5", "Exporta B");
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-exporta-a@test.local", "DUENO"));
        usuario(a, "vendedor-exporta-a@test.local", "VENDEDOR");
        usuario(b, "dueno-exporta-b@test.local", "DUENO");

        bulones = rubro(a, "Bulones", "30");
        sur = proveedor(a, "Bulonera Sur");
        Long tuerca = cargar(a, "B-002", "Tuerca 1/4", "UNIDAD", "ARS", "100.00", "160.00", "12.500", "5.000");
        jdbc.update("update articulo set codigo_barras = '7790001', codigo_proveedor = 'BS-14', rubro_id = ?,"
                + " proveedor_id = ?, margen_porcentaje = 30.00, alicuota_iva = 'IVA_10_5', ubicacion = 'Estante 3'"
                + " where id = ?", bulones, sur, tuerca);
        Long cable = cargar(a, "A-001", "Cable \"taller\"; 2,5 mm", "METRO", "USD", "1234.50", "2000.00", "-3.000",
                "0.000");
        jdbc.update("update articulo set precio_manual = true where id = ?", cable);
        Long baja = cargar(a, "C-003", "=Dado de baja", "KILO", "ARS", "10.00", "20.00", "0.000", "0.000");
        jdbc.update("update articulo set activo = false where id = ?", baja);
        cargar(b, "Z-009", "Ajeno", "UNIDAD", "ARS", "1.00", "2.00", "0.000", "0.000");
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    private Long cargar(Long ferreteriaId, String codigo, String descripcion, String unidad, String moneda, String costo,
                        String precio, String stock, String minimo) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final,"
                        + " unidad, alicuota_iva, stock_actual, stock_minimo, activo) values (?, ?, ?, ?, ?, ?, ?, 'IVA_21',"
                        + " ?, ?, true)",
                ferreteriaId, codigo, descripcion, bd(costo), moneda, bd(precio), unidad, bd(stock), bd(minimo));
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }

    private MockHttpServletResponse exportar(String query, String token) throws Exception {
        return getResult(URL + query, token).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static String csv(MockHttpServletResponse respuesta) {
        byte[] bytes = respuesta.getContentAsByteArray();
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("baja los activos propios por descripcion, con todas las columnas, como archivo CSV con la fecha")
    void exportaLosActivos() throws Exception {
        MockHttpServletResponse respuesta = exportar("", duenoA);

        assertThat(respuesta.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(respuesta.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"articulos-" + HoraArgentina.hoy() + ".csv\"");
        assertThat(csv(respuesta)).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"A-001\";;;\"Cable \"\"taller\"\"; 2,5 mm\";;;Metro;USD;1234,50;21;Heredado;2000,00;Sí;-3;0;;Sí\r\n"
                + "=\"B-002\";=\"7790001\";=\"BS-14\";Tuerca 1/4;Bulones;Bulonera Sur;Unidad;ARS;100,00;10,5;30;160,00;No;12,5;5;"
                + "Estante 3;Sí\r\n");
    }

    @Test
    @DisplayName("con busqueda sale en el orden de la pantalla: primero los que empiezan con lo buscado (arreglos-compras, S5)")
    void conBusquedaElOrdenDeLaPantalla() throws Exception {
        cargar(a, "K-1", "Barreta sacaclavos", "UNIDAD", "ARS", "1.00", "2.00", "0.000", "0.000");
        cargar(a, "K-2", "Clavo 2 pulgadas", "UNIDAD", "ARS", "1.00", "2.00", "0.000", "0.000");
        cargar(a, "K-3", "Clavo 1 pulgada", "UNIDAD", "ARS", "1.00", "2.00", "0.000", "0.000");

        String[] lineas = csv(exportar("?q=clavo", duenoA)).split("\r\n");

        assertThat(java.util.Arrays.stream(lineas).skip(1).map(l -> l.split(";")[3]).toList())
                .containsExactly("Clavo 1 pulgada", "Clavo 2 pulgadas", "Barreta sacaclavos");
    }

    @Test
    @DisplayName("respeta la busqueda y los inactivos de la pantalla; el texto que parece formula lleva apostrofo")
    void respetaElFiltro() throws Exception {
        assertThat(csv(exportar("?q=tuerca", duenoA))).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"B-002\";=\"7790001\";=\"BS-14\";Tuerca 1/4;Bulones;Bulonera Sur;Unidad;ARS;100,00;10,5;30;160,00;No;12,5;5;"
                + "Estante 3;Sí\r\n");

        String conInactivos = csv(exportar("?incluirInactivos=true&q=dado", duenoA));
        assertThat(conInactivos).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"C-003\";;;'=Dado de baja;;;Kilo;ARS;10,00;21;Heredado;20,00;No;0;0;;No\r\n");
    }

    @Test
    @DisplayName("otra ferreteria solo baja lo suyo; el Vendedor y el admin reciben 403")
    void aislamientoYPermisos() throws Exception {
        assertThat(csv(exportar("", login("dueno-exporta-b@test.local")))).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"Z-009\";;;Ajeno;;;Unidad;ARS;1,00;21;Heredado;2,00;No;0;0;;Sí\r\n");
        getResult(URL, login("vendedor-exporta-a@test.local")).andExpect(status().isForbidden());
        getResult(URL, login("admin@demo.local")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("el front, que corre en otro origen, puede leer el nombre del archivo (CORS expone Content-Disposition)")
    void exponeElNombreDelArchivo() throws Exception {
        MockHttpServletResponse respuesta = mvc.perform(get(URL).header("Authorization", "Bearer " + duenoA)
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(respuesta.getHeader("Access-Control-Expose-Headers")).contains("Content-Disposition");
    }

    @Test
    @DisplayName("el apostrofo va en toda columna de texto (ubicacion, rubro, proveedor); los codigos van como texto de Excel (sondas del verificador)")
    void apostrofoEnTodasLasColumnasDeTexto() throws Exception {
        Long rubroFormula = rubro(a, "=HYPERLINK(\"http://x\";\"y\")", "30");
        Long proveedorFormula = proveedor(a, "\t=1+1");
        Long id = cargar(a, "+A1", "Sonda formula", "UNIDAD", "ARS", "1.00", "2.00", "0.000", "0.000");
        jdbc.update("update articulo set codigo_barras = '-2+3', codigo_proveedor = '@SUM(1)',"
                + " ubicacion = '=cmd|'' /C calc''!A0', rubro_id = ?, proveedor_id = ? where id = ?",
                rubroFormula, proveedorFormula, id);

        assertThat(csv(exportar("?q=sonda", duenoA))).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"+A1\";=\"-2+3\";=\"@SUM(1)\";Sonda formula;\"'=HYPERLINK(\"\"http://x\"\";\"\"y\"\")\";'\t=1+1;Unidad;ARS;"
                + "1,00;21;Heredado;2,00;No;0;0;'=cmd|' /C calc'!A0;Sí\r\n");
    }

    @Test
    @DisplayName("stock y margen sin ceros de mas ni notacion cientifica, con su signo (sondas del verificador)")
    void numerosEnLosBordes() throws Exception {
        Long mil = cargar(a, "N-001", "Sonda mil", "UNIDAD", "ARS", "1.00", "2.00", "1000.000", "0.001");
        jdbc.update("update articulo set margen_porcentaje = 12.50 where id = ?", mil);
        Long negativo = cargar(a, "N-002", "Sonda negativo", "UNIDAD", "ARS", "1.00", "2.00", "-0.500", "12345678.123");
        jdbc.update("update articulo set margen_porcentaje = -5.00 where id = ?", negativo);

        assertThat(csv(exportar("?q=sonda", duenoA))).isEqualTo(ENCABEZADO + "\r\n"
                + "=\"N-001\";;;Sonda mil;;;Unidad;ARS;1,00;21;12,5;2,00;No;1000;0,001;;Sí\r\n"
                + "=\"N-002\";;;Sonda negativo;;;Unidad;ARS;1,00;21;-5;2,00;No;-0,5;12345678,123;;Sí\r\n");
    }

    @Test
    @DisplayName("sin token da 401, y GET /api/articulos/{id} sigue andando al lado de /exportacion")
    void sinTokenYRutaVecina() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        Long id = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = 'B-002'",
                Long.class, a);
        assertThat(getJson("/api/articulos/" + id, duenoA).get("codigoInterno").asText()).isEqualTo("B-002");
    }

    @Test
    @DisplayName("sin articulos que cumplan el filtro baja solo el encabezado")
    void vacio() throws Exception {
        assertThat(csv(exportar("?q=nada-que-ver", duenoA))).isEqualTo(ENCABEZADO + "\r\n");
    }
}
