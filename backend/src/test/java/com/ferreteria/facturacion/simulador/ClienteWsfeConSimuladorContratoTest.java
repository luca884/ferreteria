package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.credencial.CasDeArcaDePrueba;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.credencial.CredencialDePrueba;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.facturacion.motor.WsfePorAmbiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.ContratoWsfeTest;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import com.ferreteria.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * El contrato del puerto contra el cableado real de SIMULADOR (rama 6, S7): {@code ClienteWsfe} con el simulador en el borde,
 * el mismo cliente SOAP que va a ARCA. Cada llamada saca el TA real ({@code TicketsDeAcceso}: el TRA firmado en un CMS con la
 * credencial del simulador de la ferretería, {@code loginCms} y el TA cifrado en la base) y manda el XML real, que el simulador
 * valida contra el XSD antes de pasárselo al núcleo de reglas. Una ferretería nueva por test, con el reloj fijo el
 * 2026-10-28.
 */
@Import({CasDeArcaDePrueba.class, RelojControlableConfig.class})
class ClienteWsfeConSimuladorContratoTest extends ContratoWsfeTest {

    @Autowired
    WsfePorAmbiente wsfePorAmbiente;
    @Autowired
    CredencialArcaService credenciales;
    @Autowired
    RelojControlable reloj;

    private Auth auth;
    private long ferreteriaId;

    @BeforeEach
    void ferreteriaNuevaConCredencialDelSimulador() {
        reloj.fijar(RelojControlableConfig.INICIO);
        long cuit = MotorFiscalTestBase.cuitNuevo();
        String conGuiones = MotorFiscalTestBase.conGuiones(cuit);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", "Contrato " + conGuiones, conGuiones);
        ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
                + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-01 12:00:00')", ferreteriaId);
        CredencialDePrueba.delSimulador(credenciales, jdbc, ferreteriaId);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        auth = new Auth(Ambiente.SIMULADOR, cuit);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    @Override
    protected ServicioWsfe servicio() {
        return wsfePorAmbiente.para(Ambiente.SIMULADOR);
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
        return LocalDate.ofInstant(RelojControlableConfig.INICIO, HoraArgentina.ZONA);
    }

    @Test
    @DisplayName("el puerto de SIMULADOR es ClienteWsfe, y sus llamadas dejan el TA cifrado en ticket_acceso, con la huella "
            + "de la credencial del simulador, y la huella del TA en ticket_simulado")
    void elClienteSacaElTaRealYLoReusa() {
        assertThat(servicio()).isInstanceOf(ClienteWsfe.class);

        servicio().feCompUltimoAutorizado(auth, 1, 6);
        servicio().feCompUltimoAutorizado(auth, 1, 6);

        Map<String, Object> ticket = jdbc.queryForMap("select servicio, huella_credencial, token_cifrado, generado from "
                + "ticket_acceso where ferreteria_id = ? and ambiente = 'SIMULADOR'", ferreteriaId);
        assertThat(ticket).containsEntry("servicio", "wsfe");
        assertThat(ticket.get("token_cifrado")).isNotNull();
        assertThat(ticket.get("huella_credencial")).isEqualTo(jdbc.queryForObject("select huella from credencial_arca "
                + "where ferreteria_id = ? and estado = 'ACTIVA'", String.class, ferreteriaId));
        assertThat(jdbc.queryForObject("select count(*) from ticket_simulado where ferreteria_id = ?", Long.class,
                ferreteriaId)).isEqualTo(1);
    }
}
