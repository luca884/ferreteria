package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ErrorDeBase;
import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Fachada de cobrar (C-009). Deliberadamente SIN transaccion: cada intento abre la suya en
 * {@link RegistroVenta}, asi un deadlock o un choque de la clave unica revierte todo y se puede reintentar o
 * resolver con una transaccion nueva (con REPEATABLE READ, releer dentro de la que fallo no veria el commit
 * de la otra). Si una venta a cuenta corriente es rechazada por el limite, mira si la clave ya estaba registrada (el doble envio).
 *
 * <p>CU-07 paso 5 (cu-07-venta-facturada S4): recien despues de ese bucle, con la venta confirmada y sin transaccion (la
 * llamada a ARCA no va con una conexion tomada), la venta nueva con registro fiscal pide su CAE con la espera acotada de
 * {@link FacturacionParaVenta#autorizar}; la repetida facturable devuelve su estado fiscal actual sin volver a pedirlo. Nada
 * de esto bloquea la venta, que ya esta cobrada: si falla, queda en el log y la venta responde sin {@code fiscal} (nunca un
 * 503 por ARCA). Quien llame a esta fachada no puede tener una transaccion abierta.
 */
@Service
public class VentaService {

    private static final Logger log = LoggerFactory.getLogger(VentaService.class);

    /** Intentos ante un deadlock o timeout de lock. Seguro por la idempotencia de la clave. */
    static final int MAX_INTENTOS = 3;

    private final RegistroVenta registroVenta;
    private final FacturacionParaVenta facturacion;

    public VentaService(RegistroVenta registroVenta, FacturacionParaVenta facturacion) {
        this.registroVenta = registroVenta;
        this.facturacion = facturacion;
    }

    /** Cobra como un usuario que no puede autorizar excesos de cuenta corriente (un Vendedor). */
    public VentaRegistrada cobrar(ConfirmarVenta pedido, Long usuarioId) {
        return cobrar(pedido, usuarioId, false);
    }

    /** {@code usuarioEsDueno} sale del rol del JWT: solo el Dueño puede autorizar que una venta pase el limite del cliente (CC-005). */
    public VentaRegistrada cobrar(ConfirmarVenta pedido, Long usuarioId, boolean usuarioEsDueno) {
        return conEstadoFiscal(registrar(pedido, usuarioId, usuarioEsDueno));
    }

    private VentaRegistrada registrar(ConfirmarVenta pedido, Long usuarioId, boolean usuarioEsDueno) {
        int intento = 1;
        while (true) {
            try {
                return registroVenta.registrar(pedido, usuarioId, usuarioEsDueno);
            } catch (PessimisticLockingFailureException e) {
                if (intento >= MAX_INTENTOS) {
                    // Contencion transitoria agotada: 503 «reintenta» (la clave hace seguro el reintento), no un 500.
                    throw new SistemaOcupadoException(e);
                }
                intento++;
            } catch (DataIntegrityViolationException e) {
                // Con un codigo de MySQL que no es un duplicado (un valor fuera de rango) no hubo carrera: se relanza.
                if (ErrorDeBase.codigo(e).isPresent() && !ErrorDeBase.es(e, ErrorDeBase.DUPLICADO)) {
                    throw e;
                }
                // Otra transaccion con la misma clave gano la carrera: la venta ya esta confirmada.
                return registroVenta.repetida(pedido).orElseThrow(() -> e);
            } catch (VentaRechazadaException e) {
                // CC-009: el doble envio de una venta a cuenta con el limite justo: la segunda copia espera el lock del cliente, ve
                // la deuda de la primera y se pasaria del limite. Con una transaccion nueva (snapshot fresco) se mira si la clave ya
                // esta registrada: si lo esta, es la venta original (200); si no, es un rechazo de verdad.
                if (e.motivo() != Motivo.LIMITE_CUENTA_CORRIENTE) {
                    throw e;
                }
                return registroVenta.repetida(pedido).orElseThrow(() -> e);
            }
        }
    }

    /**
     * La venta con su estado fiscal: la nueva que dio de alta su registro pide el CAE ({@code autorizar}, con la espera
     * acotada); la repetida facturable lee el estado actual ({@code estado}, sin llamar a ARCA). La venta nueva sin registro
     * y la repetida no facturable no preguntan nada. Cualquier falla queda en el log y la venta sale sin {@code fiscal}.
     */
    private VentaRegistrada conEstadoFiscal(VentaRegistrada venta) {
        boolean pedirCae = !venta.repetida() && venta.conRegistroFiscal();
        boolean leerEstado = venta.repetida() && venta.comprobante().getEstado() != EstadoComprobante.NO_FACTURABLE;
        if (!pedirCae && !leerEstado) {
            return venta;
        }
        long comprobanteId = venta.comprobante().getId();
        try {
            Optional<EstadoFiscalVenta> estado = pedirCae
                    ? facturacion.autorizar(comprobanteId)
                    : facturacion.estado(comprobanteId);
            return estado.map(venta::conFiscal).orElse(venta);
        } catch (RuntimeException e) {
            log.error("No se pudo {} de la venta {}: la venta ya está cobrada y responde sin su estado fiscal.",
                    pedirCae ? "pedir el CAE" : "leer el estado fiscal", comprobanteId, e);
            return venta;
        }
    }
}
