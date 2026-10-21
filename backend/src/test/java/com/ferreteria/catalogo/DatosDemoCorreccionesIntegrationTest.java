package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.Cuit;
import com.ferreteria.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** V1004 (solo dev y test): los rubros demo llevan tilde y los CUIT de los proveedores demo son validos. */
class DatosDemoCorreccionesIntegrationTest extends IntegrationTestBase {

    @Test
    void losRubrosDemoLlevanTilde() {
        List<String> nombres = jdbc.queryForList(
                "select nombre from rubro where ferreteria_id in (1, 2) order by ferreteria_id, nombre", String.class);

        assertThat(nombres).contains("Bulonería", "Herramientas", "Pinturería")
                .doesNotContain("Buloneria", "Pintureria");
    }

    @Test
    void losCuitDeLosProveedoresDemoTienenElVerificadorCorrecto() {
        List<String> cuits = jdbc.queryForList(
                "select cuit from proveedor where id in (1, 2, 3) and cuit is not null", String.class);

        assertThat(cuits).hasSize(2);
        cuits.forEach(cuit -> assertThat(Cuit.normalizar(cuit)).isEqualTo(cuit));
    }
}
