package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las formas de pago de la ferreteria (GDS-11, RC-001 y RC-008). Una ferreteria sin ninguna recibe las cuatro de siempre,
 * sin recargo, la primera vez que las pide o crea una, y una quinta, «Cuenta corriente», INACTIVA hasta que el Dueño la
 * active (CC-024). La forma de medio CUENTA_CORRIENTE siempre guarda recargo 0 (CC-023): el servicio lo fuerza, no lo
 * rechaza. Siempre tiene que quedar una activa en efectivo: es la del cobro rapido del mostrador (F4 y Enter).
 */
@Service
public class FormaPagoService {

    private static final String NOMBRE_REPETIDO = "Ya hay una forma de pago con ese nombre.";

    private record PorDefecto(String nombre, MedioPago medio, int orden, boolean activa) {}

    private static final List<PorDefecto> DE_SIEMPRE = List.of(
            new PorDefecto("Efectivo", MedioPago.EFECTIVO, 1, true),
            new PorDefecto("Débito", MedioPago.DEBITO, 2, true),
            new PorDefecto("Crédito", MedioPago.CREDITO, 3, true),
            new PorDefecto("Transferencia", MedioPago.TRANSFERENCIA, 4, true),
            new PorDefecto("Cuenta corriente", MedioPago.CUENTA_CORRIENTE, 5, false));

    private final FormaPagoRepository repositorio;

    public FormaPagoService(FormaPagoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Escribe (siembra) si la ferreteria no tiene ninguna, por eso la transaccion no es de solo lectura. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<FormaPago> listar(boolean soloActivas) {
        sembrarSiNoHayNinguna();
        return soloActivas ? repositorio.listarActivasScoped() : repositorio.listarScoped();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FormaPago crear(DatosFormaPago datos) {
        sembrarSiNoHayNinguna();
        String nombre = datos.nombre().trim();
        if (repositorio.existeNombreScoped(nombre)) {
            throw new ConflictException(NOMBRE_REPETIDO);
        }
        int orden = datos.orden() != null ? datos.orden() : repositorio.maxOrdenScoped() + 1;
        BigDecimal recargo = alCrear(datos, datos.recargoPorcentaje());
        BigDecimal descuento = alCrear(datos, datos.descuentoPorcentaje());
        validarRecargoODescuento(recargo, descuento);
        return repositorio.save(FormaPago.de(TenantContext.requireFerreteriaId(), nombre, datos.medio(),
                recargo, descuento, datos.activa() == null || datos.activa(), orden));
    }

    /** Una forma de otra ferreteria da 404, igual que una que no existe. */
    @Transactional
    public FormaPago actualizar(Long id, DatosFormaPago datos) {
        FormaPago forma = repositorio.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Forma de pago no encontrada"));
        String nombre = datos.nombre().trim();
        if (repositorio.existeOtroConNombreScoped(nombre, id)) {
            throw new ConflictException(NOMBRE_REPETIDO);
        }
        boolean activa = datos.activa() != null ? datos.activa() : forma.isActiva();
        boolean dejaDeSerEfectivoActivo = forma.isActiva() && forma.getMedio() == MedioPago.EFECTIVO
                && (!activa || datos.medio() != MedioPago.EFECTIVO);
        if (dejaDeSerEfectivoActivo && !repositorio.existeOtraActivaDeMedioScoped(MedioPago.EFECTIVO, id)) {
            throw new ReglaDeNegocioException("Tiene que quedar al menos una forma de pago en efectivo.");
        }
        BigDecimal recargo = alActualizar(datos, datos.recargoPorcentaje(), forma.getRecargoPorcentaje());
        BigDecimal descuento = alActualizar(datos, datos.descuentoPorcentaje(), forma.getDescuentoPorcentaje());
        validarRecargoODescuento(recargo, descuento);
        forma.actualizar(nombre, datos.medio(), recargo, descuento, activa,
                datos.orden() != null ? datos.orden() : forma.getOrden());
        return forma;
    }

    /** Recargo o descuento: sin valor es 0, y la cuenta corriente nunca lleva ninguno de los dos (CC-023). */
    private static BigDecimal alCrear(DatosFormaPago datos, BigDecimal porcentaje) {
        if (datos.medio() == MedioPago.CUENTA_CORRIENTE || porcentaje == null) {
            return BigDecimal.ZERO;
        }
        return porcentaje;
    }

    /** Recargo o descuento: sin valor conserva el que tenia, salvo la cuenta corriente, que queda en 0 (CC-023). */
    private static BigDecimal alActualizar(DatosFormaPago datos, BigDecimal porcentaje, BigDecimal actual) {
        if (datos.medio() == MedioPago.CUENTA_CORRIENTE) {
            return BigDecimal.ZERO;
        }
        return porcentaje != null ? porcentaje : actual;
    }

    private static void validarRecargoODescuento(BigDecimal recargo, BigDecimal descuento) {
        if (recargo.signum() > 0 && descuento.signum() > 0) {
            throw new ReglaDeNegocioException("Una forma de pago lleva recargo o descuento, no los dos.");
        }
    }

    /**
     * Con la fila de la ferreteria bloqueada y recontando despues: dos primeros pedidos a la vez no se traban entre si, el
     * segundo espera, ve las cuatro y no siembra. Corre en READ COMMITTED (listar y crear): con REPEATABLE READ el segundo
     * seguiria viendo la foto de antes de esperar y no veria lo que sembro el primero.
     */
    private void sembrarSiNoHayNinguna() {
        if (repositorio.contarScoped() > 0) {
            return;
        }
        repositorio.bloquearFerreteriaScoped();
        if (repositorio.contarScoped() == 0) {
            DE_SIEMPRE.forEach(f -> repositorio.sembrarScoped(f.nombre(), f.medio(), f.orden(), f.activa()));
        }
    }
}
