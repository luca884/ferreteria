package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaseComprobante;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.repository.ComprobanteFiscalAlicuotaRepository;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.ErrorDeHolder;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El motor de numeración (S5) contra MySQL real y el simulador: el alta congela lo que va a ARCA, la serie se numera desde
 * el último autorizado y en orden FIFO, la CbteFch se chequea antes de enviar, cada resultado de la llamada deja el registro
 * y el intento en su estado, y la recuperación de EN_VERIFICACION decide por FECompUltimoAutorizado. La batería de
 * concurrencia está en {@code MotorFiscalConcurrenciaIntegrationTest}.
 */
class MotorFiscalIntegrationTest extends MotorFiscalTestBase {

    private static final String NO_HABILITADA =
            "La facturación electrónica no está habilitada en esta ferretería: la habilita el super admin.";

    @Autowired
    ComprobanteFiscalRepository registros;
    @Autowired
    ComprobanteFiscalAlicuotaRepository alicuotas;
    @Autowired
    PlatformTransactionManager transacciones;

    private ComprobanteFiscal leer(long id) {
        return registros.findScoped(id).orElseThrow();
    }

    // ---- Alta ----

    @Test
    @DisplayName("el alta congela lo que va a ARCA: ambiente y punto de venta, encuadre, desglose, receptor, emisor y la hora")
    void elAltaCongelaLoQueVaAArca() {
        long venta = venta(ferreteriaId);
        ReceptorFiscal receptor = new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO, TipoDocumentoReceptor.CUIT,
                30786013301L, "Corralón Sur SA", "Av. Colón 2000, Mar del Plata");

