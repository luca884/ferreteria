package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R-109: el super admin no tiene ferretería en el JWT, así que no ve ni toca datos de ventas, stock, caja ni clientes. */
class SuperAdminSinVentasIntegrationTest extends PlataformaTestBase {

    private static final List<String> LISTAS = List.of("/api/rubros", "/api/clientes", "/api/formas-pago",
            "/api/presupuestos", "/api/remitos", "/api/cajas", "/api/articulos");

    private static final List<String> CLAVES_DE_NEGOCIO = List.of("venta", "stock", "caja", "cliente", "importe", "total",
            "saldo", "precio", "articulo", "rubro", "remito", "presupuesto", "ultimoAcceso");

    /**
     * Claves exactas que contienen una palabra de negocio pero no son datos de ventas. {@code puntoVenta} es el punto de venta
     * de ARCA (CU-01 paso 2): configuración fiscal de la ferretería, que el super admin asigna con el simulador. No dice nada
     * de lo que vende.
     */
    private static final Set<String> CLAVES_PERMITIDAS = Set.of("puntoVenta");

    @Test
    @DisplayName("el super admin recibe 403 en las listas de ventas, clientes, caja, stock y catálogo")
    void listasDeFerreteria() throws Exception {
        String admin = tokenSuperAdmin();

        for (String url : LISTAS) {
            getResult(url, admin).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("la respuesta de /api/admin/ferreterias no trae ninguna clave de negocio")
    void estadoGeneralSinDatosDeVentas() throws Exception {
        String cuerpo = getResult(BASE, tokenSuperAdmin()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        List<String> claves = objectMapper.readTree(cuerpo).findParents("razonSocial").stream()
                .flatMap(n -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(n.fieldNames(), 0), false))
                .distinct().toList();

        assertThat(claves).isNotEmpty();
        for (String clave : claves) {
            if (CLAVES_PERMITIDAS.contains(clave)) {
                continue;
            }
            assertThat(CLAVES_DE_NEGOCIO).as("clave «%s»", clave)
                    .noneMatch(prohibida -> clave.toLowerCase().contains(prohibida.toLowerCase()));
        }
    }
}
