package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Base de los tests del simulador: una ferretería nueva por test (con su CUIT, así cada test arranca con las series vacías),
 * esa ferretería en {@link TenantContext} y un simulador con el reloj fijo el miércoles 2026-10-28 a las 10:15:30 de Buenos
 * Aires y sin latencia. Los repositorios, las transacciones y los montos son los del contexto.
 */
abstract class SimuladorWsfeTestBase extends IntegrationTestBase {

    static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final LocalDate HOY = LocalDate.of(2026, 10, 28);
    static final Instant AHORA = HOY.atTime(10, 15, 30).atZone(ZONA).toInstant();
    static final Clock RELOJ = Clock.fixed(AHORA, ZONA);

    /** CUIT de un Responsable Inscripto, con dígito verificador válido (30-78601330-1). */
    static final long CUIT_RECEPTOR = 30786013301L;
    /** CUIT con el dígito verificador mal: el simulador no la tiene en su padrón. */
    static final long CUIT_FUERA_DEL_PADRON = 20123456780L;
    /** DNI de un Consumidor Final. */
    static final long DNI = 30123456L;

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

    long ferreteriaId;
    long cuit;
    Auth auth;
    SimuladorWsfe simulador;

    @BeforeEach
    void ferreteriaNueva() {
        cuit = cuitNuevo();
        ferreteriaId = ferreteriaNuevaConCuit(cuit);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        auth = auth(cuit);
        simulador = simulador(RELOJ, Duration.ZERO);
    }

    @AfterEach
    void sinFerreteria() {
        TenantContext.clear();
    }

    long ferreteriaNuevaConCuit(long cuitEmisor) {
        String conGuiones = String.valueOf(cuitEmisor).replaceFirst("(\\d{2})(\\d{8})(\\d)", "$1-$2-$3");
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Simulador " + conGuiones, conGuiones);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, conGuiones);
    }

    /** Un simulador nuevo sobre el mismo estado (la misma base). */
    SimuladorWsfe simulador(Clock reloj, Duration latencia) {
        return new SimuladorWsfe(series, comprobantes, transacciones, reloj, latencia, topeRg4444, minimoFce);
    }

    long ultimo(int cbteTipo) {
        return simulador.feCompUltimoAutorizado(auth, 1, cbteTipo).cbteNro();
    }

    FeCaeResponse solicitar(PedidoDePrueba pedido) {
        return simulador.feCaeSolicitar(auth, pedido.armar());
    }

    /** Un CUIT nuevo de persona jurídica (30), con dígito verificador válido. */
    static long cuitNuevo() {
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        while (true) {
            String base = "30" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
            int suma = 0;
            for (int i = 0; i < pesos.length; i++) {
                suma += (base.charAt(i) - '0') * pesos[i];
            }
            int verificador = 11 - suma % 11;
            if (verificador == 11) {
                verificador = 0;
            }
            if (verificador != 10) {
                return Long.parseLong(base + verificador);
            }
        }
    }

    static Auth auth(long cuitEmisor) {
        return new Auth(Ambiente.SIMULADOR, cuitEmisor);
    }

    static PedidoDePrueba facturaB() {
        return PedidoDePrueba.facturaB(HOY);
    }

    /**
     * El comprobante rechazado por una validación excluyente, como el ejemplo 4 del manual (p. 86): R en la cabecera y en el
     * detalle, sin CAE ni vencimiento, el código en las {@code Observaciones} del detalle con el texto del manual, y
     * {@code Errors} vacío.
     */
    static void rechazadoCon(FeCaeResponse respuesta, int codigo) {
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.errors()).isEmpty();
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp().cae()).isNull();
        assertThat(respuesta.feDetResp().caeFchVto()).isNull();
        assertThat(respuesta.feDetResp().observaciones()).extracting(Obs::code).contains(codigo);
        assertThat(respuesta.feDetResp().observaciones()).filteredOn(o -> o.code() == codigo).extracting(Obs::msg)
                .containsExactly(CodigoManual.de(codigo).texto());
    }

    /**
     * El pedido rechazado en la cabecera, como el ejemplo 3 del manual (p. 84): R en la cabecera, el código en {@code Errors}
     * con el texto del manual y sin detalle.
     */
    static void rechazadoEnLaCabeceraCon(FeCaeResponse respuesta, int codigo) {
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(respuesta.feDetResp()).isNull();
        assertThat(respuesta.errors()).containsExactly(new Err(codigo, CodigoManual.de(codigo).texto()));
    }

    /** Aprobado (A en la cabecera y en el detalle, con CAE de 14 dígitos) y sin Errors. */
    static void aprobado(FeCaeResponse respuesta) {
        assertThat(respuesta.errors()).isEmpty();
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.A);
        assertThat(respuesta.feDetResp().cae()).matches("\\d{14}");
    }
}