        long id = motor.registrar(new AltaFiscal(venta, receptor, List.of(
                new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("100.00")),
                new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("100.00")),
                new RenglonFiscal(Alicuota.IVA_10_5, new BigDecimal("50.00")))));
        // Lo que cambie después en la ferretería no toca el registro.
        jdbc.update("update ferreteria set razon_social = 'Otra razón', domicilio_comercial = 'Otro domicilio', "
                + "ingresos_brutos = '0' where id = ?", ferreteriaId);

        ComprobanteFiscal r = leer(id);
        assertThat(r.getComprobanteId()).isEqualTo(venta);
        assertThat(r.getAmbiente()).isEqualTo(Ambiente.SIMULADOR);
        assertThat(r.getPuntoVenta()).isEqualTo(1);
        assertThat(r.getClase()).isEqualTo(ClaseComprobante.FACTURA);
        assertThat(r.getLetra()).isEqualTo(Letra.A);
        assertThat(r.getCbteTipo()).isEqualTo(FACTURA_A);
        assertThat(r.getEstado()).isEqualTo(EstadoFiscal.PENDIENTE);
        assertThat(r.getNumero()).isNull();
        assertThat(r.getCbteFch()).isNull();
        assertThat(r.getCae()).isNull();
        assertThat(r.getReceptorDocTipo()).isEqualTo(80);
        assertThat(r.getReceptorDocNro()).isEqualTo(30786013301L);
        assertThat(r.getReceptorCondicionIvaId()).isEqualTo(1);
        assertThat(r.getReceptorNombre()).isEqualTo("Corralón Sur SA");
        assertThat(r.getReceptorDomicilio()).isEqualTo("Av. Colón 2000, Mar del Plata");
        assertThat(r.getEmisorCuit()).isEqualTo(conGuiones(cuit));
        assertThat(r.getEmisorRazonSocial()).isEqualTo("Motor " + conGuiones(cuit));
        assertThat(r.getEmisorCondicionIva()).isEqualTo(CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);
        assertThat(r.getEmisorDomicilioComercial()).isEqualTo("Av. Luro 1234, Mar del Plata");
        assertThat(r.getEmisorIngresosBrutos()).isEqualTo("901-123456-7");
        assertThat(r.getEmisorInicioActividades()).isEqualTo("2015-03-01");
        // Desglose por comprobante: 21 % sobre $ 200 y 10,5 % sobre $ 50.
        assertThat(r.getImpTotal()).isEqualByComparingTo("250.00");
        assertThat(r.getImpNeto()).isEqualByComparingTo("210.54");
        assertThat(r.getImpIva()).isEqualByComparingTo("39.46");
        assertThat(r.getImpTotConc()).isEqualByComparingTo("0");
        assertThat(r.getImpOpEx()).isEqualByComparingTo("0");
        assertThat(alicuotas.deRegistroScoped(id))
                .extracting(ComprobanteFiscalAlicuota::getIvaId, a -> a.getBaseImp().toPlainString(),
                        a -> a.getImporte().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4, "45.25", "4.75"),
                        org.assertj.core.groups.Tuple.tuple(5, "165.29", "34.71"));
        assertThat(r.getRegistradoEn()).isEqualTo(AHORA);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sin configuración", "con el simulador y la facturación apagada", "sin ambiente"})
    @DisplayName("con la facturación deshabilitada o sin ambiente, el alta se niega y no guarda nada")
    void elAltaSeNiegaSinFacturacionHabilitada(String caso) {
        long f = ferreteriaSinConfig(cuitNuevo());
        switch (caso) {
            case "con el simulador y la facturación apagada" -> jdbc.update("insert into config_arca (ferreteria_id, "
                    + "de_prueba, ambiente, punto_venta) values (?, true, 'SIMULADOR', 1)", f);
            case "sin ambiente" -> jdbc.update("insert into config_arca (ferreteria_id, de_prueba) values (?, true)", f);
            default -> { }
        }
        TenantContext.setCurrentFerreteriaId(f);
        AltaFiscal alta = new AltaFiscal(venta(f), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))));

        assertThatThrownBy(() -> motor.registrar(alta)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage(NO_HABILITADA);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ?", Integer.class, f))
                .isZero();
    }

    @Test
    @DisplayName("C4: con la facturación frenada porque ARCA rechazó el certificado, el alta se niega con el código y no "
            + "guarda nada")
    void elAltaSeNiegaConElBloqueoDeArca() {
        jdbc.update("update config_arca set bloqueo_codigo = 'cms.cert.expired', bloqueo_mensaje = 'Certificado expirado', "
                + "bloqueado_en = '2026-10-28 13:00:00' where ferreteria_id = ?", ferreteriaId);
        AltaFiscal alta = new AltaFiscal(venta(ferreteriaId), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))));

        assertThatThrownBy(() -> motor.registrar(alta)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("La facturación con ARCA está frenada: ARCA rechazó el certificado (cms.cert.expired).");
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ?", Integer.class,
                ferreteriaId)).isZero();
    }

    @Test
    @DisplayName("el alta lee config_arca con FOR SHARE y lo sostiene hasta el commit")
    void elAltaSostieneUnLockCompartidoDeLaConfiguracion() {
        long config = jdbc.queryForObject("select id from config_arca where ferreteria_id = ?", Long.class, ferreteriaId);
        AltaFiscal alta = new AltaFiscal(venta(ferreteriaId), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))));

        new TransactionTemplate(transacciones).executeWithoutResult(estado -> {
            motor.registrar(alta);
            assertThat(BarreraDeLocks.sePuedeTomarYa("config_arca", config)).as("FOR UPDATE de otro, antes del commit")
                    .isFalse();
            assertThat(sePuedeLeerCompartida(config)).as("FOR SHARE de otro, antes del commit").isTrue();
        });

        assertThat(BarreraDeLocks.sePuedeTomarYa("config_arca", config)).as("FOR UPDATE de otro, después del commit")
                .isTrue();
    }

    /** ¿Otra transacción puede leer la fila con FOR SHARE ya mismo? (NOWAIT: falla si alguien la tiene exclusiva). */
    private static boolean sePuedeLeerCompartida(long config) {
        HolderJdbc holder;
        try {
            holder = BarreraDeLocks.holderJdbc("select id from config_arca where id = " + config + " for share nowait");
        } catch (ErrorDeHolder e) {
            return false;
        }
        holder.rollback();
        return true;
    }

    // ---- Numeración ----

    @Test
    @DisplayName("la primera vez consulta FECompUltimoAutorizado y numera desde ahí; A deja el CAE en el registro y la serie")
    void laPrimeraVezNumeraDesdeElUltimoAutorizado() {
        for (long n = 1; n <= 3; n++) {
            assertThat(autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(n)).feCabResp().resultado())
                    .isEqualTo(Resultado.A);
        }
        long id = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        ComprobanteFiscal r = leer(id);
        assertThat(r.getEstado()).isEqualTo(EstadoFiscal.AUTORIZADO);
        assertThat(r.getNumero()).isEqualTo(4);
        assertThat(r.getCbteFch()).isEqualTo(HOY);
        assertThat(r.getResultado()).isEqualTo("A");
        assertThat(r.getCae()).matches("\\d{14}").isEqualTo(caeEnArca(FACTURA_B, 4));
        assertThat(r.getCaeVencimiento()).isEqualTo(HOY.plusDays(10));
        assertThat(r.getObservaciones()).isEqualTo("[]");
        assertThat(r.getErrores()).isEqualTo("[]");
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(4);
        assertThat(jdbc.queryForMap("select ultimo_autorizado, ultima_cbte_fch, lease_dueno, lease_vence from serie_fiscal "
                + "where ferreteria_id = ? and cbte_tipo = 6", ferreteriaId))
                .containsEntry("ultimo_autorizado", 4L).containsEntry("ultima_cbte_fch", java.sql.Date.valueOf(HOY))
                .containsEntry("lease_dueno", null).containsEntry("lease_vence", null);
        List<Map<String, Object>> intentos = intentos(id);
        assertThat(intentos).hasSize(1);
        assertThat(intentos.get(0)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 4L)
                .containsEntry("cbte_fch", java.sql.Date.valueOf(HOY)).containsEntry("resultado", "A");
        assertThat((String) intentos.get(0).get("pedido")).contains("\"cbteDesde\":4", "\"cbteFch\":\"2026-10-28\"");
        assertThat((String) intentos.get(0).get("respuesta")).contains(r.getCae());
        assertThat(intentos.get(0).get("terminado_en")).isNotNull();
        assertThat(intentos.get(0).get("cerrado_en")).isNotNull();
    }

    @Test
    @DisplayName("FIFO por serie: el pendiente más viejo sale primero")
    void fifo() {
        long primero = registrarFacturaB();
        long segundo = registrarFacturaB();
        long tercero = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(List.of(numero(primero), numero(segundo), numero(tercero))).containsExactly(1L, 2L, 3L);
        assertThat(List.of(estado(primero), estado(segundo), estado(tercero))).containsOnly("AUTORIZADO");
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(3);
    }

    @Test
    @DisplayName("un rechazo en la cabecera (sin detalle) se decide por la cabecera: RECHAZADO con los Errors, y el número queda libre")
    void rechazoEnLaCabecera() {
        long rechazado = registrarFacturaB();
        long siguiente = registrarFacturaB();
        wsfe.rechazarEnLaCabecera(new Err(600, "ValidacionDeToken: No validaron las fechas del token."));

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        ComprobanteFiscal r = leer(rechazado);
        assertThat(r.getEstado()).isEqualTo(EstadoFiscal.RECHAZADO);
        assertThat(r.getResultado()).isEqualTo("R");
        assertThat(r.getNumero()).isNull();
        assertThat(r.getCbteFch()).isNull();
        assertThat(r.getCae()).isNull();
        assertThat(r.getErrores()).contains("600", "ValidacionDeToken: No validaron las fechas del token.");
        assertThat(intentos(rechazado)).singleElement().satisfies(i -> assertThat(i)
                .containsEntry("estado", "RECHAZADO").containsEntry("resultado", "R").containsEntry("numero", 1L));
        assertThat(numero(siguiente)).isEqualTo(1);
        assertThat(estado(siguiente)).isEqualTo("AUTORIZADO");
    }

    @Test
    @DisplayName("un pedido que no llegó deja el intento NO_LLEGO y el registro PENDIENTE sin número; el turno siguiente lo manda")
    void pedidoQueNoLlego() {
        long id = registrarFacturaB();
        wsfe.fallas().programar(Falla.NO_LLEGA);

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);

        assertThat(estado(id)).isEqualTo("PENDIENTE");
        assertThat(numero(id)).isNull();
        assertThat(intentos(id)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "NO_LLEGO").containsEntry("numero", 1L).containsEntry("resultado", null);
            assertThat(i.get("cerrado_en")).isNotNull();
        });
        assertThat(ultimoEnArca(FACTURA_B)).isZero();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(id)).isEqualTo("AUTORIZADO");
        assertThat(numero(id)).isEqualTo(1);
        assertThat(intentos(id)).extracting(i -> i.get("estado"), i -> i.get("numero"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NO_LLEGO", 1L),
                        org.assertj.core.groups.Tuple.tuple("AUTORIZADO", 1L));
    }

    @Test
    @DisplayName("un resultado incierto deja EN_VERIFICACION y traba la serie; si ARCA va en N−1, reenvía el mismo número y fecha")
    void resultadoInciertoYReenvioConNMenosUno() {
        long dudoso = registrarFacturaB();
        long detras = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);

        assertThat(estado(dudoso)).isEqualTo("EN_VERIFICACION");
        assertThat(numero(dudoso)).isEqualTo(1);
        assertThat(intentos(dudoso)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "EN_VERIFICACION").containsEntry("cerrado_en", null);
            assertThat(i.get("terminado_en")).isNotNull();
        });
        assertThat(estado(detras)).as("la serie quedó trabada").isEqualTo("PENDIENTE");
        assertThat(wsfe.solicitudes()).isEqualTo(1);

        reloj.avanzar(Duration.ofDays(1));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        ComprobanteFiscal r = leer(dudoso);
        assertThat(r.getEstado()).isEqualTo(EstadoFiscal.AUTORIZADO);
        assertThat(r.getNumero()).isEqualTo(1);
        assertThat(r.getCbteFch()).as("la fecha nunca cambia sola").isEqualTo(HOY);
        List<Map<String, Object>> intentos = intentos(dudoso);
        assertThat(intentos).extracting(i -> i.get("estado"), i -> i.get("numero"), i -> i.get("cbte_fch"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NO_LLEGO", 1L, java.sql.Date.valueOf(HOY)),
                        org.assertj.core.groups.Tuple.tuple("AUTORIZADO", 1L, java.sql.Date.valueOf(HOY)));
        assertThat((String) intentos.get(0).get("respuesta_verificacion")).contains("\"ultimoAutorizado\":0");
        assertThat(intentos.get(1).get("pedido")).isEqualTo(intentos.get(0).get("pedido"));
        assertThat(numero(detras)).isEqualTo(2);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(2);
    }

    @Test
    @DisplayName("si ARCA va más de uno atrás (último < N−1), el registro queda SERIE_DESINCRONIZADA con su número y la serie trabada")
    void serieDesincronizada() {
        long dudoso = registrarFacturaB();
        // La serie del motor cree que ARCA ya va en 5 (ARCA no tiene ninguno).
        jdbc.update("insert into serie_fiscal (ferreteria_id, ambiente, punto_venta, cbte_tipo, ultimo_autorizado, "
                + "ultima_cbte_fch) values (?, 'SIMULADOR', 1, 6, 5, '2026-10-27')", ferreteriaId);
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(numero(dudoso)).isEqualTo(6);
        long detras = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);

        ComprobanteFiscal r = leer(dudoso);
        assertThat(r.getEstado()).isEqualTo(EstadoFiscal.SERIE_DESINCRONIZADA);
        assertThat(r.getNumero()).as("sin renumerar").isEqualTo(6);
        assertThat(r.getCae()).isNull();
        assertThat(intentos(dudoso)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "ALERTA").containsEntry("numero", 6L).containsEntry("cerrado_en", null);
            assertThat((String) i.get("respuesta_verificacion")).contains("\"ultimoAutorizado\":0");
        });
        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);
        assertThat(estado(detras)).isEqualTo("PENDIENTE");
        assertThat(ultimoEnArca(FACTURA_B)).isZero();
    }

    // ---- CbteFch ----

    @Test
    @DisplayName("una CbteFch de más de 5 días antes del envío pasa a FUERA_DE_FECHA, sin número ni intento; el siguiente sale")
    void fueraDeFechaPorVieja() {
        long viejo = registrarFacturaB();
        reloj.avanzar(Duration.ofDays(6));
        long nuevo = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(viejo)).isEqualTo("FUERA_DE_FECHA");
        assertThat(numero(viejo)).isNull();
        assertThat(intentos(viejo)).isEmpty();
        assertThat(estado(nuevo)).isEqualTo("AUTORIZADO");
        assertThat(numero(nuevo)).isEqualTo(1);
        assertThat(leer(nuevo).getCbteFch()).isEqualTo(HOY.plusDays(6));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("una CbteFch anterior a la del último autorizado en ARCA pasa a FUERA_DE_FECHA sin llamar a FECAESolicitar")
    void fueraDeFechaPorAnteriorAlUltimo() {
        long id = registrarFacturaB();
        reloj.avanzar(Duration.ofDays(1));
        assertThat(autorizarAfuera(PedidoDePrueba.facturaB(HOY.plusDays(1)).numero(1)).feCabResp().resultado())
                .isEqualTo(Resultado.A);

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(id)).isEqualTo("FUERA_DE_FECHA");
        assertThat(intentos(id)).isEmpty();
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(jdbc.queryForMap("select ultimo_autorizado, ultima_cbte_fch from serie_fiscal where ferreteria_id = ?",
                ferreteriaId)).containsEntry("ultimo_autorizado", 1L)
                .containsEntry("ultima_cbte_fch", java.sql.Date.valueOf(HOY.plusDays(1)));
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("si ARCA va en N−1 pero la CbteFch ya no entra, no se reenvía: FUERA_DE_FECHA sin número y el intento NO_LLEGO")
    void reenvioConLaCbteFchFueraDeLaVentana() {
        reloj.fijar(HOY.minusDays(5).atTime(10, 0).atZone(ZONA).toInstant());
        long dudoso = registrarFacturaB();
        reloj.fijar(AHORA);
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).as("N−5 todavía entra").isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(estado(dudoso)).isEqualTo("EN_VERIFICACION");
        reloj.avanzar(Duration.ofDays(1));
        long detras = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(dudoso)).isEqualTo("FUERA_DE_FECHA");
        assertThat(numero(dudoso)).isNull();
        assertThat(leer(dudoso).getCbteFch()).isNull();
        assertThat(intentos(dudoso)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "NO_LLEGO").containsEntry("numero", 1L);
            assertThat(i.get("cerrado_en")).isNotNull();
            assertThat((String) i.get("respuesta_verificacion")).contains("\"ultimoAutorizado\":0");
        });
        assertThat(numero(detras)).as("el número quedó libre").isEqualTo(1);
        assertThat(estado(detras)).isEqualTo("AUTORIZADO");
        assertThat(wsfe.solicitudes()).as("ninguna solicitud con la CbteFch vencida").isEqualTo(2);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }

    // ---- Adaptador ----

    @Test
    @DisplayName("el adaptador sale del ambiente del registro: con el cableado de los tests (solo SIMULADOR), uno de "
            + "HOMOLOGACION da un error claro y no va al simulador")
    void elAdaptadorSaleDelAmbienteDelRegistro() {
        long venta = venta(ferreteriaId);
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, estado, receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, "
                + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
                + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
                + "values (?, ?, 'HOMOLOGACION', 'FACTURA', 'B', 6, 1, 'PENDIENTE', 99, 0, 5, ?, 'Motor', "
                + "'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234', '901-123456-7', '2015-03-01', 121.00, 100.00, 21.00, 0, 0, "
                + "'2026-10-28 13:15:30')", ferreteriaId, venta, conGuiones(cuit));
        long id = jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ?", Long.class, venta);

        assertThatThrownBy(() -> motor.atender(Ambiente.HOMOLOGACION, 1, FACTURA_B))
                .isInstanceOf(AmbienteNoDisponibleException.class)
                .hasMessageContaining("No hay conexión con ARCA para el ambiente HOMOLOGACION");

        assertThat(estado(id)).isEqualTo("PENDIENTE");
        assertThat(intentos(id)).isEmpty();
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(ultimoEnArca(FACTURA_B)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from serie_fiscal where ferreteria_id = ? and lease_dueno is not null",
                Integer.class, ferreteriaId)).isZero();
    }
}
