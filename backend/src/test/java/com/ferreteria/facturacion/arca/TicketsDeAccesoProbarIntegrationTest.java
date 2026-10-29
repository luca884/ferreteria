package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Probar conexión» y el bloqueo de C4 (rama 6, S3 y S8): {@link TicketsDeAcceso#probar} es la única forma de llamar a WSAA
 * con la facturación frenada, porque una prueba OK es lo que la destraba (el Dueño autorizó el certificado a wsfe en ARCA,
 * por ejemplo). Devuelve el TA con su vencimiento, para el detalle del paso AUTENTICACION. Un nuevo rechazo del certificado
 * vuelve a bloquear, con el código nuevo.
 */
class TicketsDeAccesoProbarIntegrationTest extends TicketsDeAccesoTestBase {

    private void bloqueada() {
        jdbc.update("update config_arca set bloqueo_codigo = 'coe.notAuthorized', bloqueo_mensaje = 'Computador no "
                + "autorizado a acceder al servicio', bloqueado_en = ? where ferreteria_id = ?", utc(AHORA), ferreteriaId);
    }

    private String bloqueo() {
        return jdbc.queryForObject("select bloqueo_codigo from config_arca where ferreteria_id = ?", String.class,
                ferreteriaId);
    }

    @Test
    @DisplayName("con el bloqueo, ticket() no llama a WSAA pero probar() sí, y da el TA con su vencimiento; el bloqueo sigue")
    void probarLlamaAWsaaAunqueEsteBloqueada() {
        bloqueada();
        TicketsDeAcceso tickets = instancia();

        assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isInstanceOf(PedidoNoLlegoException.class);
        assertThat(wsaa.llamadas).hasValue(0);

        TicketsDeAcceso.Probado probado = tickets.probar(Ambiente.HOMOLOGACION, WSFE);

        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(probado.ticket().token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(probado.ticket().cuit()).isEqualTo(cuit);
        assertThat(probado.vence()).isEqualTo(AHORA.plus(wsaa.vigencia));
        // lo levanta «Probar conexión» cuando pasan los cuatro pasos, no el ticket solo
        assertThat(bloqueo()).isEqualTo("coe.notAuthorized");
    }

    @Test
    @DisplayName("probar() con otro rechazo del certificado vuelve a bloquear con el código nuevo, y la falla trae el fault")
    void probarConOtroRechazoVuelveABloquear() {
        bloqueada();
        wsaa.luegoFault("cms.cert.expired", "Certificado expirado");

        assertThatThrownBy(() -> instancia().probar(Ambiente.HOMOLOGACION, WSFE))
                .isInstanceOf(PedidoNoLlegoException.class)
                .cause().isInstanceOfSatisfying(FaultWsaaException.class, fault -> {
                    assertThat(fault.codigo()).isEqualTo("cms.cert.expired");
                    assertThat(fault.faultstring()).isEqualTo("Certificado expirado");
                });
        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(bloqueo()).isEqualTo("cms.cert.expired");
    }

    @Test
    @DisplayName("probar() reusa el TA vigente sin otro loginCms, con su vencimiento")
    void probarReusaElTaVigente() {
        TicketsDeAcceso tickets = instancia();
        tickets.ticket(Ambiente.HOMOLOGACION, WSFE);
        reloj.fijar(AHORA.plus(Duration.ofMinutes(5)));

        TicketsDeAcceso.Probado probado = tickets.probar(Ambiente.HOMOLOGACION, WSFE);

        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(probado.ticket().token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(probado.vence()).isEqualTo(AHORA.plus(wsaa.vigencia));
    }
}
