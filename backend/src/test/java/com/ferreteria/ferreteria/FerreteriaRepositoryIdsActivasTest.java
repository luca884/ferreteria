package com.ferreteria.ferreteria;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** El job diario recorre las ferreterias ACTIVAS: las suspendidas no se recalculan. Contra MySQL, CUIT 30-7700000x. */
class FerreteriaRepositoryIdsActivasTest extends IntegrationTestBase {

    private static final String CUIT_ACTIVA = "30-77000001-1";
    private static final String CUIT_OTRA_ACTIVA = "30-77000002-2";
    private static final String CUIT_SUSPENDIDA = "30-77000003-3";

    @Autowired
    FerreteriaRepository ferreterias;

    private Long activa;
    private Long otraActiva;
    private Long suspendida;

    private Long crear(String cuit, String estado) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, estado) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO', ?) on duplicate key update estado = ?",
                "IdsActivas " + cuit, cuit, estado, estado);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    @BeforeEach
    void sembrar() {
        activa = crear(CUIT_ACTIVA, "ACTIVA");
        otraActiva = crear(CUIT_OTRA_ACTIVA, "ACTIVA");
        suspendida = crear(CUIT_SUSPENDIDA, "SUSPENDIDA");
    }

    @AfterEach
    void limpiar() {
        jdbc.update("delete from ferreteria where cuit in (?, ?, ?)", CUIT_ACTIVA, CUIT_OTRA_ACTIVA, CUIT_SUSPENDIDA);
    }

    @Test
    @DisplayName("devuelve los ids de las ferreterias ACTIVAS y excluye la suspendida")
    void soloLasActivas() {
        List<Long> ids = ferreterias.idsActivas();

        assertThat(ids).contains(activa, otraActiva);
        assertThat(ids).doesNotContain(suspendida);
    }

    @Test
    @DisplayName("una ferreteria que se suspende deja de figurar y una que se reactiva vuelve a figurar")
    void sigueElEstado() {
        jdbc.update("update ferreteria set estado = 'SUSPENDIDA' where id = ?", activa);
        assertThat(ferreterias.idsActivas()).doesNotContain(activa).contains(otraActiva);

        jdbc.update("update ferreteria set estado = 'ACTIVA' where id = ?", suspendida);
        assertThat(ferreterias.idsActivas()).contains(suspendida, otraActiva);
    }
}
