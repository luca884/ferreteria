package com.ferreteria.facturacion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Puerto de {@code facturacion} para {@code ventas} (CU-07 pasos 3 y 5, D4 y D5): decide si una venta sale con factura
 * electrónica, da de alta su registro fiscal dentro de la transacción de la venta y pide el CAE después del commit. Solo
 * tipos del JDK: ventas traduce su cliente y sus renglones a los de este puerto. Opera sobre la ferretería del request.
 *
 * <p>Orden dentro de la transacción de la venta: {@link #decidir} antes de escribir nada (después del lock del cliente y
 * antes del numerador), {@link #registrar} como último paso, y {@link #autorizar} recién después del commit, sin
 * transacción. La venta no facturable no llama a nada.
 *
 * <p>La anulación de una venta (CU-08, rama 8) sigue el mismo orden: {@link #tomarParaAnular} después de la caja y el
 * cliente y antes del numerador, {@link #registrarNotaDeCredito} o {@link #cerrarSinCae} después, y {@link #autorizar} de
 * la nota de crédito recién después del commit.
 */
public interface FacturacionParaVenta {

    /**
     * Lee la configuración de ARCA de la ferretería con lock compartido hasta el fin de la transacción (mientras tanto el
     * panel no la cambia) y decide si la venta sale con factura en {@code instante}: el mismo que después recibe
     * {@link #registrar} como {@code decididaEn}, así la vigencia de la credencial se mira en un solo instante (rama 6,
     * S15). No escribe nada. Exige una transacción abierta.
     */
    Decision decidir(ReceptorVenta receptor, Instant instante);

    /**
     * Da de alta el registro fiscal PENDIENTE de la venta {@code comprobanteId}, con lo que va a ARCA congelado y
     * {@code fecha}, el instante de la venta (de él sale la CbteFch). Vuelve a mirar la regla en {@code decididaEn}, el
     * instante con el que se llamó a {@link #decidir}: lo que se decidió facturable se da de alta aunque la credencial venza
     * entre las dos llamadas (rama 6, S15). La venta lee {@code fecha} con su numerador tomado, después de decidir, así las
     * fechas de una ferretería siguen el orden de sus registros. Exige la transacción de la venta, con una
     * {@link Decision#factura()} tomada en ella. Devuelve el id del registro.
     */
    long registrar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, Instant fecha,
            Instant decididaEn);

    /** {@link #registrar(long, ReceptorVenta, List, Instant, Instant)} para quien decidió en el mismo instante de la venta. */
    long registrar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, Instant fecha);

    /**
     * Pide el CAE de la venta con una espera acotada ({@code app.facturacion.venta.espera}). Se llama sin transacción: con
     * una abierta lanza {@code IllegalStateException}. Nunca lanza por ARCA: lo que no se resolvió a tiempo vuelve en su
     * estado actual. Vacío si la venta no tiene registro fiscal.
     */
    Optional<EstadoFiscalVenta> autorizar(long comprobanteId);

    /** El estado fiscal actual de la venta, sin llamar a ARCA (la venta repetida). Vacío si no tiene registro fiscal. */
    Optional<EstadoFiscalVenta> estado(long comprobanteId);

    /**
     * El documento imprimible de la venta (CU-07 paso 5, cu-07-factura-mostrador C1), con su estado fiscal, en una sola
     * lectura del registro: el estado y la hoja no pueden no coincidir. Se arma solo con la copia del registro fiscal, nunca
     * con la configuración ni con el cliente de hoy. Sin llamar a ARCA y sin transacción propia de escritura. Vacío si la
     * venta no tiene registro fiscal (o es de otra ferretería).
     */
    Optional<DocumentoFiscal> documento(long comprobanteId);

    /**
     * El resumen fiscal de cada venta que tiene registro (CU-16, C2), por id de venta: las que no tienen, o son de otra
     * ferretería, no aparecen en el mapa.
     */
    Map<Long, ResumenFiscal> resumenes(Collection<Long> comprobanteIds);

    /**
     * Las ventas de la ferretería cuyo registro fiscal tiene ese número (S15), en cualquier letra y ambiente; con
     * {@code puntoVenta} null, en cualquier punto de venta. Solo los registros con número (no los PENDIENTE, RECHAZADO ni
     * FUERA_DE_FECHA).
     */
    List<Long> ventasConNumeroFiscal(Integer puntoVenta, long numero);

    /**
     * Lo que el cobro anticipa (S3, C3): si la ferretería factura ahora, con la misma regla que {@link #decidir} (la
     * facturación habilitada y los datos de CU-01 completos) pero sin lock, su ambiente y la letra que saldría para cada
     * condición frente al IVA del cliente. Vacío si no factura. La letra la sigue decidiendo el alta del registro.
     */
    Optional<FacturacionDelMostrador> paraElMostrador();

    // ---- La bandeja de Ventas, las acciones del Dueño y «llegó el CAE» (cu-07-contingencias C1 a C3 y C7) ----

    /** Las ventas de la ferretería cuyo registro vigente está en esa bandeja (C1), por id. Solo lectura. */
    List<Long> ventasEnBandeja(Bandeja bandeja);

    /**
     * La factura de la venta como la ve el detalle de Ventas (C1): la hoja de {@link #documento} y, de la misma lectura del
     * registro vigente, lo que dijo ARCA, quién la dejó sin factura, las acciones que valen hoy y si hay que reimprimirla.
     * Sin llamar a ARCA. Vacío si la venta no tiene registro fiscal (o es de otra ferretería).
     */
    Optional<FacturaConsultada> consulta(long comprobanteId);

    /**
     * De esas ventas, las que salieron con el ticket provisorio y ya están AUTORIZADO (C7), con el texto de su factura:
     * «Factura B 00001-00000045» si es válida como factura, o «X · <marca>» si no (lo simulado nunca parece fiscal). Las
     * demás, o de otra ferretería, no aparecen. Solo lectura.
     */
    Map<Long, String> provisoriasAutorizadas(Collection<Long> comprobanteIds);

    /**
     * Que la venta tenga un registro vigente RECHAZADO o FUERA_DE_FECHA (C2), sin cargarlo: quien va a reintentar lo
     * pregunta antes de mirar el cliente de hoy. Se suma a la transacción de quien llama, si tiene una.
     *
     * @throws FacturaImpedidaException SIN_FACTURA_RECHAZADA si no lo tiene
     */
    void exigirFacturaRechazada(long comprobanteId);

    /**
     * «Reintentar» del Dueño (C2, D10): el registro vigente RECHAZADO o FUERA_DE_FECHA de la venta queda como historia y nace
     * otro PENDIENTE con la copia de hoy: el receptor que pasa la venta (el cliente leído con su lock exclusivo), el emisor
     * de CU-01 y la letra recalculada. La fecha nunca cambia sola: sin {@code fechaDeHoy}, la de la venta si todavía entra
     * en ARCA. Exige la transacción de quien llama, con el cliente ya tomado: adentro se toman {@code config_arca} en S y
     * el registro. El CAE se pide después del commit, con {@link #autorizar}. Devuelve el id del registro nuevo.
     *
     * <p>Sobre una nota de crédito (rama 8, C1), {@code comprobanteId} es la NC: ignora {@code receptor} y {@code renglones}
     * y vuelve a copiar todo de la factura asociada, como {@link #registrarNotaDeCredito}; la factura tiene que ser del
     * ambiente de hoy.
     *
     * @throws FacturaImpedidaException SIN_FACTURA_RECHAZADA, FACTURACION_FRENADA o FECHA_NUEVA_REQUERIDA, y OTRO_AMBIENTE
     *     en una NC; no cambia nada
     */
    long reintentar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, boolean fechaDeHoy);

    /**
     * «Dejar sin factura» del Dueño (C3, S12): el registro vigente RECHAZADO o FUERA_DE_FECHA pasa a SIN_FACTURA con el
     * motivo (obligatorio, hasta 200 caracteres: si no, 400 en {@code motivo}), el usuario y la hora. Es definitivo.
     *
     * @throws FacturaImpedidaException SIN_FACTURA_RECHAZADA si no hay una rechazada; no cambia nada
     */
    void dejarSinFactura(long comprobanteId, String motivo, long usuarioId);

    // ---- La anulación de una venta (CU-08, rama 8; C1 de cu-08-nota-de-credito) ----

    /**
     * Cómo se anula la venta del lado fiscal. Exige la transacción de la anulación: va después de tomar la caja y el cliente
     * y antes del numerador, y toma el registro vigente de la venta con lock exclusivo hasta el commit (el paso «factura
     * original» del orden de locks): mientras tanto ni el job ni la venta siguiente de la serie lo mandan a ARCA. Si el
     * registro cambió con un «Reintentar» que la foto de la transacción no ve, lanza
     * {@code org.springframework.dao.CannotAcquireLockException}: la anulación se repite en una transacción nueva, como con
     * un deadlock.
     *
     * @return {@link ViaDeAnulacion#NOTA_DE_CREDITO} con la factura AUTORIZADO; {@link ViaDeAnulacion#ANULACION_INTERNA} sin
     *     registro, o con uno PENDIENTE (sin número ni envío abierto), RECHAZADO, FUERA_DE_FECHA o SIN_FACTURA
     * @throws FacturaImpedidaException ESPERAR_CAE con EN_CURSO, EN_VERIFICACION, CAE_NO_COINCIDE o SERIE_DESINCRONIZADA;
     *     con la factura AUTORIZADO, OTRO_AMBIENTE si el ambiente de hoy de la ferretería no es el de la factura (el
     *     cerrojo de ambiente), o FACTURACION_FRENADA si no puede facturar hoy, con el mismo motivo que «Reintentar». No
     *     cambia nada
     */
    ViaDeAnulacion tomarParaAnular(long ventaId);

    /**
     * Da de alta el registro PENDIENTE de la nota de crédito {@code notaId} (el comprobante de la anulación) de la venta, en
     * la serie de su letra (CbteTipo 3, 8 o 13): el ambiente, el punto de venta, la letra, el receptor, el emisor, los
     * importes y las alícuotas copiados del registro de la factura, nunca del cliente ni de los datos de hoy, y la factura
     * como comprobante asociado (tipo, punto de venta, número, CUIT del emisor y CbteFch). La fecha es la del {@code Clock}.
     * Exige la transacción de la anulación, después de {@link #tomarParaAnular} con NOTA_DE_CREDITO. El CAE se pide después
     * del commit, con {@link #autorizar}{@code (notaId)}. Devuelve el id del registro.
     *
     * @throws IllegalStateException si la venta no tiene su factura AUTORIZADO
     */
    long registrarNotaDeCredito(long notaId, long ventaId);

    /**
     * La anulación interna del lado fiscal (CU-08 punto 3, D12): el registro vigente PENDIENTE, RECHAZADO o FUERA_DE_FECHA de
     * la venta pasa a SIN_FACTURA con el motivo (obligatorio, hasta 200 caracteres: si no, 400 en {@code motivo}), el
     * usuario y la hora, con el mismo UPDATE condicional que el job: o lo manda el job o lo anula el Dueño, nunca las dos
     * cosas. Sin registro, o ya SIN_FACTURA, no hace nada. Exige la transacción de la anulación, después de
     * {@link #tomarParaAnular} con ANULACION_INTERNA.
     */
    void cerrarSinCae(long ventaId, String motivo, long usuarioId);

    /** Cómo se anula una venta del lado fiscal ({@link #tomarParaAnular}). */
    enum ViaDeAnulacion {
        /** Con una nota de crédito total, con su propio CAE: la venta tiene su factura AUTORIZADO. */
        NOTA_DE_CREDITO,
        /** Sin documento fiscal, con motivo: la venta no tiene CAE ({@link #cerrarSinCae}). */
        ANULACION_INTERNA
    }

    /** La bandeja de Ventas del Dueño (C1, CU-16 punto 7). */
    enum Bandeja {
        /** Todavía sin CAE: PENDIENTE, EN_CURSO, EN_VERIFICACION y las en revisión (CAE_NO_COINCIDE y SERIE_DESINCRONIZADA). */
        PENDIENTES,
        /** ARCA no la autorizó: RECHAZADO y FUERA_DE_FECHA. */
        RECHAZADAS
    }

    /** Lo que el Dueño puede hacer hoy con la factura de una venta (C2 y C3). */
    enum AccionFiscal {
        REINTENTAR,
        DEJAR_SIN_FACTURA
    }

    /**
     * Un error de ARCA, tal cual (C6), con la explicación de los códigos conocidos.
     *
     * @param explicacion qué quiere decir y qué hacer; null si el código no es uno de los conocidos
     */
    record ErrorArca(int codigo, String mensaje, String explicacion) {}

    /** Una observación de ARCA sobre una factura autorizada, tal cual. */
    record ObservacionArca(int codigo, String mensaje) {}

    /**
     * La factura de la venta en el detalle de Ventas (C1).
     *
     * @param documento la hoja, como la de {@link #documento}
     * @param errores lo que ARCA dijo al no autorizarla (sus {@code Errors} y, con R, sus {@code Observaciones}), en ese
     *     orden; vacío si no dijo nada
     * @param observaciones las {@code Observaciones} de una factura autorizada; vacío en las demás
     * @param motivoSinFactura por qué el Dueño la dejó sin factura; null si no
     * @param usuarioSinFacturaId quién (el id del usuario); null si no
     * @param sinFacturaEn cuándo; null si no
     * @param acciones las que valen hoy: las dos con un registro RECHAZADO o FUERA_DE_FECHA; ninguna en los demás
     * @param fechaNuevaRequerida true si es RECHAZADO o FUERA_DE_FECHA y la fecha de la venta ya no entra en ARCA hoy
     *     (reintentar pide confirmar la de hoy)
     * @param motivoFueraDeFecha por qué ARCA ya no acepta la fecha de una FUERA_DE_FECHA que no trae errores de ARCA (la
     *     dejó el motor); null en las demás (cu-07-advertencias-contingencias S2)
     * @param reimprimir true si salió con el ticket provisorio y ya está AUTORIZADO
     */
    record FacturaConsultada(DocumentoFiscal documento, List<ErrorArca> errores, List<ObservacionArca> observaciones,
            String motivoSinFactura, Long usuarioSinFacturaId, Instant sinFacturaEn, List<AccionFiscal> acciones,
            boolean fechaNuevaRequerida, String motivoFueraDeFecha, boolean reimprimir) {

        public FacturaConsultada {
            Objects.requireNonNull(documento, "Falta la hoja de la factura.");
            errores = List.copyOf(errores);
            observaciones = List.copyOf(observaciones);
            acciones = List.copyOf(acciones);
        }
    }

    /**
     * Lo que decide {@link #decidir}.
     *
     * @param factura true si la venta sale con factura electrónica (la facturación está habilitada y los datos de CU-01
     *     de la ferretería están completos); si no, la venta se cobra sin factura (D5)
     * @param receptorCompleto false si el receptor es Responsable Inscripto, Monotributista o Exento y le falta el CUIT o
     *     el domicilio (D4); con {@code factura} en false, siempre true
     * @param topeIdentificacion desde este total (inclusive) la venta sin cliente identificado no se factura: el tope de
     *     ARCA; null si {@code factura} es false
     */
    record Decision(boolean factura, boolean receptorCompleto, BigDecimal topeIdentificacion) {

        public static final Decision SIN_FACTURA = new Decision(false, true, null);
    }

    /** La condición frente al IVA del cliente, como la conoce este puerto. */
    enum CondicionIva {
        CONSUMIDOR_FINAL,
        RESPONSABLE_INSCRIPTO,
        MONOTRIBUTISTA,
        EXENTO
    }

    /** El documento del cliente, como lo conoce este puerto. */
    enum TipoDocumento {
        DNI,
        CUIT,
        CUIL
    }

    /**
     * Quien recibe la factura, copiado del cliente bajo su lock. Sin cliente, {@link #sinCliente()}: Consumidor Final sin
     * identificar.
     *
     * @param condicion la condición frente al IVA
     * @param tipoDocumento el documento, o null si no tiene
     * @param numeroDocumento solo dígitos, o null si no tiene documento
     * @param nombre la razón social o el nombre, o null sin cliente
     * @param domicilio el domicilio, o null
     */
    record ReceptorVenta(CondicionIva condicion, TipoDocumento tipoDocumento, String numeroDocumento, String nombre,
            String domicilio) {

        public ReceptorVenta {
            Objects.requireNonNull(condicion, "Falta la condición frente al IVA del receptor.");
            if ((tipoDocumento == null) != (numeroDocumento == null || numeroDocumento.isBlank())) {
                throw new IllegalArgumentException("El tipo y el número de documento van juntos.");
            }
        }

        public static ReceptorVenta sinCliente() {
            return new ReceptorVenta(CondicionIva.CONSUMIDOR_FINAL, null, null, null, null);
        }

        public boolean identificado() {
            return tipoDocumento != null;
        }
    }

    /**
     * Un renglón de la venta: su alícuota de IVA y su total con IVA (lo que se cobra, con descuento y recargo).
     *
     * @param alicuotaPorcentaje 21.00 significa 21 %
     * @param total en pesos, con dos decimales como mucho
     */
    record RenglonParaFacturar(BigDecimal alicuotaPorcentaje, BigDecimal total) {

        public RenglonParaFacturar {
            Objects.requireNonNull(alicuotaPorcentaje, "Falta la alícuota del renglón.");
            Objects.requireNonNull(total, "Falta el total del renglón.");
        }
    }

    /**
     * El estado fiscal de una venta, para la respuesta del cobro.
     *
     * @param estado PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO, FUERA_DE_FECHA, CAE_NO_COINCIDE,
     *     SERIE_DESINCRONIZADA o SIN_FACTURA
     * @param clase FACTURA o NOTA_CREDITO
     * @param letra A, B o C
     * @param puntoVenta el punto de venta del registro
     * @param numero el número fiscal, o null mientras no tenga
     * @param cae el CAE, o null si no está autorizado
     * @param caeVencimiento el vencimiento del CAE, o null
     * @param ambiente SIMULADOR, HOMOLOGACION o PRODUCCION
     */
    record EstadoFiscalVenta(String estado, String clase, String letra, int puntoVenta, Long numero, String cae,
            LocalDate caeVencimiento, String ambiente) {}

    /**
     * El documento imprimible de la venta (cu-07-factura-mostrador C1): lo que la hoja muestra, decidido acá y nunca en
     * quien imprime. La apariencia (S6): {@code validoComoFactura} solo con el ambiente PRODUCCION, el registro AUTORIZADO y
     * su CAE; si no, es un documento X: {@code letra} "X", con {@code codigo}, {@code puntoVenta}, {@code numero},
     * {@code cae}, {@code caeVencimiento}, {@code qr} y {@code qrModulos} en null y la {@code marca} del caso (S12). Lo que
     * depende de la letra del registro (el cuerpo que discrimina el IVA, alícuotas, transparencia y leyenda) se arma igual
     * en el X: así el X de una A se ve con el formato de la A (S13). Importes con escala 2.
     *
     * @param estadoFiscal el estado del registro, de la misma lectura que la hoja
     * @param validoComoFactura true solo en PRODUCCION, AUTORIZADO y con CAE
     * @param letra A, B o C del registro; X si no es válido como factura
     * @param discriminaIva true si el registro es una A, válida o X: la hoja muestra los renglones sin IVA con su alícuota
     *     y el pie con el neto gravado y el IVA por alícuota (S13); describe el cuerpo, no la apariencia fiscal
     * @param codigo el {@code CbteTipo} (1, 6 u 11; en una nota de crédito, 3, 8 o 13); null en el X
     * @param marca lo que el X muestra debajo de «DOCUMENTO NO VALIDO COMO FACTURA»; null en la factura válida
     * @param puntoVenta el punto de venta; null en el X
     * @param numero el número fiscal; null en el X
     * @param fecha la CbteFch; sin CbteFch, el día de la venta en Buenos Aires
     * @param cae el CAE; null en el X
     * @param caeVencimiento el vencimiento del CAE; null en el X
     * @param emisor la ferretería, como se copió al vender
     * @param receptor quien recibe el comprobante, como se copió al vender
     * @param total {@code ImpTotal}
     * @param neto {@code ImpNeto} (en la C, el total)
     * @param iva {@code ImpIVA} (en la C, 0)
     * @param noGravado {@code ImpTotConc}
     * @param exento {@code ImpOpEx}
     * @param alicuotas el desglose por alícuota guardado, en el orden de su Id; vacío en la C
     * @param transparencia la del Régimen de Transparencia Fiscal (Ley 27.743): solo en la B a Consumidor Final
     * @param leyendaMonotributo la leyenda de la Ley 27.618: solo en la A a un Monotributista
     * @param qr el texto del QR de ARCA ({@code https://www.arca.gob.ar/fe/qr/?p=...}); null en el X
     * @param qrModulos la matriz del QR sin zona de silencio, una fila por texto de '1' (oscuro) y '0' (claro), todas del
     *     mismo largo; null en el X
     * @param comprobanteAsociado en una nota de crédito (la clase de {@code estadoFiscal}), la factura que anula: «Factura B
     *     00001-00000045» en producción; fuera de producción, «X · <marca del ambiente>», porque lo simulado nunca parece
     *     fiscal. Null en una factura
     */
    record DocumentoFiscal(EstadoFiscalVenta estadoFiscal, boolean validoComoFactura, String letra,
            boolean discriminaIva, Integer codigo, String marca, Integer puntoVenta, Long numero, LocalDate fecha,
            String cae, LocalDate caeVencimiento, EmisorDelDocumento emisor, ReceptorDelDocumento receptor,
            BigDecimal total, BigDecimal neto, BigDecimal iva, BigDecimal noGravado, BigDecimal exento,
            List<AlicuotaDelDocumento> alicuotas, Transparencia transparencia, String leyendaMonotributo, String qr,
            List<String> qrModulos, String comprobanteAsociado) {

        public DocumentoFiscal {
            alicuotas = List.copyOf(alicuotas);
            qrModulos = qrModulos == null ? null : List.copyOf(qrModulos);
        }

        /** El documento de una factura, sin comprobante asociado (como antes de la rama 8). */
        public DocumentoFiscal(EstadoFiscalVenta estadoFiscal, boolean validoComoFactura, String letra,
                boolean discriminaIva, Integer codigo, String marca, Integer puntoVenta, Long numero, LocalDate fecha,
                String cae, LocalDate caeVencimiento, EmisorDelDocumento emisor, ReceptorDelDocumento receptor,
                BigDecimal total, BigDecimal neto, BigDecimal iva, BigDecimal noGravado, BigDecimal exento,
                List<AlicuotaDelDocumento> alicuotas, Transparencia transparencia, String leyendaMonotributo, String qr,
                List<String> qrModulos) {
            this(estadoFiscal, validoComoFactura, letra, discriminaIva, codigo, marca, puntoVenta, numero, fecha, cae,
                    caeVencimiento, emisor, receptor, total, neto, iva, noGravado, exento, alicuotas, transparencia,
                    leyendaMonotributo, qr, qrModulos, null);
        }
    }

    /**
     * La ferretería como emisora, de la copia del registro.
     *
     * @param cuit los 11 dígitos, sin guiones
     * @param condicionIva «IVA RESPONSABLE INSCRIPTO», «RESPONSABLE MONOTRIBUTO» o «IVA EXENTO»
     */
    record EmisorDelDocumento(String razonSocial, String cuit, String condicionIva, String domicilioComercial,
            String ingresosBrutos, LocalDate inicioActividades) {}

    /**
     * Quien recibe el comprobante, de la copia del registro.
     *
     * @param nombre la razón social o el nombre; null si se vendió sin cliente
     * @param condicionIva «IVA RESPONSABLE INSCRIPTO», «RESPONSABLE MONOTRIBUTO», «IVA EXENTO» o «CONSUMIDOR FINAL»
     * @param tipoDocumento CUIT, CUIL o DNI; null sin identificar
     * @param numeroDocumento solo dígitos; null sin identificar
     * @param aConsumidorFinal true si la condición es Consumidor Final, con documento o sin él
     */
    record ReceptorDelDocumento(String nombre, String condicionIva, String tipoDocumento, String numeroDocumento,
            String domicilio, boolean aConsumidorFinal) {}

    /**
     * Una alícuota del desglose guardado.
     *
     * @param porcentaje 21.00 significa 21 %
     * @param base la base imponible ({@code BaseImp})
     * @param importe el IVA de esa alícuota
     */
    record AlicuotaDelDocumento(BigDecimal porcentaje, BigDecimal base, BigDecimal importe) {}

    /**
     * El Régimen de Transparencia Fiscal al Consumidor (Ley 27.743) de la B a Consumidor Final.
     *
     * @param ivaContenido el IVA guardado del comprobante
     * @param otrosImpuestosNacionalesIndirectos 0.00: la venta no tiene otros
     */
    record Transparencia(BigDecimal ivaContenido, BigDecimal otrosImpuestosNacionalesIndirectos) {}

    /**
     * El resumen fiscal de una venta para el listado (C2).
     *
     * @param letra la letra de la hoja: A, B o C si es válida como factura; X si no
     * @param puntoVenta el punto de venta del registro
     * @param numero el número fiscal del registro, o null mientras no tiene
     * @param estado el estado del registro
     * @param ambiente SIMULADOR, HOMOLOGACION o PRODUCCION
     * @param validoComoFactura la misma regla que {@link DocumentoFiscal#validoComoFactura()}
     * @param reimprimir true si salió con el ticket provisorio y ya está AUTORIZADO (rama 7, C1)
     * @param clase FACTURA o NOTA_CREDITO (rama 8)
     * @param codigo el {@code CbteTipo} con la misma regla que {@link DocumentoFiscal#codigo()}: null si no es válido como
     *     factura
     * @param comprobanteAsociado el de {@link DocumentoFiscal#comprobanteAsociado()}: la factura que anula una nota de
     *     crédito; null en una factura
     */
    record ResumenFiscal(String letra, int puntoVenta, Long numero, String estado, String ambiente,
            boolean validoComoFactura, boolean reimprimir, String clase, Integer codigo, String comprobanteAsociado) {}

    /**
     * La facturación de la ferretería, como la anticipa el cobro (C3).
     *
     * @param ambiente SIMULADOR, HOMOLOGACION o PRODUCCION
     * @param letras la letra que saldría para cada condición frente al IVA del cliente (la venta sin cliente es la de
     *     Consumidor Final), armada con la misma función que el alta del registro
     */
    record FacturacionDelMostrador(String ambiente, Map<CondicionIva, String> letras) {

        public FacturacionDelMostrador {
            letras = Collections.unmodifiableMap(new LinkedHashMap<>(letras));
        }
    }
}
