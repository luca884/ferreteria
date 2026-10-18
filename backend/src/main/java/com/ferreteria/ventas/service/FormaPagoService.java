package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.FormaPagoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las formas de pago de la ferreteria (GDS-11, RC-001 y RC-008). Una ferreteria sin ninguna recibe las cuatro de siempre,
 * sin recargo, la primera vez que las pide o crea una. Siempre tiene que quedar una activa en efectivo: es la del cobro
 * rapido del mostrador (F4 y Enter).
 */
@Service
public class FormaPagoService {

    private static final String NOMBRE_REPETIDO = "Ya hay una forma de pago con ese nombre.";

    private record PorDefecto(String nombre, MedioPago medio, int orden) {}

    private static final List<PorDefecto> DE_SIEMPRE = List.of(
            new PorDefecto("Efectivo", MedioPago.EFECTIVO, 1),
            new PorDefecto("Débito", MedioPago.DEBITO, 2),
            new PorDefecto("Crédito", MedioPago.CREDITO, 3),
            new PorDefecto("Transferencia", MedioPago.TRANSFERENCIA, 4));

    private final FormaPagoRepository repositorio;

    public FormaPagoService(FormaPagoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Escribe (siembra) si la ferreteria no tiene ninguna, por eso la transaccion no es de solo lectura. */
    @Transactional
    public List<FormaPago> listar(boolean soloActivas) {
        sembrarSiNoHayNinguna();
        return soloActivas ? repositorio.listarActivasScoped() : repositorio.listarScoped();
    }

    @Transactional
    public FormaPago crear(DatosFormaPago datos) {
        sembrarSiNoHayNinguna();
        String nombre = datos.nombre().trim();
        if (repositorio.existeNombreScoped(nombre)) {
            throw new ConflictException(NOMBRE_REPETIDO);
        }
        int orden = datos.orden() != null ? datos.orden() : repositorio.maxOrdenScoped() + 1;
        return repositorio.save(FormaPago.de(TenantContext.requireFerreteriaId(), nombre, datos.medio(),
                datos.recargoPorcentaje() != null ? datos.recargoPorcentaje() : BigDecimal.ZERO,
                datos.activa() == null || datos.activa(), orden));
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
        forma.actualizar(nombre, datos.medio(),
                datos.recargoPorcentaje() != null ? datos.recargoPorcentaje() : forma.getRecargoPorcentaje(), activa,
                datos.orden() != null ? datos.orden() : forma.getOrden());
        return forma;
    }

    private void sembrarSiNoHayNinguna() {
        if (repositorio.contarScoped() == 0) {
            DE_SIEMPRE.forEach(f -> repositorio.sembrarScoped(f.nombre(), f.medio(), f.orden()));
        }
    }
}
