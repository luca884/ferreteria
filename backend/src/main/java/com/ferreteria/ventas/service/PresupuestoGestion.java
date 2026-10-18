package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.repository.ItemPresupuestoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Listar, ver y cambiar el estado de los presupuestos de la ferreteria del request (PP-002, PP-005). Uno de otra
 * ferreteria es indistinguible de uno que no existe: 404.
 */
@Service
public class PresupuestoGestion {

    static final int TAMANO_MAXIMO = 100;
    static final String MENSAJE_NO_ENCONTRADO = "Presupuesto no encontrado";
    static final String MENSAJE_ESTADO = "Estado no válido.";
    static final String MENSAJE_FACTURADO = "Ese presupuesto ya se facturó.";

    /** Los estados que se eligen a mano: FACTURADO solo lo pone la venta hecha desde el presupuesto. */
    private static final Set<String> ESTADOS_MANUALES = Set.of(
            EstadoPresupuesto.PENDIENTE.name(), EstadoPresupuesto.ACEPTADO.name(), EstadoPresupuesto.RECHAZADO.name());

    private final PresupuestoRepository presupuestoRepository;
    private final ItemPresupuestoRepository itemRepository;

    public PresupuestoGestion(PresupuestoRepository presupuestoRepository, ItemPresupuestoRepository itemRepository) {
        this.presupuestoRepository = presupuestoRepository;
        this.itemRepository = itemRepository;
    }

    /** Los mas nuevos primero. {@code desde} y {@code hasta} son dias, ambos inclusive; un filtro nulo no filtra. */
    @Transactional(readOnly = true)
    public Page<Presupuesto> listar(
            EstadoPresupuesto estado, Long numero, LocalDate desde, LocalDate hasta, int page, int size) {
        return presupuestoRepository.buscarScoped(estado, numero,
                desde == null ? null : desde.atStartOfDay(),
                hasta == null ? null : hasta.plusDays(1).atStartOfDay(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
    }

    @Transactional(readOnly = true)
    public DetallePresupuesto detalle(Long id) {
        Presupuesto presupuesto = presupuestoRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADO));
        return new DetallePresupuesto(presupuesto, itemRepository.dePresupuestoScoped(id));
    }

    /**
     * Pasa el presupuesto a PENDIENTE, ACEPTADO o RECHAZADO, en cualquier orden entre ellos. Con lock exclusivo, para
     * ordenarse con la venta que lo factura. Cualquier otro valor es 400 y uno ya FACTURADO, 409.
     */
    @Transactional
    public DetallePresupuesto cambiarEstado(Long id, String estado) {
        Presupuesto presupuesto = presupuestoRepository.paraCambiarScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADO));
        if (estado == null || !ESTADOS_MANUALES.contains(estado)) {
            throw new DatosInvalidosException(Map.of("estado", MENSAJE_ESTADO));
        }
        if (presupuesto.getEstado() == EstadoPresupuesto.FACTURADO) {
            throw new ConflictException(MENSAJE_FACTURADO);
        }
        presupuesto.cambiarEstado(EstadoPresupuesto.valueOf(estado));
        return new DetallePresupuesto(presupuesto, itemRepository.dePresupuestoScoped(id));
    }
}
