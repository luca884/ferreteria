package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.credencial.CasDeArcaDePrueba;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.credencial.CredencialDePrueba;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import com.ferreteria.tenant.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Base de los tests del simulador en el borde contra MySQL real (rama 6, S6 y S7): el reloj controlable y las CA de ARCA de
 * prueba (el mismo contexto que los tests del ticket de acceso y de la credencial), y una ferretería nueva por test, RI, en
 * SIMULADOR con el punto de venta 1 y la facturación habilitada, con la credencial que firma la CA del simulador de esa
 * ferretería ({@link CredencialDePrueba#delSimulador}), puesta en {@link TenantContext}.
 */
@Import({CasDeArcaDePrueba.class, RelojControlableConfig.class})
abstract class SimuladorEnElBordeTestBase extends IntegrationTestBase {

    static final Instant AHORA = RelojControlableConfig.INICIO;

    @Autowired
    RelojControlable reloj;
    @Autowired
    CredencialArcaService credenciales;

    long ferreteriaId;
    long cuit;
    CredencialDePrueba credencial;

    @BeforeEach
    void ferreteriaEnElSimulador() {
        reloj.fijar(AHORA);
        cuit = MotorFiscalTestBase.cuitNuevo();
        ferreteriaId = ferreteriaSinCredencial(cuit);
        credencial = CredencialDePrueba.delSimulador(credenciales, jdbc, ferreteriaId);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    /** Una ferretería RI con los datos de CU-01, en SIMULADOR con el punto de venta 1 y la facturación habilitada. */
    long ferreteriaSinCredencial(long cuitEmisor) {
        String conGuiones = MotorFiscalTestBase.conGuiones(cuitEmisor);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", "Borde " + conGuiones, conGuiones);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
                + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-01 12:00:00')", f);
        return f;
    }
}
