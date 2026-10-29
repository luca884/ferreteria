package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.credencial.CasDeArcaDePrueba;
import com.ferreteria.facturacion.credencial.CredencialDePrueba;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import com.ferreteria.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Base de los tests del ticket de acceso contra MySQL real (rama 6, S2, S3 y C4): el reloj controlable y las CA de ARCA de
 * prueba (el mismo contexto que los tests de la credencial), una ferretería nueva por test en HOMOLOGACION con la facturación
 * habilitada en el punto de venta 3 y una credencial activa puesta por SQL ({@link CredencialDePrueba}), esa ferretería en
 * {@link TenantContext}, y un WSAA falso ({@link WsaaDePrueba}) que cuenta los {@code loginCms}. Cada {@link #instancia()}
 * es un {@link TicketsDeAcceso} nuevo sobre la misma base, como otra JVM del deploy o la misma después de un reinicio.
 */
@Import({CasDeArcaDePrueba.class, RelojControlableConfig.class})
abstract class TicketsDeAccesoTestBase extends IntegrationTestBase {

    static final Instant AHORA = RelojControlableConfig.INICIO;
    static final String WSFE = ClienteWsfe.SERVICIO;

    @Autowired
    RelojControlable reloj;
    @Autowired
    PasosDelTicket pasos;
    @Autowired
    FirmaCms firma;
    @Autowired
    PropiedadesArca propiedades;
    @Autowired
    CifradorDeClaves cifrador;
    @Autowired
    TicketAccesoRepository tickets;

    WsaaDePrueba wsaa;
    long ferreteriaId;
    long cuit;
    CredencialDePrueba credencial;

    @BeforeEach
    void ferreteriaEnHomologacion() {
        reloj.fijar(AHORA);
        wsaa = new WsaaDePrueba(reloj, CasDeArcaDePrueba.HOMOLOGACION);
        cuit = MotorFiscalTestBase.cuitNuevo();
        ferreteriaId = ferreteriaConCredencial(cuit);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    /** Una ferretería RI con los datos de CU-01, en HOMOLOGACION con la facturación habilitada y una credencial activa. */
    long ferreteriaConCredencial(long cuitEmisor) {
        long f = ferreteriaSinCredencial(cuitEmisor);
        credencial = CredencialDePrueba.activa(jdbc, cifrador, f, Ambiente.HOMOLOGACION, cuitEmisor, AHORA);
        return f;
    }

    /** Lo mismo, sin credencial. */
    long ferreteriaSinCredencial(long cuitEmisor) {
        String conGuiones = MotorFiscalTestBase.conGuiones(cuitEmisor);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, domicilio_comercial, inicio_actividades, "
                + "ingresos_brutos) values (?, ?, 'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '2015-03-01', "
                + "'901-123456-7')", "Ticket " + conGuiones, conGuiones);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueño', "
                + "'DUENO')", f, "ticket-" + f + "@test.local");
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
                + "habilitada_en) values (?, false, 'HOMOLOGACION', 3, true, '2026-10-01 12:00:00')", f);
        return f;
    }

    /** Un {@link TicketsDeAcceso} nuevo sobre la misma base, con el WSAA falso de HOMOLOGACION y la pausa de producción. */
    TicketsDeAcceso instancia() {
        return instancia(TicketsDeAcceso.Pausa.CORTA);
    }

    TicketsDeAcceso instancia(TicketsDeAcceso.Pausa pausa) {
        return new TicketsDeAcceso(pasos, firma, List.of(new ClienteWsaa(Ambiente.HOMOLOGACION, wsaa, propiedades.wsaa())),
                propiedades, reloj, pausa);
    }

    /** Una hora como la guarda Hibernate ({@code jdbc.time_zone: UTC}), para escribirla por SQL. */
    static String utc(Instant instante) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").format(LocalDateTime.ofInstant(instante,
                ZoneOffset.UTC));
    }

    /** Una hora leída por SQL ({@code DATETIME(6)} en UTC) como instante; null si es null. */
    static Instant instante(Object columna) {
        return columna == null ? null : ((LocalDateTime) columna).toInstant(ZoneOffset.UTC);
    }

    /** La fila del ticket de wsfe en HOMOLOGACION de la ferretería, como la ve la base. */
    Map<String, Object> fila(long ferreteria) {
        return jdbc.queryForMap("select * from ticket_acceso where ferreteria_id = ? and servicio = 'wsfe' and ambiente = "
                + "'HOMOLOGACION'", ferreteria);
    }
}
