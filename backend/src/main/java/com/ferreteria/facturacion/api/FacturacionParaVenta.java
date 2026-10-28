package com.ferreteria.facturacion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
}
