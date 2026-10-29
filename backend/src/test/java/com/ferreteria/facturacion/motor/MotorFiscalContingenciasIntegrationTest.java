package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que la rama 7 suma al motor contra MySQL real y el simulador (cu-07-contingencias S3, C6): el 10016 se entiende con
 * FECompUltimoAutorizado (el número atrasado o adelantado resincroniza la serie y renumera; el número próximo es la fecha,
 * FUERA_DE_FECHA). Un 602 de FECompConsultar con ARCA en N o más es una alerta sin renumerar, no un error transitorio; al sincronizar la serie, el 602 del último es «no existe».
 */
class MotorFiscalContingenciasIntegrationTest extends MotorFiscalTestBase {

    // ---- 10016 ----

    @Test
    @DisplayName("10016 con el número atrasado (ARCA ya lo tiene, de otro sistema): resincroniza la serie y renumera en la "
            + "misma atención; el intento rechazado guarda lo que dijo ARCA y el último autorizado")
    void elDiezMilDieciseisPorNumeroRenumera() {
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(2));
        long r = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 3L);
        assertThat(intentos(r)).extracting(i -> i.get("estado"), i -> i.get("numero"), i -> i.get("resultado"))
                .containsExactly(tuple("RECHAZADO", 2L, "R"), tuple("AUTORIZADO", 3L, "A"));
        assertThat((String) intentos(r).get(0).get("respuesta")).contains("10016");
        assertThat((String) intentos(r).get(0).get("respuesta_verificacion")).contains("\"ultimoAutorizado\":2");
        assertThat(ultimoDeLaSerie()).isEqualTo(3);
        assertThat(caeEnArca(FACTURA_B, 3)).isEqualTo(registro(r).get("cae"));
    }

    @Test
    @DisplayName("10016 con la serie adelantada (ARCA va atrás, como un homologación vaciado): la serie se resincroniza "
            + "hacia atrás y el registro sale con el número que sigue en ARCA")
    void elDiezMilDieciseisResincronizaHaciaAtras() {
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        jdbc.update("update serie_fiscal set ultimo_autorizado = 5 where ferreteria_id = ? and cbte_tipo = ?",
                ferreteriaId, FACTURA_B);
        long r = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
        assertThat(intentos(r)).extracting(i -> i.get("estado"), i -> i.get("numero"))
                .containsExactly(tuple("RECHAZADO", 6L), tuple("AUTORIZADO", 2L));
        assertThat(ultimoDeLaSerie()).isEqualTo(2);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(2);
    }

    @Test
    @DisplayName("10016 con el número próximo es la fecha: FUERA_DE_FECHA sin número, con lo que dijo ARCA tal cual; la "
            + "serie aprende la fecha del último y el número sigue libre")
    void elDiezMilDieciseisPorFechaQuedaFueraDeFecha() {
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        // La serie no sabe que el último de ARCA es de hoy: cree que es de hace tres días.
        jdbc.update("update serie_fiscal set ultima_cbte_fch = ? where ferreteria_id = ? and cbte_tipo = ?",
                Date.valueOf(HOY.minusDays(3)), ferreteriaId, FACTURA_B);
        long r = motor.registrar(new AltaFiscal(venta(ferreteriaId), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))), AHORA.minus(Duration.ofDays(2)));

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null)
                .containsEntry("cbte_fch", null).containsEntry("resultado", null).containsEntry("cae", null);
        assertThat((String) registro(r).get("observaciones")).contains("10016");
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "RECHAZADO")
                .containsEntry("numero", 2L).containsEntry("resultado", "R"));
        assertThat(fechaDeLaSerie()).isEqualTo(HOY);
        long siguiente = registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(siguiente)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
    }

    @Test
    @DisplayName("10016 sin poder preguntar el último a ARCA: el registro vuelve a PENDIENTE sin número, la serie no se toca "
            + "y la atención corta como un error transitorio; la siguiente lo resuelve")
    void elDiezMilDieciseisSinElUltimoEsTransitorio() {
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(2));
        long r = registrarFacturaB();
        wsfe.responderConErroresElSiguienteFeCompUltimoAutorizado(new Err(502, "Error interno de la transacción"));

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);

        assertThat(registro(r)).containsEntry("estado", "PENDIENTE").containsEntry("numero", null);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "RECHAZADO")
                .containsEntry("numero", 2L));
        assertThat(ultimoDeLaSerie()).isEqualTo(1);
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 3L);
    }

    // ---- 602 ----

    @Test
    @DisplayName("602 en la verificación con ARCA en N o más es una contradicción (la numeración es correlativa): alerta "
            + "CAE_NO_COINCIDE sin renumerar, porque nunca se reenvía a ciegas; no es un error transitorio")
    void elSeiscientosDosEnLaVerificacionEsUnaAlerta() {
        long r = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        // ARCA ya va en el 1, pero al consultarlo contesta que no lo tiene: si tarda en reflejar el nuestro, renumerar
        // duplicaría la factura de la venta.
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(1));
        wsfe.responderConErroresElSiguienteFeCompConsultar(new Err(602, "No existen datos en nuestros registros."));

        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);

        assertThat(registro(r)).containsEntry("estado", "CAE_NO_COINCIDE").containsEntry("numero", 1L)
                .containsEntry("cae", null);
        assertThat(intentos(r)).singleElement()
                .satisfies(i -> assertThat((String) i.get("respuesta_verificacion")).contains("602"));
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("un error del sistema de ARCA (no 602) en la consulta de la verificación es transitorio: corta sin alerta "
            + "y la atención siguiente adopta el CAE")
    void otroErrorEnLaVerificacionEsTransitorio() {
        long r = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        wsfe.responderConErroresElSiguienteFeCompConsultar(new Err(501, "Error interno de base de datos"));

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(registro(r)).containsEntry("estado", "EN_VERIFICACION").containsEntry("numero", 1L);

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cae", caeEnArca(FACTURA_B, 1));
    }

    @Test
    @DisplayName("al sincronizar la serie, un 602 del último sigue sin su fecha (como antes), y otro error de ARCA es "
            + "transitorio: no se manda nada")
    void alSincronizarElSeiscientosDosSigueYOtroErrorCorta() {
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(1));
        long r = registrarFacturaB();
        wsfe.responderConErroresElSiguienteFeCompConsultar(new Err(502, "Error interno de la transacción"));

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(registro(r)).containsEntry("estado", "PENDIENTE");
        assertThat(wsfe.solicitudes()).isZero();

        wsfe.responderConErroresElSiguienteFeCompConsultar(new Err(602, "No existen datos en nuestros registros."));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
    }

    // ---- Ayudas ----

    private Long ultimoDeLaSerie() {
        return jdbc.queryForObject("select ultimo_autorizado from serie_fiscal where ferreteria_id = ? and cbte_tipo = ?",
                Long.class, ferreteriaId, FACTURA_B);
    }

    private LocalDate fechaDeLaSerie() {
        return jdbc.queryForObject("select ultima_cbte_fch from serie_fiscal where ferreteria_id = ? and cbte_tipo = ?",
                LocalDate.class, ferreteriaId, FACTURA_B);
    }
}
