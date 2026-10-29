package com.ferreteria.facturacion.documento;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ferreteria.facturacion.api.FacturacionParaVenta.AccionFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Bandeja;
import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ErrorArca;
import com.ferreteria.facturacion.api.FacturacionParaVenta.FacturaConsultada;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ObservacionArca;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lo que la bandeja de Ventas y los avisos muestran de un registro fiscal (cu-07-contingencias C1 y C7), armado solo con el
 * registro vigente. Función pura, como {@link HojaFiscal}: acá se decide qué se muestra y quien lo pinta solo lo copia.
 *
 * <p>Lo que dijo ARCA se muestra tal cual, leído del JSON guardado ({@code [{"code":..,"msg":..}]}). Cuando no la autorizó,
 * los motivos son sus {@code Errors} y sus {@code Observaciones} (el manual llama «Obs» también al motivo del rechazo): van
 * todos en {@code errores}, con la explicación de los códigos conocidos ({@link ExplicacionesArca}). Las
 * {@code Observaciones} de una autorizada son avisos: van en {@code observaciones}.
 */
public final class BandejaFiscal {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<EstadoFiscal> PENDIENTES = EnumSet.of(EstadoFiscal.PENDIENTE, EstadoFiscal.EN_CURSO,
            EstadoFiscal.EN_VERIFICACION, EstadoFiscal.CAE_NO_COINCIDE, EstadoFiscal.SERIE_DESINCRONIZADA);
    private static final Set<EstadoFiscal> RECHAZADAS = EnumSet.of(EstadoFiscal.RECHAZADO, EstadoFiscal.FUERA_DE_FECHA);

    private BandejaFiscal() {
    }

    /** Los estados de cada bandeja (C1). */
    public static Set<EstadoFiscal> estados(Bandeja bandeja) {
        return switch (bandeja) {
            case PENDIENTES -> PENDIENTES;
            case RECHAZADAS -> RECHAZADAS;
        };
    }

    /** RECHAZADO o FUERA_DE_FECHA: lo único que se reintenta o se deja sin factura (C2, C3). */
    public static boolean rechazada(ComprobanteFiscal registro) {
        return RECHAZADAS.contains(registro.getEstado());
    }

    /** Salió con el ticket provisorio y ya está AUTORIZADO: hay que reimprimir la factura (S6). */
    public static boolean reimprimir(ComprobanteFiscal registro) {
        return registro.isTicketProvisorio() && registro.getEstado() == EstadoFiscal.AUTORIZADO;
    }

    /**
     * La factura en el detalle de Ventas, con {@code documento} de la misma lectura del registro y
     * {@code fechaNuevaRequerida} ya calculada por quien conoce la serie y el reloj.
     */
    public static FacturaConsultada consultada(ComprobanteFiscal registro, DocumentoFiscal documento,
            boolean fechaNuevaRequerida) {
        boolean autorizada = registro.getEstado() == EstadoFiscal.AUTORIZADO;
        List<ErrorArca> errores = new ArrayList<>(errores(registro.getErrores()));
        List<ObservacionArca> observaciones = new ArrayList<>();
        for (ObservacionArca observacion : observaciones(registro.getObservaciones())) {
            if (autorizada) {
                observaciones.add(observacion);
            } else {
                errores.add(new ErrorArca(observacion.codigo(), observacion.mensaje(),
                        ExplicacionesArca.de(observacion.codigo())));
            }
        }
        boolean rechazada = rechazada(registro);
        List<AccionFiscal> acciones = rechazada ? List.of(AccionFiscal.REINTENTAR, AccionFiscal.DEJAR_SIN_FACTURA)
                : List.of();
        return new FacturaConsultada(documento, errores, observaciones, registro.getMotivoSinFactura(),
                registro.getUsuarioSinFacturaId(), registro.getSinFacturaEn(), acciones, rechazada && fechaNuevaRequerida,
                reimprimir(registro));
    }

    /**
     * «Factura B 00001-00000045» (o, desde la rama 8, «Nota de crédito B 00001-00000003») si es válida como factura; si no,
     * «X · <marca>», como el X de la hoja (C7).
     */
    public static String factura(ComprobanteFiscal registro) {
        if (!HojaFiscal.validoComoFactura(registro)) {
            return HojaFiscal.letra(registro) + " · " + HojaFiscal.marca(registro);
        }
        return String.format("%s %s %05d-%08d", registro.esNotaDeCredito() ? "Nota de crédito" : "Factura",
                registro.getLetra().name(), registro.getPuntoVenta(), registro.getNumero());
    }

    private static List<ErrorArca> errores(String json) {
        List<ErrorArca> errores = new ArrayList<>();
        for (JsonNode nodo : arreglo(json)) {
            int codigo = nodo.path("code").asInt();
            errores.add(new ErrorArca(codigo, nodo.path("msg").asText(null), ExplicacionesArca.de(codigo)));
        }
        return errores;
    }

    private static List<ObservacionArca> observaciones(String json) {
        List<ObservacionArca> observaciones = new ArrayList<>();
        for (JsonNode nodo : arreglo(json)) {
            observaciones.add(new ObservacionArca(nodo.path("code").asInt(), nodo.path("msg").asText(null)));
        }
        return observaciones;
    }

    /** El arreglo guardado; sin nada guardado (o con otra forma), ninguno. */
    private static JsonNode arreglo(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createArrayNode();
        }
        try {
            JsonNode leido = JSON.readTree(json);
            return leido.isArray() ? leido : JSON.createArrayNode();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo leer lo que respondió ARCA, guardado en el registro fiscal.", e);
        }
    }
}
