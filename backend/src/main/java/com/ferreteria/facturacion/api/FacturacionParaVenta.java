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
 */
public interface FacturacionParaVenta {

    /**
     * Lee la configuración de ARCA de la ferretería con lock compartido hasta el fin de la transacción (mientras tanto el
     * panel no la cambia) y decide si la venta sale con factura. No escribe nada. Exige una transacción abierta.
     */
    Decision decidir(ReceptorVenta receptor);

    /**
     * Da de alta el registro fiscal PENDIENTE de la venta {@code comprobanteId}, con lo que va a ARCA congelado y la fecha
     * de la venta. Exige la transacción de la venta, con una {@link Decision#factura()} tomada en ella. Devuelve el id del
     * registro.
     */
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
     * @param estado PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO, FUERA_DE_FECHA, CAE_NO_COINCIDE o
     *     SERIE_DESINCRONIZADA
     * @param clase FACTURA
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
     * @param codigo el {@code CbteTipo} (1, 6 u 11); null en el X
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
     */
    record DocumentoFiscal(EstadoFiscalVenta estadoFiscal, boolean validoComoFactura, String letra,
            boolean discriminaIva, Integer codigo, String marca, Integer puntoVenta, Long numero, LocalDate fecha,
            String cae, LocalDate caeVencimiento, EmisorDelDocumento emisor, ReceptorDelDocumento receptor,
            BigDecimal total, BigDecimal neto, BigDecimal iva, BigDecimal noGravado, BigDecimal exento,
            List<AlicuotaDelDocumento> alicuotas, Transparencia transparencia, String leyendaMonotributo, String qr,
            List<String> qrModulos) {

        public DocumentoFiscal {
            alicuotas = List.copyOf(alicuotas);
            qrModulos = qrModulos == null ? null : List.copyOf(qrModulos);
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
     */
    record ResumenFiscal(String letra, int puntoVenta, Long numero, String estado, String ambiente,
            boolean validoComoFactura) {}

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
