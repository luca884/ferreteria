package com.ferreteria.facturacion.documento;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.facturacion.api.FacturacionParaVenta.AlicuotaDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EmisorDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Transparencia;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.EncuadreFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.Letra;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * La hoja de la factura de una venta (CU-07 paso 5; cu-07-factura-mostrador S5, S6, S7, S12 y S14), y desde la rama 8 la
 * de su nota de crédito (CU-08, S8: el código 3, 8 u 13 y la factura asociada, con QR solo en producción), armada solo con la
 * copia del registro fiscal y su desglose: nunca con la configuración ni con los datos de hoy de la ferretería o del
 * cliente. Función pura: acá se decide todo lo que la hoja muestra, y quien imprime solo lo pinta.
 *
 * <p>El cerrojo de la apariencia (S6, «lo simulado nunca parece fiscal»): es válida como factura solo en PRODUCCION, con
 * el registro AUTORIZADO y su CAE. Si no, es un documento X, sin letra A/B/C, sin código, punto de venta, número, CAE ni
 * QR, con la marca del caso (S12) debajo de «DOCUMENTO NO VALIDO COMO FACTURA». Lo que depende de la letra del registro
 * (el cuerpo que discrimina el IVA, alícuotas, transparencia y leyenda) se arma igual, porque no hace pasar al X por una
 * factura: así el X de una A muestra el formato de la A (S13) antes de producción.
 *
 * <p>Los textos de la condición frente al IVA son los de «Comprobantes en línea»; la transparencia (Ley 27.743) va solo en
 * la B a Consumidor Final, con el IVA guardado como «IVA Contenido»; la leyenda de la Ley 27.618 (RG 5022/2021), solo en
 * la A a un Monotributista, con la misma regla que el alta ({@link EncuadreFiscal}).
 */
public final class HojaFiscal {

    static final String SIMULADO = "SIMULADO — sin validez fiscal";
    static final String HOMOLOGACION = "HOMOLOGACIÓN — sin validez fiscal";
    static final String TICKET_PROVISORIO = "TICKET PROVISORIO — factura en trámite con ARCA";
    static final String SIN_FACTURA = "SIN FACTURA — ARCA no la autorizó";

    static final String LEYENDA_LEY_27618 = "El crédito fiscal discriminado en el presente comprobante, sólo podrá ser "
            + "computado a efectos del Régimen de Sostenimiento e Inclusión Fiscal para Pequeños Contribuyentes de la Ley "
            + "Nº 27.618";

    /** Los estados de producción en los que ARCA todavía puede autorizarlo: el ticket provisorio. */
    private static final Set<EstadoFiscal> EN_TRAMITE =
            EnumSet.of(EstadoFiscal.PENDIENTE, EstadoFiscal.EN_CURSO, EstadoFiscal.EN_VERIFICACION);

