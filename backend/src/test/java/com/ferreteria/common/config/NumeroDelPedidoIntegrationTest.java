package com.ferreteria.common.config;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hallazgo alto 2 de la revision del 05/11: un numero con un exponente gigante en el pedido gastaba la CPU o la memoria
 * del servidor de todas las ferreterias (formatear el dolar del mensaje, sumarle el importe al saldo). Ahora se rechaza
 * con 400 al leer el pedido, antes de la logica. Los exponentes de las pruebas son chicos (1000) para que, sin el
 * arreglo, el test falle por el codigo y no tumbe la JVM.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NumeroDelPedidoIntegrationTest extends IntegrationTestBase {

    private String dueno;
    private String vendedor;

    @BeforeEach
    void ingresar() throws Exception {
        dueno = login("dueno@tornillo.demo");
        vendedor = login("vendedor@tornillo.demo");
    }

    @Test
    @DisplayName("POST /api/cotizacion/recalculo?dolar=1E+1000 da 400 sin llegar a comparar ni formatear el dolar")
    void dolarDeLaVistaPrevia() throws Exception {
        postJson("/api/cotizacion/recalculo?dolar=1E+1000", dueno, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", is("Parámetro inválido: dolar")));
    }

    @Test
    @DisplayName("el Vendedor que pide la cuenta de un cliente con importe=1E+1000 recibe 400, antes de buscar el cliente")
    void importeDeLaCuentaCorriente() throws Exception {
        getResult("/api/clientes/999999999/cuenta-corriente?importe=1E+1000", vendedor)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", is("Parámetro inválido: importe")));
    }

    @Test
    @DisplayName("la vista previa de margen y redondeo con un redondeo de 1E-1000 da 400")
    void redondeoDeLaVistaPrevia() throws Exception {
        getResult("/api/configuracion-precios/articulos-que-cambian?margenDefectoPorcentaje=30&redondeoMultiplo=1E-1000",
                dueno)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", is("Parámetro inválido: redondeoMultiplo")));
    }

    @Test
    @DisplayName("un recargo de 1E+1000 % en el cuerpo de la compra da 400 al leer el pedido (hallazgo medio 4)")
    void recargoEnElCuerpo() throws Exception {
        postJson("/api/compras/vista-previa", dueno, "{\"recargos\":[{\"nombre\":\"Flete\",\"porcentaje\":1E+1000}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
    }

    @Test
    @DisplayName("un numero en texto del cuerpo con exponente gigante tambien da 400")
    void recargoEnTextoEnElCuerpo() throws Exception {
        postJson("/api/compras/vista-previa", dueno,
                "{\"recargos\":[{\"nombre\":\"Flete\",\"porcentaje\":\"1E-1000\"}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
    }
}
