package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.ContratoWsfeTest;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;

/** El contrato del puerto, contra el simulador: una ferretería nueva por test, reloj fijo el 2026-10-28. */
class SimuladorWsfeContratoTest extends ContratoWsfeTest {

    @Autowired
    SerieSimuladaRepository series;
    @Autowired
    ComprobanteSimuladoRepository comprobantes;
    @Autowired
    PlatformTransactionManager transacciones;
    @Value("${app.facturacion.simulador.tope-rg4444}")
    BigDecimal topeRg4444;
    @Value("${app.facturacion.simulador.minimo-fce}")
    BigDecimal minimoFce;

    private SimuladorWsfe simulador;
    private Auth auth;

    @BeforeEach
    void ferreteriaNueva() {
        long cuit = SimuladorWsfeTestBase.cuitNuevo();
        String conGuiones = String.valueOf(cuit).replaceFirst("(\\d{2})(\\d{8})(\\d)", "$1-$2-$3");
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Contrato " + conGuiones, conGuiones);
        TenantContext.setCurrentFerreteriaId(
                jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones));
        auth = SimuladorWsfeTestBase.auth(cuit);
        simulador = new SimuladorWsfe(series, comprobantes, transacciones, SimuladorWsfeTestBase.RELOJ, Duration.ZERO,
                topeRg4444, minimoFce);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    @Override
    protected ServicioWsfe servicio() {
        return simulador;
    }

    @Override
    protected Auth auth() {
        return auth;
    }

    @Override
    protected int puntoDeVenta() {
        return 1;
    }

    @Override
    protected LocalDate fechaDeEnvio() {
        return SimuladorWsfeTestBase.HOY;
    }
}
