package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.api.ArticuloVendible;
import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** El costo que copia la venta (CU-15) es el costo real: el costo con los recargos del articulo (CU-13). */
class ArticulosParaVentaCostoRealTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    ArticulosParaVenta articulosParaVenta;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("con recargos copia el costo real; sin recargos, el costo de siempre")
    void copiaElCostoReal() {
        String cuit = String.format("30-%08d-%d", 79900000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Costo real " + cuit, cuit);
        Long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        Long conRecargo = articulo(f, "CR-1", "235971.56",
                "[{\"nombre\": \"Embalaje y carga\", \"porcentaje\": 4.44}]");
        Long sinRecargo = articulo(f, "CR-2", "100.00", "[]");
        TenantContext.setCurrentFerreteriaId(f);

        List<ArticuloVendible> vendibles = articulosParaVenta.buscar(List.of(conRecargo, sinRecargo));

        assertThat(vendibles.stream().filter(v -> v.id().equals(conRecargo)).findFirst().orElseThrow().costoUnitario())
                .isEqualByComparingTo("246448.7000");
        assertThat(vendibles.stream().filter(v -> v.id().equals(sinRecargo)).findFirst().orElseThrow().costoUnitario())
                .isEqualByComparingTo("100.0000");
    }

    private Long articulo(Long ferreteriaId, String codigo, String costo, String recargos) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "recargos_costo) values (?, ?, 'Articulo', ?, 1.00, ?)", ferreteriaId, codigo, costo, recargos);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }
}
