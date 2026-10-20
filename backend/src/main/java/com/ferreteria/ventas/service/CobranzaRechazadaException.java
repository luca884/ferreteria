package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * La cobranza no se hizo por algo que el front distingue (CC-029, CC-030, CC-035, CC-038): no hay caja abierta del día, la
 * caja es de otro día, la clave ya registró otro cobro, o la deuda de algún comprobante cambió desde que se vio (409). Lleva
 * el motivo, su estado HTTP, el mensaje para el usuario y, en el 409, la deuda ACTUAL de los comprobantes afectados. Ningún
 * rechazo guarda nada ni consume número de recibo.
 */
public class CobranzaRechazadaException extends RuntimeException {

    public enum Motivo {
        /** CU-10: no hay caja abierta en la ferretería. */
        CAJA_CERRADA(422),
        /** CU-10: la caja abierta es de un día anterior; hay que cerrarla y abrir otra. */
        CAJA_DE_OTRO_DIA(422),
        /** La misma clave ya registró otro cobro, con otros datos. */
        CLAVE_CON_OTRO_CONTENIDO(422),
        /** «La deuda cambió desde que la viste»: se imputó más de lo que le queda a algún comprobante. */
        IMPUTACION_EXCEDE_DEUDA(409),
        /** CC-060: el recibo que se quiere anular ya está anulado (hecho consumado: el front refresca). */
        RECIBO_YA_ANULADO(409);

        private final int estadoHttp;

        Motivo(int estadoHttp) {
            this.estadoHttp = estadoHttp;
        }

        public int estadoHttp() {
            return estadoHttp;
        }
    }

    private static final DateTimeFormatter CUANDO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Motivo motivo;
    private final List<DeudaActual> deudas;
    private final String anuladoPor;
    private final LocalDateTime fechaAnulacion;

    public CobranzaRechazadaException(Motivo motivo, String mensaje, List<DeudaActual> deudas) {
        this(motivo, mensaje, deudas, null, null);
    }

    private CobranzaRechazadaException(Motivo motivo, String mensaje, List<DeudaActual> deudas, String anuladoPor,
            LocalDateTime fechaAnulacion) {
        super(mensaje);
        this.motivo = motivo;
        this.deudas = List.copyOf(deudas);
        this.anuladoPor = anuladoPor;
        this.fechaAnulacion = fechaAnulacion;
    }

    /** CC-060: el recibo ya está anulado; el mensaje dice quién y cuándo, y los dos datos viajan también como campos. */
    public static CobranzaRechazadaException reciboYaAnulado(String anuladoPor, LocalDateTime fechaAnulacion) {
        return new CobranzaRechazadaException(Motivo.RECIBO_YA_ANULADO,
                "Este recibo ya fue anulado por " + anuladoPor + " el " + fechaAnulacion.format(CUANDO) + ".", List.of(),
                anuladoPor, fechaAnulacion);
    }

    public CobranzaRechazadaException(Motivo motivo, String mensaje) {
        this(motivo, mensaje, List.of());
    }

    public Motivo motivo() {
        return motivo;
    }

    /** La deuda actual de los comprobantes afectados; vacía salvo en IMPUTACION_EXCEDE_DEUDA. */
    public List<DeudaActual> deudas() {
        return deudas;
    }

    /** Quién anuló el recibo; solo en RECIBO_YA_ANULADO. */
    public String anuladoPor() {
        return anuladoPor;
    }

    /** Cuándo se anuló el recibo (hora de Buenos Aires); solo en RECIBO_YA_ANULADO. */
    public LocalDateTime fechaAnulacion() {
        return fechaAnulacion;
    }
}
