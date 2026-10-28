package com.ferreteria.ventas.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * La venta no se hizo por algo del ticket que el cajero puede corregir (R-008, R-009, R-010, R-011), porque no hay
 * caja abierta del dia (CU-10), por la cuenta corriente del cliente (CC-005, CC-006) o por lo que pide la factura (GDS-32,
 * D4). Lleva el
 * motivo, su estado HTTP, el mensaje para el usuario y los articulos involucrados. Ningun rechazo guarda
 * nada ni consume la clave de idempotencia.
 */
public class VentaRechazadaException extends RuntimeException {

    public enum Motivo {
        /** Inexistente o de otra ferreteria: indistinguibles a proposito. */
        ARTICULO_INEXISTENTE(404),
        ARTICULO_DADO_DE_BAJA(422),
        /** Precio final $0: no se vende gratis por un costo sin cargar. */
        ARTICULO_SIN_PRECIO(422),
        PRECIOS_CAMBIADOS(409),
        /** La misma clave ya registro otra venta, con otros datos. */
        CLAVE_CON_OTRO_CONTENIDO(422),
        /** CU-10: no hay caja abierta en la ferreteria. */
        CAJA_CERRADA(422),
        /** CU-10: la caja abierta es de un dia anterior; hay que cerrarla y abrir otra. */
        CAJA_DE_OTRO_DIA(422),
        /** CC-006: el cliente no tiene cuenta corriente habilitada (limite NULL). Ni el Dueño puede autorizarlo. */
        CUENTA_CORRIENTE_NO_HABILITADA(422),
        /** CC-005: la venta a cuenta corriente pasa el limite del cliente; la puede autorizar el Dueño. */
        LIMITE_CUENTA_CORRIENTE(422),
        /** GDS-32: desde el tope, la venta facturable a Consumidor Final necesita un cliente con documento. */
        CONSUMIDOR_FINAL_SIN_DATOS(422),
        /**
         * CU-07 paso 2 (D4): con la facturación habilitada, la venta facturable a un cliente Responsable Inscripto,
         * Monotributista o Exento necesita su CUIT y su domicilio.
         */
        CLIENTE_SIN_DATOS_FISCALES(422);

        private final int estadoHttp;

        Motivo(int estadoHttp) {
            this.estadoHttp = estadoHttp;
        }

        public int estadoHttp() {
            return estadoHttp;
        }
    }

    /**
     * Un articulo del rechazo. Segun el motivo vienen unos campos u otros y el resto es null: inexistente
     * solo trae el id que mando el cliente; baja, id y descripcion; precios, todos.
     */
    public record Articulo(Long articuloId, String descripcion, BigDecimal precioMostrado, BigDecimal precioActual,
            BigDecimal totalRenglon) {

        public static Articulo soloId(Long articuloId) {
            return new Articulo(articuloId, null, null, null, null);
        }

        public static Articulo conDescripcion(Long articuloId, String descripcion) {
            return new Articulo(articuloId, descripcion, null, null, null);
        }
    }

    /**
     * El detalle de un rechazo por limite de cuenta corriente (CC-013). {@code puedeAutorizar} es si quien vende es el Dueño; las
     * cifras solo las ve el Dueño: para el Vendedor vienen en null (lo decide RegistroVenta, que conoce el rol, no el response).
     */
    public record ExcesoLimite(boolean puedeAutorizar, BigDecimal saldo, BigDecimal limite, BigDecimal disponible,
            BigDecimal importe, BigDecimal exceso) {

        /** Lo que recibe el Vendedor: que no puede autorizar y ninguna cifra. */
        public static ExcesoLimite sinCifras() {
            return new ExcesoLimite(false, null, null, null, null, null);
        }
    }

    static final String MENSAJE_LIMITE =
            "Esta venta supera el límite de cuenta corriente del cliente.";

    /** CC-006: «<razón social> no tiene cuenta corriente habilitada.», sin cifras y sin objeto de cuenta. */
    public static VentaRechazadaException cuentaNoHabilitada(String razonSocial) {
        return new VentaRechazadaException(Motivo.CUENTA_CORRIENTE_NO_HABILITADA,
                razonSocial + " no tiene cuenta corriente habilitada.", List.of());
    }

    /** CC-005, CC-013: el mensaje depende de si quien vende puede autorizar el exceso; sin cifras en ninguno. */
    public static VentaRechazadaException limiteCuentaCorriente(ExcesoLimite exceso) {
        String mensaje = MENSAJE_LIMITE + (exceso.puedeAutorizar()
                ? " Autorizala para seguir."
                : " Pedile al Dueño que la autorice.");
        return new VentaRechazadaException(Motivo.LIMITE_CUENTA_CORRIENTE, mensaje, List.of(), null, exceso);
    }

    /** GDS-32: «Desde $ <tope> la venta a Consumidor Final necesita identificar al comprador: …», sin articulos. */
    public static VentaRechazadaException consumidorFinalSinDatos(String tope) {
        return new VentaRechazadaException(Motivo.CONSUMIDOR_FINAL_SIN_DATOS, "Desde $ " + tope
                + " la venta a Consumidor Final necesita identificar al comprador: elegí un cliente con DNI, CUIT o CUIL.",
                List.of());
    }

    /** D4: «Para facturarle a <razón social> falta su CUIT o su domicilio: completalos en Clientes.», sin articulos. */
    public static VentaRechazadaException clienteSinDatosFiscales(String razonSocial) {
        return new VentaRechazadaException(Motivo.CLIENTE_SIN_DATOS_FISCALES, "Para facturarle a " + razonSocial
                + " falta su CUIT o su domicilio: completalos en Clientes.", List.of());
    }

    private final Motivo motivo;
    private final List<Articulo> articulos;
    private final BigDecimal total;
    private final ExcesoLimite excesoLimite;

    private VentaRechazadaException(Motivo motivo, String mensaje, List<Articulo> articulos, BigDecimal total,
            ExcesoLimite excesoLimite) {
        super(mensaje);
        this.motivo = motivo;
        this.articulos = List.copyOf(articulos);
        this.total = total;
        this.excesoLimite = excesoLimite;
    }

    public VentaRechazadaException(Motivo motivo, String mensaje, List<Articulo> articulos, BigDecimal total) {
        this(motivo, mensaje, articulos, total, null);
    }

    public VentaRechazadaException(Motivo motivo, String mensaje, List<Articulo> articulos) {
        this(motivo, mensaje, articulos, null);
    }

    public Motivo motivo() {
        return motivo;
    }

    public List<Articulo> articulos() {
        return articulos;
    }

    /** El total actual recalculado; solo en PRECIOS_CAMBIADOS, null en los demas. */
    public BigDecimal total() {
        return total;
    }

    /** El detalle del rechazo por limite; solo en LIMITE_CUENTA_CORRIENTE, null en los demas. */
    public ExcesoLimite excesoLimite() {
        return excesoLimite;
    }
}
