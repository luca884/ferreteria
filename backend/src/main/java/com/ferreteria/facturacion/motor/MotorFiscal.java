package com.ferreteria.facturacion.motor;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.arca.AlertaArca;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.EstadoIntento;
import com.ferreteria.facturacion.model.IntentoFiscal;
import com.ferreteria.facturacion.model.SerieFiscal;
import com.ferreteria.facturacion.model.VentanaCbteFch;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.ferreteria.facturacion.wsfe.ResultGet;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * El motor de numeración de ARCA (S5, regla «Nunca se reenvía a ciegas»). Da de alta el registro fiscal de una venta y
 * atiende cada serie (ferretería, ambiente, punto de venta, CbteTipo): la toma con un lease con dueño en la base, que vale
 * entre instancias, y manda sus pendientes en orden FIFO.
 *
 * <p>Por cada pendiente: N = último autorizado + 1 (la primera vez se pregunta con {@code FECompUltimoAutorizado}) y la
 * CbteFch es la fecha de Buenos Aires de la venta; si no entra en la ventana ({@link VentanaCbteFch}) pasa a
 * FUERA_DE_FECHA. Si entra, el intento ABIERTO y el registro EN_CURSO se guardan en una transacción corta ANTES de llamar;
 * la llamada va sin transacción ni conexión tomada (este motor no es transaccional, y {@link PasosFiscales}, que sí, no
 * conoce el puerto), y el resultado se guarda en otra transacción corta con UPDATE condicionales:
 * <ul>
 *   <li>A: AUTORIZADO, con el CAE, y el número pasa a ser el último de la serie;</li>
 *   <li>R (del detalle, o de la cabecera si no hay detalle): RECHAZADO, con lo que dijo ARCA tal cual; el número se
 *       libera. Salvo el 10016 (rama 7, C6), que no es un rechazo del comprobante: se pregunta el último de ARCA y, si el
 *       número no era el próximo, la serie se resincroniza (también hacia atrás) y el registro se renumera; si lo era, el
 *       problema es la fecha: FUERA_DE_FECHA;</li>
 *   <li>no llegó: el intento NO_LLEGO y el registro otra vez PENDIENTE, sin número;</li>
 *   <li>incierto (timeout de lectura, 5xx): EN_VERIFICACION, que traba la serie.</li>
 * </ul>
 * La verificación es lo primero de la atención siguiente (nunca en la misma, así ARCA tiene tiempo de terminar lo que
 * recibió), con {@code FECompUltimoAutorizado}: N o más → {@code FECompConsultar(N)} y se adopta el CAE solo si coinciden
 * todos los campos (si no, CAE_NO_COINCIDE; un 602 ahí también: con ARCA en N o más, N tendría que existir, y renumerar
 * a ciegas podría duplicar la factura); N−1 → se reenvía el mismo número y fecha en un intento nuevo, o, si esa
 * CbteFch ya no entra en la ventana, FUERA_DE_FECHA sin número y sin reenviar; menos → SERIE_DESINCRONIZADA. Las dos
 * alertas traban la serie hasta que la vea una persona, y quedan en el log con {@code ALERTA ARCA}.
 *
 * <p>Una llamada que no llegó o quedó incierta, o que volvió con un error del sistema de ARCA (no un 602), corta la atención
 * ({@link Atencion#SIN_RESPUESTA}); la sigue la próxima. La atención del job de reintento lleva un límite de tiempo
 * ({@link Atencion#SIN_TIEMPO}); la de la venta, no. El
 * adaptador sale del ambiente de la serie, que es el del registro ({@link WsfePorAmbiente}). Todo el tiempo sale del
 * {@code Clock} inyectado.
 */
@Service
public class MotorFiscal {

    /** ARCA trabaja en hora de Buenos Aires: de ahí salen la CbteFch y el día del envío. */
    private static final ZoneId ZONA_ARCA = HoraArgentina.ZONA;

    /** 10016 de FECAESolicitar: el número no es el próximo o la CbteFch no entra (el mismo código para los dos). */
    static final int ERROR_NUMERO_O_FECHA = 10016;
    /** 602 de FECompConsultar: «No existen datos en nuestros registros». */
    static final int NO_EXISTE = 602;

    private static final Logger log = LoggerFactory.getLogger(MotorFiscal.class);

    private final PasosFiscales pasos;
    private final WsfePorAmbiente wsfePorAmbiente;
    private final Clock reloj;
    private final Duration lease;

    /**
     * @param lease cuánto dura la toma de una serie; se estira antes de cada llamada
     * @param presupuestoLlamada lo más que puede tardar una llamada a WSFEv1; el lease tiene que superarlo (L5): si la toma
     *     venciera con la llamada en vuelo, otra instancia vería N−1 y reenviaría el mismo número
     */
    public MotorFiscal(PasosFiscales pasos, WsfePorAmbiente wsfePorAmbiente, Clock reloj,
            @Value("${app.facturacion.motor.lease}") Duration lease,
            @Value("${app.facturacion.motor.presupuesto-llamada}") Duration presupuestoLlamada) {
        if (lease.compareTo(presupuestoLlamada) <= 0) {
            throw new IllegalStateException("La toma de una serie (app.facturacion.motor.lease = " + lease
                    + ") tiene que durar más que una llamada a WSFEv1 (app.facturacion.motor.presupuesto-llamada = "
                    + presupuestoLlamada + "): si venciera con la llamada en vuelo, otra instancia reenviaría el mismo "
                    + "número.");
        }
        this.pasos = pasos;
        this.wsfePorAmbiente = wsfePorAmbiente;
        this.reloj = reloj;
        this.lease = lease;
    }

    /**
     * Da de alta el registro fiscal PENDIENTE de una venta de la ferretería del request, con lo que va a ARCA congelado y la
     * hora del {@code Clock}. Se suma a la transacción de quien llama. Sin facturación habilitada, se niega
     * ({@code ReglaDeNegocioException}). Devuelve el id del registro.
     */
    public long registrar(AltaFiscal alta) {
        return registrar(alta, reloj.instant());
    }

    /**
     * Lo mismo, con la hora de la venta en vez de la del {@code Clock}: la venta y su registro fiscal llevan el mismo
     * instante, y de él sale la CbteFch. Se suma a la transacción de quien llama.
     */
    public long registrar(AltaFiscal alta, Instant registradoEn) {
        return registrar(alta, registradoEn, registradoEn);
    }

    /**
     * Lo mismo, mirando la regla (la vigencia de la credencial) en {@code revisadaEn}, el instante en que la venta decidió
     * facturar, que puede ser anterior a {@code registradoEn} (rama 6, S15).
     */
    public long registrar(AltaFiscal alta, Instant registradoEn, Instant revisadaEn) {
        return pasos.registrar(alta, registradoEn, revisadaEn);
    }

    /**
     * «Reintentar» del Dueño (rama 7, D10): el registro vigente RECHAZADO o FUERA_DE_FECHA de la venta queda como historia
     * y nace otro PENDIENTE con la copia de la hora del {@code Clock} ({@link PasosFiscales#reintentar}). Exige la
     * transacción de quien llama, con el cliente ya tomado con lock exclusivo. El CAE se pide después del commit.
     *
     * @param alta la venta, con el receptor de hoy y sus renglones
     * @param fechaDeHoy si el Dueño confirmó que la factura salga con la fecha de hoy
     * @return el id del registro nuevo
     * @throws AccionFiscalImpedidaException si no se puede; no cambia nada
     */
    public long reintentar(AltaFiscal alta, boolean fechaDeHoy) {
        return pasos.reintentar(alta, fechaDeHoy, reloj.instant());
    }

    /**
     * «Dejar sin factura» del Dueño (rama 7, S12): el registro vigente RECHAZADO o FUERA_DE_FECHA de la venta pasa a
     * SIN_FACTURA con el motivo, el usuario y la hora ({@link PasosFiscales#dejarSinFactura}).
     */
    public void dejarSinFactura(long comprobanteId, String motivo, long usuarioId) {
        pasos.dejarSinFactura(comprobanteId, motivo, usuarioId);
    }

    /**
     * Atiende la serie de la ferretería del request: si está libre la toma, resuelve lo que haya quedado en verificación y
     * manda sus pendientes en orden, y al final la suelta. Si la tiene otra instancia, no hace nada. Sin límite de tiempo:
     * lo que usa la venta.
     *
     * @throws AmbienteNoDisponibleException si el ambiente todavía no tiene con quién hablar (homologación y producción,
     *     hasta la rama 6); no toca nada
     */
    public Atencion atender(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return atender(ambiente, puntoVenta, cbteTipo, Instant.MAX);
    }

    /**
     * Lo mismo, con un límite de tiempo (rama 7, el tope de la pasada del job, C5): antes de empezar cada paso (lo que quedó
     * en verificación, o el registro siguiente) mira el {@code Clock} y, si ya llegó a {@code limite}, termina en
     * {@link Atencion#SIN_TIEMPO} y suelta la serie. Lo que está en vuelo termina igual.
     */
    public Atencion atender(Ambiente ambiente, int puntoVenta, int cbteTipo, Instant limite) {
        ServicioWsfe wsfe = wsfePorAmbiente.para(ambiente);
        long serieId = pasos.asegurarSerie(ambiente, puntoVenta, cbteTipo);
        String dueno = UUID.randomUUID().toString();
        Instant ahora = reloj.instant();
        if (!pasos.tomar(serieId, dueno, ahora, ahora.plus(lease))) {
            return Atencion.OCUPADA;
        }
        try {
            return new Turno(wsfe, serieId, dueno, limite).atender();
        } catch (PasoDescartadoException e) {
            return Atencion.SERIE_PERDIDA;
        } finally {
            pasos.soltar(serieId, dueno);
        }
    }

    /**
     * Lo último autorizado de una serie según ARCA y su CbteFch.
     *
     * @param numero el último autorizado (0 si no tiene)
     * @param fecha su CbteFch; null sin comprobantes, o si ARCA no lo encuentra (602)
     * @param ultimo la respuesta de FECompUltimoAutorizado, tal cual
     * @param consulta la de FECompConsultar del último; null si no se consultó
     */
    private record UltimoEnArca(long numero, LocalDate fecha, FeCompUltimoAutorizadoResponse ultimo,
            FeCompConsultarResponse consulta) {

        /** Lo que se guarda en el intento como verificación. */
        Map<String, Object> verificacion() {
            Map<String, Object> verificacion = new LinkedHashMap<>();
            verificacion.put("ultimoAutorizado", numero);
            verificacion.put("cbteFch", fecha);
            if (consulta != null && !consulta.errors().isEmpty()) {
                verificacion.put("consulta", consulta);
            }
            return verificacion;
        }
    }

    /** Una toma de la serie: lo que se hace mientras es propia. */
    private final class Turno {

        private final ServicioWsfe wsfe;
        private final long serieId;
        private final String dueno;
        private final Instant limite;

        Turno(ServicioWsfe wsfe, long serieId, String dueno, Instant limite) {
            this.wsfe = wsfe;
            this.serieId = serieId;
            this.dueno = dueno;
            this.limite = limite;
        }

        Atencion atender() {
            while (true) {
                if (!reloj.instant().isBefore(limite)) {
                    return Atencion.SIN_TIEMPO;
                }
                Optional<IntentoFiscal> abierto = pasos.intentoAbierto(serieId);
                if (abierto.isPresent()) {
                    Optional<Atencion> fin = resolver(abierto.get());
                    if (fin.isPresent()) {
                        return fin.get();
                    }
                    continue;
                }
                SerieFiscal serie = pasos.serie(serieId);
                Optional<ComprobanteFiscal> siguiente = pasos.siguientePendiente(serie);
                if (siguiente.isEmpty()) {
                    return Atencion.TERMINADA;
                }
                ComprobanteFiscal registro = siguiente.get();
                if (serie.getUltimoAutorizado() == null) {
                    Optional<Atencion> fin = sincronizar(serie, registro);
                    if (fin.isPresent()) {
                        return fin.get();
                    }
                    continue;
                }
                LocalDate cbteFch = LocalDate.ofInstant(registro.getRegistradoEn(), ZONA_ARCA);
                if (!VentanaCbteFch.admite(cbteFch, diaDelEnvio(), serie.getUltimaCbteFch())) {
                    pasos.fueraDeFecha(registro.getId());
                    continue;
                }
                long numero = serie.getUltimoAutorizado() + 1;
                FeCaeReq pedido = PedidoFiscal.armar(registro, pasos.alicuotas(registro.getId()), numero, cbteFch);
                Instant ahora = reloj.instant();
                long intento = pasos.abrirIntento(serieId, dueno, registro.getId(), numero, cbteFch, JsonFiscal.de(pedido),
                        ahora, ahora.plus(lease));
                Optional<Atencion> fin = enviar(registro, intento, numero, cbteFch, pedido);
                if (fin.isPresent()) {
                    return fin.get();
                }
            }
        }

        /** El intento abierto de la serie: una alerta la traba; uno ABIERTO o EN_VERIFICACION se verifica. */
        private Optional<Atencion> resolver(IntentoFiscal intento) {
            if (intento.getEstado() == EstadoIntento.ALERTA) {
                return Optional.of(Atencion.TRABADA);
            }
            if (intento.getEstado() == EstadoIntento.ABIERTO) {
                // Quedó de una toma que venció o se cortó con la llamada sin terminar: no se sabe si llegó.
                pasos.enVerificacion(intento.getComprobanteFiscalId(), intento.getId(), intento.getNumero(),
                        JsonFiscal.de(falla("TomaSinResultado",
                                "La toma anterior de la serie terminó sin el resultado de la llamada.")),
                        reloj.instant());
            }
            return verificar(pasos.registro(intento.getComprobanteFiscalId()), intento);
        }

        /** La primera vez: el último autorizado según ARCA, y su CbteFch (para no mandar una anterior). */
        private Optional<Atencion> sincronizar(SerieFiscal serie, ComprobanteFiscal registro) {
            Optional<UltimoEnArca> ultimo = ultimoEnArca(auth(registro), serie.getPuntoVenta(), serie.getCbteTipo());
            if (ultimo.isEmpty()) {
                return Optional.of(Atencion.SIN_RESPUESTA);
            }
            pasos.sincronizar(serieId, dueno, reloj.instant(), ultimo.get().numero(), ultimo.get().fecha());
            return Optional.empty();
        }

        /**
         * {@code FECompUltimoAutorizado} y, si tiene alguno, {@code FECompConsultar} del último para su CbteFch, con la toma
         * estirada antes de cada llamada. Un 602 de la consulta es «no existe» (C6): sigue sin la fecha. Vacío si ARCA no
         * contestó: la llamada no llegó o quedó incierta, o vino con otro error (un error del sistema de ARCA, transitorio).
         */
        private Optional<UltimoEnArca> ultimoEnArca(Auth auth, int puntoVenta, int cbteTipo) {
            renovar();
            try {
                FeCompUltimoAutorizadoResponse ultimo = wsfe.feCompUltimoAutorizado(auth, puntoVenta, cbteTipo);
                if (!ultimo.errors().isEmpty()) {
                    return Optional.empty();
                }
                if (ultimo.cbteNro() == 0) {
                    return Optional.of(new UltimoEnArca(0, null, ultimo, null));
                }
                renovar();
                FeCompConsultarResponse consulta = wsfe.feCompConsultar(auth, cbteTipo, puntoVenta, ultimo.cbteNro());
                if (consulta.resultGet() != null) {
                    return Optional.of(new UltimoEnArca(ultimo.cbteNro(), consulta.resultGet().detalle().cbteFch(), ultimo,
                            consulta));
                }
                if (noExiste(consulta)) {
                    return Optional.of(new UltimoEnArca(ultimo.cbteNro(), null, ultimo, consulta));
                }
                return Optional.empty();
            } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
                return Optional.empty();
            }
        }

        /** La llamada, sin transacción ni conexión tomada, y su resultado en una transacción corta. */
        private Optional<Atencion> enviar(ComprobanteFiscal registro, long intento, long numero, LocalDate cbteFch,
                FeCaeReq pedido) {
            FeCaeResponse respuesta;
            try {
                respuesta = wsfe.feCaeSolicitar(auth(registro), pedido);
            } catch (PedidoNoLlegoException e) {
                pasos.noLlego(registro.getId(), intento, numero, JsonFiscal.de(falla(e)), reloj.instant());
                return Optional.of(Atencion.SIN_RESPUESTA);
            } catch (ResultadoInciertoException e) {
                pasos.enVerificacion(registro.getId(), intento, numero, JsonFiscal.de(falla(e)), reloj.instant());
                return Optional.of(Atencion.SIN_RESPUESTA);
            }
            Instant ahora = reloj.instant();
            FeCaeDetResponse detalle = respuesta.feDetResp();
            // Sin detalle (un error de la cabecera), decide la cabecera.
            Resultado resultado = detalle != null ? detalle.resultado() : respuesta.feCabResp().resultado();
            String observaciones = JsonFiscal.de(detalle == null ? List.of() : detalle.observaciones());
            String errores = JsonFiscal.de(respuesta.errors());
            if (resultado == Resultado.R && esDiezMilDieciseis(respuesta)) {
                return entenderDiezMilDieciseis(registro, intento, numero, observaciones, errores,
                        JsonFiscal.de(respuesta));
            }
            if (resultado == Resultado.R) {
                pasos.rechazado(registro.getId(), intento, numero, observaciones, errores, JsonFiscal.de(respuesta), ahora);
                return Optional.empty();
            }
            if (detalle != null && detalle.cae() != null && detalle.caeFchVto() != null) {
                pasos.autorizado(serieId, registro.getId(), intento, numero, cbteFch, detalle.cae(), detalle.caeFchVto(),
                        observaciones, errores, JsonFiscal.de(respuesta), ahora);
                return Optional.empty();
            }
            // A sin CAE: no hay con qué darlo por autorizado; se verifica como un resultado incierto.
            pasos.enVerificacion(registro.getId(), intento, numero, JsonFiscal.de(respuesta), ahora);
            return Optional.of(Atencion.SIN_RESPUESTA);
        }

        /**
         * Un R con 10016 (C6): el código es el mismo para el número y para la fecha, así que se pregunta el último de ARCA.
         * Si el número enviado no era el próximo, la serie se resincroniza (también hacia atrás) y el registro vuelve a
         * PENDIENTE sin número, para renumerarse en esta misma atención; pero si ARCA va atrás de números que la serie ya
         * tiene, es SERIE_DESINCRONIZADA sin renumerar. Si era el próximo, es la fecha: FUERA_DE_FECHA. Si ARCA no contesta,
         * el registro vuelve a PENDIENTE sin tocar la serie y la atención corta como transitoria.
         */
        private Optional<Atencion> entenderDiezMilDieciseis(ComprobanteFiscal registro, long intento, long numero,
                String observaciones, String errores, String respuesta) {
            Optional<UltimoEnArca> ultimo = ultimoEnArca(auth(registro), registro.getPuntoVenta(), registro.getCbteTipo());
            Instant ahora = reloj.instant();
            if (ultimo.isEmpty()) {
                pasos.rechazadoSinEntender(registro.getId(), intento, numero, respuesta, ahora);
                return Optional.of(Atencion.SIN_RESPUESTA);
            }
            UltimoEnArca u = ultimo.get();
            String verificacion = JsonFiscal.de(u.verificacion());
            if (u.numero() + 1 != numero && pasos.hayNumeroDespuesDe(registro, u.numero())) {
                // ARCA va atrás de números que esta serie ya tiene (un homologación vaciado, o ARCA que perdió algo nuestro):
                // renumerar chocaría con ellos o repetiría uno. Alerta sin renumerar, como «menos» en la verificación.
                pasos.desincronizadaPorArca(serieId, dueno, registro.getId(), intento, numero, respuesta, verificacion,
                        ahora, ahora.plus(lease));
                alertaEnElLog(registro, numero, EstadoFiscal.SERIE_DESINCRONIZADA);
                return Optional.of(Atencion.TRABADA);
            }
            if (u.numero() + 1 != numero) {
                pasos.renumerar(serieId, dueno, registro.getId(), intento, numero, u.numero(), u.fecha(), respuesta,
                        verificacion, ahora, ahora.plus(lease));
            } else {
                pasos.fueraDeFechaPorArca(serieId, dueno, registro.getId(), intento, numero, u.numero(), u.fecha(),
                        observaciones, errores, respuesta, verificacion, ahora, ahora.plus(lease));
            }
            return Optional.empty();
        }

        /** Resuelve un intento EN_VERIFICACION con FECompUltimoAutorizado y, si hace falta, FECompConsultar. */
        private Optional<Atencion> verificar(ComprobanteFiscal registro, IntentoFiscal intento) {
            Auth auth = auth(registro);
            long numero = intento.getNumero();
            LocalDate cbteFch = intento.getCbteFch();
            Map<String, Object> verificacion = new LinkedHashMap<>();
            renovar();
            long ultimo;
            FeCompConsultarResponse consulta = null;
            try {
                FeCompUltimoAutorizadoResponse respuesta = wsfe.feCompUltimoAutorizado(auth, registro.getPuntoVenta(),
                        registro.getCbteTipo());
                if (!respuesta.errors().isEmpty()) {
                    return Optional.of(Atencion.SIN_RESPUESTA);
                }
                ultimo = respuesta.cbteNro();
                if (ultimo >= numero) {
                    renovar();
                    consulta = wsfe.feCompConsultar(auth, registro.getCbteTipo(), registro.getPuntoVenta(), numero);
                }
            } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
                return Optional.of(Atencion.SIN_RESPUESTA);
            }
            verificacion.put("ultimoAutorizado", ultimo);
            FeCaeReq enviado = PedidoFiscal.armar(registro, pasos.alicuotas(registro.getId()), numero, cbteFch);

            if (ultimo >= numero && consulta.resultGet() == null) {
                if (!noExiste(consulta)) {
                    // Un error del sistema de ARCA, no una respuesta: se verifica en la próxima, sin alerta.
                    return Optional.of(Atencion.SIN_RESPUESTA);
                }
                // 602 (C6) con ARCA en N o más: la numeración de ARCA es correlativa, así que N tendría que existir. Es
                // una contradicción, no un «no existe» que habilite renumerar: si ARCA tarda en reflejar el nuestro,
                // renumerar duplicaría la factura de la venta. Alerta sin renumerar, como un CAE que no coincide.
                verificacion.put("consulta", consulta);
                return alertar(registro, intento, numero, EstadoFiscal.CAE_NO_COINCIDE, verificacion);
            }
            if (ultimo >= numero) {
                verificacion.put("consulta", consulta);
                ResultGet autorizado = consulta.resultGet();
                if (PedidoFiscal.coincide(enviado, autorizado)) {
                    pasos.adoptar(serieId, registro.getId(), intento.getId(), numero, cbteFch,
                            autorizado.codAutorizacion(), autorizado.fchVto(), JsonFiscal.de(autorizado.observaciones()),
                            JsonFiscal.de(verificacion), reloj.instant());
                    return Optional.empty();
                }
                return alertar(registro, intento, numero, EstadoFiscal.CAE_NO_COINCIDE, verificacion);
            }
            if (ultimo == numero - 1) {
                Instant ahora = reloj.instant();
                if (!VentanaCbteFch.admite(cbteFch, diaDelEnvio(), pasos.serie(serieId).getUltimaCbteFch())) {
                    pasos.fueraDeFechaSinReenvio(serieId, dueno, registro.getId(), intento.getId(), numero,
                            JsonFiscal.de(verificacion), ahora, ahora.plus(lease));
                    return Optional.empty();
                }
                long nuevo = pasos.reenviar(serieId, dueno, registro.getId(), intento.getId(), numero, cbteFch,
                        JsonFiscal.de(enviado), JsonFiscal.de(verificacion), ahora, ahora.plus(lease));
                return enviar(registro, nuevo, numero, cbteFch, enviado);
            }
            return alertar(registro, intento, numero, EstadoFiscal.SERIE_DESINCRONIZADA, verificacion);
        }

        /** Una alerta de la verificación: el registro y el intento pasan a la alerta, que queda en el log, y la serie se traba. */
        private Optional<Atencion> alertar(ComprobanteFiscal registro, IntentoFiscal intento, long numero, EstadoFiscal alerta,
                Map<String, Object> verificacion) {
            pasos.alertar(registro.getId(), intento.getId(), numero, alerta, JsonFiscal.de(verificacion));
            alertaEnElLog(registro, numero, alerta);
            return Optional.of(Atencion.TRABADA);
        }

        /** Estira la toma antes de cada llamada; si se perdió, la atención termina. */
        private void renovar() {
            Instant ahora = reloj.instant();
            pasos.renovar(serieId, dueno, ahora, ahora.plus(lease));
        }

        private LocalDate diaDelEnvio() {
            return LocalDate.ofInstant(reloj.instant(), ZONA_ARCA);
        }
    }

    /**
     * S13 (rama 7): cada alerta queda en el log con {@link AlertaArca}, una vez, al pasar el registro (las atenciones que
     * encuentran la serie trabada no la repiten). Solo identificadores: nada del pedido ni de la respuesta.
     */
    private static void alertaEnElLog(ComprobanteFiscal registro, long numero, EstadoFiscal alerta) {
        log.error(AlertaArca.MARCADOR, "{}: el registro fiscal {} de la ferretería {} quedó en {} (serie {} {}-{}, número "
                + "{}): la serie queda trabada hasta que la revise soporte.", AlertaArca.TEXTO, registro.getId(),
                registro.getFerreteriaId(), alerta, registro.getAmbiente(), registro.getPuntoVenta(), registro.getCbteTipo(),
                numero);
    }

    /** La CUIT del emisor congelada en el registro y el ambiente del registro (nunca el de la configuración actual). */
    private static Auth auth(ComprobanteFiscal registro) {
        return new Auth(registro.getAmbiente(), registro.cuitEmisora());
    }

    /**
     * Un R con el 10016 (el número o la CbteFch no son los próximos; manual 4.8): en las {@code Observaciones} del detalle,
     * donde lo pone ARCA para una validación del comprobante, o en los {@code Errors} (inferido: por si lo devuelve ahí).
     */
    static boolean esDiezMilDieciseis(FeCaeResponse respuesta) {
        FeCaeDetResponse detalle = respuesta.feDetResp();
        return (detalle != null && detalle.observaciones().stream().anyMatch(o -> o.code() == ERROR_NUMERO_O_FECHA))
                || respuesta.errors().stream().anyMatch(e -> e.code() == ERROR_NUMERO_O_FECHA);
    }

    /** {@code FECompConsultar} sin el comprobante y con el 602 («No existen datos en nuestros registros»): no existe (C6). */
    static boolean noExiste(FeCompConsultarResponse consulta) {
        return consulta.resultGet() == null && consulta.errors().stream().anyMatch(e -> e.code() == NO_EXISTE);
    }

    private static Map<String, String> falla(RuntimeException e) {
        return falla(e.getClass().getSimpleName(), e.getMessage());
    }

    private static Map<String, String> falla(String tipo, String mensaje) {
        Map<String, String> falla = new LinkedHashMap<>();
        falla.put("falla", tipo);
        falla.put("mensaje", mensaje);
        return falla;
    }
}
