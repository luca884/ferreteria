package com.ferreteria.facturacion;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.Cuit;
import com.ferreteria.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V1006 (solo dev y test, S9): las ferreterías demo tienen un CUIT con el dígito verificador correcto, porque la factura
 * lo lleva como emisor y el simulador lo valida.
 */
class DatosDemoCuitIntegrationTest extends IntegrationTestBase {

    @Test
    void losCuitDeLasFerreteriasDemoTienenElVerificadorCorrecto() {
        List<String> cuits = jdbc.queryForList("select cuit from ferreteria where id in (1, 2) order by id", String.class);

        assertThat(cuits).containsExactly("30-00000001-5", "30-00000002-3");
        cuits.forEach(cuit -> assertThat(Cuit.normalizar(cuit)).isEqualTo(cuit));
    }
}
