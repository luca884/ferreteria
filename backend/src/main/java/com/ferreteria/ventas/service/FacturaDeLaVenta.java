package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.facturacion.api.FacturaImpedidaException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Decision;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.service.VentaGestion.FiscalConsultado;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

/**
 * Las dos acciones del Dueño sobre la factura de una venta (cu-07-contingencias C2 y C3; CU-16 punto 7, D10): «Reintentar»
 * y «Dejar sin factura». Lo fiscal lo hace {@code facturacion} por su puerto; aca va lo que es de la venta: que exista, el
 * cliente de hoy y los controles de D4 del cobro.
 *
 * <p>«Reintentar» abre su transaccion, con el orden de locks de la venta (cliente X → config_arca S → registro fiscal):
 * la venta (404), que tenga una factura rechazada (409), el cliente de hoy tomado con lock, la regla de facturar del cobro
 * ({@link FacturacionParaVenta#decidir}) con sus dos controles de D4 (el mismo 422 que el cobro) y, al final, el registro
 * nuevo. Recien despues del commit, sin transaccion, pide el CAE con la misma espera corta que el cobro
 * ({@link FacturacionParaVenta#autorizar}): lo que no llega queda pendiente para el job. Un deadlock revierte todo y se
 * reintenta, como en el cobro.
 */
@Service
public class FacturaDeLaVenta {

    private static final Logger log = LoggerFactory.getLogger(FacturaDeLaVenta.class);

    /** El prefijo del nombre de la alicuota que guarda el renglon (IVA_21, IVA_10_5...): el de {@code catalogo.api}. */
    private static final String PREFIJO_ALICUOTA = "IVA_";

    private final VentaGestion gestion;
    private final ItemComprobanteRepository itemRepository;
    private final ClienteDelDocumento clienteDelDocumento;
    private final ConsumidorFinal consumidorFinal;
    private final FacturacionParaVenta facturacion;
    private final TransactionTemplate transaccion;
    private final Clock reloj;

    public FacturaDeLaVenta(VentaGestion gestion, ItemComprobanteRepository itemRepository,
            ClienteDelDocumento clienteDelDocumento, ConsumidorFinal consumidorFinal, FacturacionParaVenta facturacion,
            PlatformTransactionManager transacciones, Clock reloj) {
        this.gestion = gestion;
        this.itemRepository = itemRepository;
        this.clienteDelDocumento = clienteDelDocumento;
        this.consumidorFinal = consumidorFinal;
        this.facturacion = facturacion;
        this.transaccion = new TransactionTemplate(transacciones);
        this.reloj = reloj;
    }

