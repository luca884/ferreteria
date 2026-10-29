package com.ferreteria.facturacion.motor;

import com.ferreteria.common.HoraArgentina;
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
 *       libera;</li>
 *   <li>no llegó: el intento NO_LLEGO y el registro otra vez PENDIENTE, sin número;</li>
 *   <li>incierto (timeout de lectura, 5xx): EN_VERIFICACION, que traba la serie.</li>
 * </ul>
 * La verificación es lo primero de la atención siguiente (nunca en la misma, así ARCA tiene tiempo de terminar lo que
 * recibió), con {@code FECompUltimoAutorizado}: N o más → {@code FECompConsultar(N)} y se adopta el CAE solo si coinciden
 * todos los campos (si no, CAE_NO_COINCIDE); N−1 → se reenvía el mismo número y fecha en un intento nuevo, o, si esa
 * CbteFch ya no entra en la ventana, FUERA_DE_FECHA sin número y sin reenviar; menos → SERIE_DESINCRONIZADA. Las dos alertas traban la serie hasta que la vea una persona.
 *
 * <p>Una llamada que no llegó o quedó incierta corta la atención ({@link Atencion#SIN_RESPUESTA}); la sigue la próxima. El
 * adaptador sale del ambiente de la serie, que es el del registro ({@link WsfePorAmbiente}). Todo el tiempo sale del
 * {@code Clock} inyectado.
 */
@Service
public class MotorFiscal {

    /** ARCA trabaja en hora de Buenos Aires: de ahí salen la CbteFch y el día del envío. */
    private static final ZoneId ZONA_ARCA = HoraArgentina.ZONA;

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
     * Atiende la serie de la ferretería del request: si está libre la toma, resuelve lo que haya quedado en verificación y
     * manda sus pendientes en orden, y al final la suelta. Si la tiene otra instancia, no hace nada.
     *
     * @throws AmbienteNoDisponibleException si el ambiente todavía no tiene con quién hablar (homologación y producción,
     *     hasta la rama 6); no toca nada
     */
    public Atencion atender(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        ServicioWsfe wsfe = wsfePorAmbiente.para(ambiente);
        long serieId = pasos.asegurarSerie(ambiente, puntoVenta, cbteTipo);
        String dueno = UUID.randomUUID().toString();
        Instant ahora = reloj.instant();
        if (!pasos.tomar(serieId, dueno, ahora, ahora.plus(lease))) {
            return Atencion.OCUPADA;
        }
        try {
            return new Turno(wsfe, serieId, dueno).atender();
        } catch (PasoDescartadoException e) {
            return Atencion.SERIE_PERDIDA;
        } finally {
            pasos.soltar(serieId, dueno);
        }
    }

    /** Una toma de la serie: lo que se hace mientras es propia. */
    private final class Turno {

        private final ServicioWsfe wsfe;
        private final long serieId;
        private final String dueno;

        Turno(ServicioWsfe wsfe, long serieId, String dueno) {
            this.wsfe = wsfe;
            this.serieId = serieId;
            this.dueno = dueno;
        }

        Atencion atender() {
            while (true) {
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
            Auth auth = auth(registro);
            renovar();
            FeCompUltimoAutorizadoResponse ultimo;
            LocalDate fecha = null;
            try {
                ultimo = wsfe.feCompUltimoAutorizado(auth, serie.getPuntoVenta(), serie.getCbteTipo());
                if (!ultimo.errors().isEmpty()) {
                    return Optional.of(Atencion.SIN_RESPUESTA);
                }
                if (ultimo.cbteNro() > 0) {
                    renovar();
                    FeCompConsultarResponse consulta = wsfe.feCompConsultar(auth, serie.getCbteTipo(),
                            serie.getPuntoVenta(), ultimo.cbteNro());
                    if (consulta.resultGet() != null) {
                        fecha = consulta.resultGet().detalle().cbteFch();
                    }
                }
            } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
                return Optional.of(Atencion.SIN_RESPUESTA);
            }
            pasos.sincronizar(serieId, dueno, reloj.instant(), ultimo.cbteNro(), fecha);
            return Optional.empty();
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

            if (ultimo >= numero) {
                verificacion.put("consulta", consulta);
                ResultGet autorizado = consulta.resultGet();
                if (PedidoFiscal.coincide(enviado, autorizado)) {
                    pasos.adoptar(serieId, registro.getId(), intento.getId(), numero, cbteFch,
                            autorizado.codAutorizacion(), autorizado.fchVto(), JsonFiscal.de(autorizado.observaciones()),
                            JsonFiscal.de(verificacion), reloj.instant());
                    return Optional.empty();
                }
                pasos.alertar(registro.getId(), intento.getId(), numero, EstadoFiscal.CAE_NO_COINCIDE,
                        JsonFiscal.de(verificacion));
                return Optional.of(Atencion.TRABADA);
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
            pasos.alertar(registro.getId(), intento.getId(), numero, EstadoFiscal.SERIE_DESINCRONIZADA,
                    JsonFiscal.de(verificacion));
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

    /** La CUIT del emisor congelada en el registro y el ambiente del registro (nunca el de la configuración actual). */
    private static Auth auth(ComprobanteFiscal registro) {
        return new Auth(registro.getAmbiente(), registro.cuitEmisora());
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
