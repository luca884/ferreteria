package com.ferreteria.compras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.ListasDePrueba;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La lectura de la factura del proveedor en PDF (CU-13): un borrador que no guarda nada. Facturas inventadas, generadas
 * en el test. Solo el Dueno; el proveedor sale del CUIT y los articulos del codigo del proveedor, siempre dentro de la
 * ferreteria del request.
 */
class FacturaPdfIntegrationTest extends ComprasTestBase {

    String cuitProveedor;
    Tienda tienda;
    String dueno;
    long proveedor;
    long tornillo;

    @BeforeEach
    void datos() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        cuitProveedor = cuitValido();
        proveedor = proveedor(tienda.id(), "Bulonera Sur", cuitProveedor);
        tornillo = articulo(tienda.id(), "TOR-1", "Tornillo 1/4", "2.50", "4.00", proveedor, "TOR-9");
        articulo(tienda.id(), "TUE-1", "Tuerca 1/4", "12.50", "20.00", proveedor, "TUE-9");
    }

    @Test
    @DisplayName("devuelve el borrador: proveedor por CUIT, encabezado, recargos, renglones con su articulo y el control")
    void leeLaFactura() throws Exception {
        postMultipart("/api/compras/factura-pdf", dueno, archivo("factura.pdf", factura(cuitProveedor, "TOR-9", "TUE-9")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").value(proveedor))
                .andExpect(jsonPath("$.proveedorNombre").value("Bulonera Sur"))
                .andExpect(jsonPath("$.tipo").value("FA"))
                .andExpect(jsonPath("$.puntoVenta").value(4))
                .andExpect(jsonPath("$.numero").value(1234))
                .andExpect(jsonPath("$.fecha").value("2026-11-03"))
                .andExpect(jsonPath("$.recargos[0].nombre").value("EMBALAJE Y CARGA"))
                .andExpect(jsonPath("$.recargos[0].porcentaje").value(4.0))
                .andExpect(jsonPath("$.renglones.length()").value(2))
                .andExpect(jsonPath("$.renglones[0].codigo").value("TOR-9"))
                .andExpect(jsonPath("$.renglones[0].cantidad").value(10.0))
                .andExpect(jsonPath("$.renglones[0].costoUnitario").value(2.5))
                .andExpect(jsonPath("$.renglones[0].neto").value(25.0))
                .andExpect(jsonPath("$.renglones[0].articuloId").value(tornillo))
                .andExpect(jsonPath("$.renglones[0].articuloDescripcion").value("Tornillo 1/4"))
                .andExpect(jsonPath("$.renglones[0].cantidadNoEntera").value(false))
                .andExpect(jsonPath("$.sumaNetos").value(75.0))
                .andExpect(jsonPath("$.control").value("CUADRA"))
                .andExpect(jsonPath("$.cantidadNoEntera").value(false));
        assertThat(jdbc.queryForObject("select count(*) from compra where ferreteria_id = ?", Integer.class,
                tienda.id())).isZero();
    }

    @Test
    @DisplayName("el CUIT de la propia ferreteria no cuenta, ni tampoco el de un proveedor dado de baja")
    void elCuitPropioNoCuenta() throws Exception {
        proveedor(tienda.id(), "Mi propio CUIT", tienda.cuit());
        String baja = cuitValido();
        long dadoDeBaja = proveedor(tienda.id(), "Proveedor de baja", baja);
        jdbc.update("update proveedor set activo = false where id = ?", dadoDeBaja);

        postMultipart("/api/compras/factura-pdf", dueno,
                archivo("factura.pdf", factura(List.of(tienda.cuit(), baja, cuitProveedor), "TOR-9")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").value(proveedor))
                .andExpect(jsonPath("$.cuits.length()").value(3));
    }

    @Test
    @DisplayName("sin proveedor con ese CUIT no hay proveedor ni articulos, pero se leen los renglones")
    void sinProveedor() throws Exception {
        postMultipart("/api/compras/factura-pdf", dueno, archivo("factura.pdf", factura(cuitValido(), "TOR-9")), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").doesNotExist())
                .andExpect(jsonPath("$.cuits.length()").value(1))
                .andExpect(jsonPath("$.renglones[0].codigo").value("TOR-9"))
                .andExpect(jsonPath("$.renglones[0].articuloId").doesNotExist());
    }

    @Test
    @DisplayName("un renglon sin articulo con ese codigo queda sin articulo; una cantidad decimal de un articulo por unidad avisa")
    void renglonSinArticuloYCantidadDecimal() throws Exception {
        byte[] pdf = ListasDePrueba.pdf(List.of(
                List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
                List.of("FECHA:", "03/11/2026", "C.U.I.T.: " + cuitProveedor),
                List.of("TOR-9", "2.5", "TORNILLO", "2.00", "5.00"),
                List.of("OTRO-1", "1.0", "OTRA COSA", "3.00", "3.00"),
                List.of("TOTAL", "8.00")));

        postMultipart("/api/compras/factura-pdf", dueno, archivo("factura.pdf", pdf), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renglones[0].articuloId").value(tornillo))
                .andExpect(jsonPath("$.renglones[0].cantidadNoEntera").value(true))
                .andExpect(jsonPath("$.renglones[1].articuloId").doesNotExist())
                .andExpect(jsonPath("$.renglones[1].cantidadNoEntera").value(false))
                .andExpect(jsonPath("$.cantidadNoEntera").value(true));
    }

    @Test
    @DisplayName("un PDF sin texto y una factura sin renglones avisan con el texto de la factura: 400")
    void erroresDeLectura() throws Exception {
        postMultipart("/api/compras/factura-pdf", dueno, archivo("escaneada.pdf", ListasDePrueba.pdfSinTexto()), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("El PDF no tiene texto para leer (parece escaneado). Cargá la compra a mano."));
        postMultipart("/api/compras/factura-pdf", dueno,
                archivo("sin-renglones.pdf", ListasDePrueba.pdf(List.of(List.of("Hola", "mundo")))), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("No se encontraron renglones en la factura. Cargá la compra a mano."));
    }

    @Test
    @DisplayName("el Vendedor no puede leer facturas: 403")
    void vendedorRecibe403() throws Exception {
        String vendedor = login(tienda.emailVendedor());

        postMultipart("/api/compras/factura-pdf", vendedor,
                archivo("factura.pdf", factura(cuitProveedor, "TOR-9")), null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("otra ferreteria con el mismo CUIT de proveedor y los mismos codigos no se mezcla con esta")
    void aislamiento() throws Exception {
        Tienda otra = tienda();
        String duenoOtra = login(otra.emailDueno());
        long proveedorOtra = proveedor(otra.id(), "Bulonera Sur", cuitProveedor);
        long articuloOtra = articulo(otra.id(), "TOR-1", "Tornillo de la otra", "2.50", "4.00", proveedorOtra, "TOR-9");
        // Una tercera ferreteria sin ese proveedor: la factura no encuentra ni proveedor ni articulos.
        Tienda tercera = tienda();
        String duenoTercera = login(tercera.emailDueno());
        articulo(tercera.id(), "TOR-1", "Tornillo de la tercera", "2.50", "4.00", null, "TOR-9");
        byte[] pdf = factura(cuitProveedor, "TOR-9", "TUE-9");

        postMultipart("/api/compras/factura-pdf", dueno, archivo("factura.pdf", pdf), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").value(proveedor))
                .andExpect(jsonPath("$.renglones[0].articuloId").value(tornillo));
        postMultipart("/api/compras/factura-pdf", duenoOtra, archivo("factura.pdf", pdf), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").value(proveedorOtra))
                .andExpect(jsonPath("$.renglones[0].articuloId").value(articuloOtra))
                .andExpect(jsonPath("$.renglones[1].articuloId").doesNotExist());
        postMultipart("/api/compras/factura-pdf", duenoTercera, archivo("factura.pdf", pdf), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedorId").doesNotExist())
                .andExpect(jsonPath("$.renglones[0].articuloId").doesNotExist());
    }

    // ---- helpers ----

    private static byte[] factura(String cuitProveedor, String codigo1, String codigo2) {
        return factura(List.of(cuitProveedor), codigo1, codigo2);
    }

    private static byte[] factura(String cuitProveedor, String codigo1) {
        return ListasDePrueba.pdf(List.of(
                List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
                List.of("FECHA:", "03/11/2026", "C.U.I.T.: " + cuitProveedor),
                List.of(codigo1, "10.0", "TORNILLO", "2.50", "25.00"),
                List.of("TOTAL", "25.00")));
    }

    /** Dos renglones (25,00 y 50,00) y un recargo de 4 % (3,00): el total 78,00 cuadra. */
    private static byte[] factura(List<String> cuits, String codigo1, String codigo2) {
        return ListasDePrueba.pdf(List.of(
                List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
                List.of("FECHA:", "03/11/2026"),
                encabezadoDeCuits(cuits),
                List.of(codigo1, "10.0", "TORNILLO", "2.50", "25.00"),
                List.of(codigo2, "4.0", "TUERCA", "12.50", "50.00"),
                List.of("EMBALAJE Y CARGA :", "4.00%", "3.00"),
                List.of("TOTAL", "78.00")));
    }

    private static byte[] factura(List<String> cuits, String codigo1) {
        return ListasDePrueba.pdf(List.of(
                List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
                List.of("FECHA:", "03/11/2026"),
                encabezadoDeCuits(cuits),
                List.of(codigo1, "10.0", "TORNILLO", "2.50", "25.00"),
                List.of("TOTAL", "25.00")));
    }

    private static List<String> encabezadoDeCuits(List<String> cuits) {
        return cuits.stream().flatMap(c -> java.util.stream.Stream.of("C.U.I.T.:", c)).toList();
    }
}