    /**
     * «Reintentar» (C2): la factura nueva con la copia de hoy, y su estado despues de la espera corta del CAE. Quien llama
     * no puede tener una transaccion abierta (la llamada a ARCA no va con una conexion tomada).
     *
     * @param fechaDeHoy el Dueño confirmo que la factura salga con la fecha de hoy
     * @throws VentaRechazadaException SIN_FACTURA_RECHAZADA, FACTURACION_FRENADA o FECHA_NUEVA_REQUERIDA (409), o
     *     CLIENTE_SIN_DATOS_FISCALES o CONSUMIDOR_FINAL_SIN_DATOS (422); no cambia nada
     */
    public FiscalConsultado reintentar(long ventaId, boolean fechaDeHoy) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reintentar pide el CAE después del commit: no puede ir con una transacción "
                    + "abierta.");
        }
        int intento = 1;
        while (true) {
            try {
                transaccion.executeWithoutResult(s -> darDeAltaLaFacturaNueva(ventaId, fechaDeHoy));
                break;
            } catch (PessimisticLockingFailureException e) {
                if (intento >= VentaService.MAX_INTENTOS) {
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            }
        }
        try {
            facturacion.autorizar(ventaId);
        } catch (RuntimeException e) {
            log.error("No se pudo pedir el CAE del reintento de la venta {}: queda pendiente.", ventaId, e);
        }
        return gestion.fiscalDe(ventaId).orElseThrow();
    }

    /**
     * «Dejar sin factura» (C3): definitivo, con el motivo, quien y cuando; devuelve la factura como la ve el detalle. Solo
     * sobre una venta: una nota de credito rechazada se corrige con «Reintentar» (CU-08 punto 2), y sobre ella esto es 404
     * «Venta no encontrada».
     *
     * @throws VentaRechazadaException SIN_FACTURA_RECHAZADA (409) si no hay una rechazada; con el motivo vacio o largo,
     *     400 en {@code motivo}
     */
    public FiscalConsultado dejarSinFactura(long ventaId, String motivo, long usuarioId) {
        gestion.venta(ventaId);
        try {
            facturacion.dejarSinFactura(ventaId, motivo, usuarioId);
        } catch (FacturaImpedidaException e) {
            throw rechazo(e);
        }
        return gestion.fiscalDe(ventaId).orElseThrow();
    }

    /**
     * En la transaccion del reintento, en el orden de locks de la venta. Sobre una nota de credito (CU-08 punto 2) no hay
     * cliente de hoy ni controles de D4: {@code facturacion} vuelve a copiar todo de la factura asociada e ignora el
     * receptor y los renglones que se le pasan (van los de la nota, que son los de la venta).
     */
    private void darDeAltaLaFacturaNueva(long ventaId, boolean fechaDeHoy) {
        Comprobante venta = gestion.ventaONota(ventaId);
        try {
            // Sin una rechazada no hay nada que reintentar: se responde antes de mirar el cliente de hoy.
            facturacion.exigirFacturaRechazada(ventaId);
            if (venta.getTipo() == TipoComprobante.NOTA_CREDITO) {
                facturacion.reintentar(ventaId, ReceptorVenta.sinCliente(), renglones(ventaId), fechaDeHoy);
                return;
            }
            ClienteDelDocumento.Elegido cliente = clienteDelDocumento.resolverParaReintento(venta.getClienteId());
            Decision decision = facturacion.decidir(cliente.receptor(), reloj.instant());
            // Con la facturacion frenada no hay controles de D4: el reintento responde FACTURACION_FRENADA.
            if (decision.factura()) {
                if (!decision.receptorCompleto()) {
                    throw VentaRechazadaException.clienteSinDatosFiscales(cliente.nombre());
                }
                consumidorFinal.verificar(true, cliente.identificado(), venta.getTotal(), decision.topeIdentificacion());
            }
            facturacion.reintentar(ventaId, cliente.receptor(), renglones(ventaId), fechaDeHoy);
        } catch (FacturaImpedidaException e) {
            throw rechazo(e);
        }
    }

    /** Los renglones como los pide la factura: su alicuota y su total cobrado, como al vender. */
    private List<RenglonParaFacturar> renglones(long ventaId) {
        return itemRepository.deComprobanteScoped(ventaId).stream()
                .map(i -> new RenglonParaFacturar(porcentaje(i), i.getTotal()))
                .toList();
    }

    /**
     * El porcentaje de la alicuota guardada por su nombre (IVA_21 es 21, IVA_10_5 es 10.5): ventas no conoce
     * {@code catalogo.model}, y el nombre es el que copio el renglon al vender.
     */
    static BigDecimal porcentaje(ItemComprobante item) {
        String nombre = item.getAlicuotaIva();
        if (nombre == null || !nombre.startsWith(PREFIJO_ALICUOTA)) {
            throw new IllegalStateException("El renglón " + item.getRenglon() + " tiene una alícuota que no se entiende: "
                    + nombre + ".");
        }
        return new BigDecimal(nombre.substring(PREFIJO_ALICUOTA.length()).replace('_', '.'));
    }

    /** El impedimento fiscal como el rechazo de la venta (409), con su texto tal cual. Lo usa también la anulación. */
    static VentaRechazadaException rechazo(FacturaImpedidaException e) {
        Motivo motivo = switch (e.impedimento()) {
            case SIN_FACTURA_RECHAZADA -> Motivo.SIN_FACTURA_RECHAZADA;
            case FECHA_NUEVA_REQUERIDA -> Motivo.FECHA_NUEVA_REQUERIDA;
            case FACTURACION_FRENADA -> Motivo.FACTURACION_FRENADA;
            case ESPERAR_CAE -> Motivo.ESPERAR_CAE;
            case OTRO_AMBIENTE -> Motivo.OTRO_AMBIENTE;
        };
        return VentaRechazadaException.facturaImpedida(motivo, e.getMessage());
    }
}