    private static final String LETRA_X = "X";
    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2);

    private HojaFiscal() {
    }

    /** S6: solo en PRODUCCION, AUTORIZADO y con CAE. */
    public static boolean validoComoFactura(ComprobanteFiscal registro) {
        return registro.getAmbiente() == Ambiente.PRODUCCION && registro.getEstado() == EstadoFiscal.AUTORIZADO
                && registro.getCae() != null;
    }

    /** La hoja de la venta, con {@code estado} (de la misma lectura) y el desglose guardado del registro. */
    public static DocumentoFiscal de(ComprobanteFiscal registro, List<ComprobanteFiscalAlicuota> desglose,
            EstadoFiscalVenta estado) {
        boolean valido = validoComoFactura(registro);
        CondicionIvaReceptor condicionReceptor = condicionReceptor(registro.getReceptorCondicionIvaId());
        boolean leyenda = EncuadreFiscal.de(registro.getEmisorCondicionIva(), condicionReceptor).leyendaLey27618();
        boolean transparencia = registro.getLetra() == Letra.B
                && condicionReceptor == CondicionIvaReceptor.CONSUMIDOR_FINAL;
        String qr = valido ? QrArca.texto(datosDelQr(registro)) : null;
        return new DocumentoFiscal(estado, valido, letra(registro), discriminaIva(registro),
                valido ? registro.getCbteTipo() : null,
                valido ? null : marca(registro), valido ? registro.getPuntoVenta() : null,
                valido ? registro.getNumero() : null, fecha(registro), valido ? registro.getCae() : null,
                valido ? registro.getCaeVencimiento() : null, emisor(registro), receptor(registro, condicionReceptor),
                registro.getImpTotal(), registro.getImpNeto(), registro.getImpIva(), registro.getImpTotConc(),
                registro.getImpOpEx(), desglose.stream().map(HojaFiscal::alicuota).toList(),
                transparencia ? new Transparencia(registro.getImpIva(), CERO) : null,
                leyenda ? LEYENDA_LEY_27618 : null, qr, valido ? QrArca.modulos(qr) : null,
                comprobanteAsociado(registro));
    }

    /**
     * El resumen para el listado (C2), con la misma letra que la hoja y, desde la rama 7, si hay que reimprimirla
     * ({@link BandejaFiscal#reimprimir}).
     */
    public static ResumenFiscal resumen(ComprobanteFiscal registro) {
        boolean valido = validoComoFactura(registro);
        return new ResumenFiscal(letra(registro), registro.getPuntoVenta(), registro.getNumero(),
                registro.getEstado().name(), registro.getAmbiente().name(), valido, BandejaFiscal.reimprimir(registro),
                registro.getClase().name(), valido ? registro.getCbteTipo() : null, comprobanteAsociado(registro),
                registro.getLetra().name());
    }

    /**
     * La factura que anula una nota de crédito (rama 8, S8), de la copia del asociado: «Factura B 00001-00000045» en
     * producción, donde la factura es fiscal (la NC sale solo en el ambiente de su factura, y solo de una AUTORIZADO); fuera
     * de producción, «X · <marca del ambiente>», como el texto de «llegó el CAE» ({@link BandejaFiscal#factura}): lo
     * simulado nunca parece fiscal. Null en una factura.
     */
    static String comprobanteAsociado(ComprobanteFiscal registro) {
        if (!registro.esNotaDeCredito()) {
            return null;
        }
        if (registro.getAmbiente() != Ambiente.PRODUCCION) {
            return LETRA_X + " · " + marca(registro);
        }
        return String.format("Factura %s %05d-%08d", registro.getLetra().name(), registro.getAsociadoPuntoVenta(),
                registro.getAsociadoNumero());
    }

    /** La letra de la hoja: la del registro si es válida como factura; X si no. */
    static String letra(ComprobanteFiscal registro) {
        return validoComoFactura(registro) ? registro.getLetra().name() : LETRA_X;
    }

    /**
     * S13: la hoja discrimina el IVA (renglones sin IVA con su alícuota, neto gravado e IVA por alícuota) solo en la A,
     * válida o X. No es {@link Letra#discriminaIva()}: ese dice si van alícuotas a ARCA, y la B también las lleva aunque
     * se imprima con precios con IVA.
     */
    static boolean discriminaIva(ComprobanteFiscal registro) {
        return registro.getLetra() == Letra.A;
    }

    /** S12: la marca del X, por ambiente y, en producción, por estado. */
    static String marca(ComprobanteFiscal registro) {
        return switch (registro.getAmbiente()) {
            case SIMULADOR -> SIMULADO;
            case HOMOLOGACION -> HOMOLOGACION;
            case PRODUCCION -> EN_TRAMITE.contains(registro.getEstado()) ? TICKET_PROVISORIO : SIN_FACTURA;
        };
    }

    /** La CbteFch; sin CbteFch (sin número), el día de la venta en Buenos Aires, como la calcularía el motor. */
    static LocalDate fecha(ComprobanteFiscal registro) {
        if (registro.getCbteFch() != null) {
            return registro.getCbteFch();
        }
        return LocalDate.ofInstant(registro.getRegistradoEn(), HoraArgentina.ZONA);
    }

    private static QrArca.Datos datosDelQr(ComprobanteFiscal r) {
        return new QrArca.Datos(r.getCbteFch(), r.cuitEmisora(), r.getPuntoVenta(), r.getCbteTipo(), r.getNumero(),
                r.getImpTotal(), r.getReceptorDocTipo(), r.getReceptorDocNro(), Long.parseLong(r.getCae()));
    }

    private static EmisorDelDocumento emisor(ComprobanteFiscal r) {
        return new EmisorDelDocumento(r.getEmisorRazonSocial(), String.valueOf(r.cuitEmisora()),
                textoEmisor(r.getEmisorCondicionIva()), r.getEmisorDomicilioComercial(), r.getEmisorIngresosBrutos(),
                r.getEmisorInicioActividades());
    }

    /** Sin identificar (DocTipo 99), sin tipo ni número de documento. */
    private static ReceptorDelDocumento receptor(ComprobanteFiscal r, CondicionIvaReceptor condicion) {
        TipoDocumentoReceptor tipo = tipoDocumento(r.getReceptorDocTipo());
        boolean identificado = tipo != TipoDocumentoReceptor.SIN_IDENTIFICAR;
        return new ReceptorDelDocumento(r.getReceptorNombre(), textoReceptor(condicion),
                identificado ? tipo.name() : null, identificado ? String.valueOf(r.getReceptorDocNro()) : null,
                r.getReceptorDomicilio(), condicion == CondicionIvaReceptor.CONSUMIDOR_FINAL);
    }

    private static AlicuotaDelDocumento alicuota(ComprobanteFiscalAlicuota a) {
        return new AlicuotaDelDocumento(alicuotaDeId(a.getIvaId()).porcentaje().setScale(2), a.getBaseImp(),
                a.getImporte());
    }

    static String textoEmisor(CondicionIvaEmisor condicion) {
        return switch (condicion) {
            case RESPONSABLE_INSCRIPTO -> "IVA RESPONSABLE INSCRIPTO";
            case MONOTRIBUTISTA -> "RESPONSABLE MONOTRIBUTO";
            case EXENTO -> "IVA EXENTO";
        };
    }

    static String textoReceptor(CondicionIvaReceptor condicion) {
        return switch (condicion) {
            case RESPONSABLE_INSCRIPTO -> "IVA RESPONSABLE INSCRIPTO";
            case MONOTRIBUTISTA -> "RESPONSABLE MONOTRIBUTO";
            case EXENTO -> "IVA EXENTO";
            case CONSUMIDOR_FINAL -> "CONSUMIDOR FINAL";
        };
    }

    private static CondicionIvaReceptor condicionReceptor(int condicionIvaReceptorId) {
        for (CondicionIvaReceptor condicion : CondicionIvaReceptor.values()) {
            if (condicion.condicionIvaReceptorId() == condicionIvaReceptorId) {
                return condicion;
            }
        }
        throw new IllegalStateException("El registro tiene una condición frente al IVA del receptor desconocida: "
                + condicionIvaReceptorId + ".");
    }

    private static TipoDocumentoReceptor tipoDocumento(int docTipo) {
        for (TipoDocumentoReceptor tipo : TipoDocumentoReceptor.values()) {
            if (tipo.docTipo() == docTipo) {
                return tipo;
            }
        }
        throw new IllegalStateException("El registro tiene un tipo de documento del receptor desconocido: " + docTipo + ".");
    }

    private static Alicuota alicuotaDeId(int ivaId) {
        for (Alicuota alicuota : Alicuota.values()) {
            if (alicuota.id() == ivaId) {
                return alicuota;
            }
        }
        throw new IllegalStateException("El desglose tiene una alícuota desconocida: " + ivaId + ".");
    }
}
