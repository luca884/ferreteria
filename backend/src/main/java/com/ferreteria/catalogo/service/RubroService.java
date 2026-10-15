package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.RubroRequest;
import com.ferreteria.catalogo.dto.RubroResponse;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RubroService {

    private final RubroRepository rubroRepository;

    private final RecalculoPreciosService recalculoPreciosService;

    private final ArticuloRepository articuloRepository;

    public RubroService(
            RubroRepository rubroRepository,
            RecalculoPreciosService recalculoPreciosService,
            ArticuloRepository articuloRepository) {
        this.rubroRepository = rubroRepository;
        this.recalculoPreciosService = recalculoPreciosService;
        this.articuloRepository = articuloRepository;
    }

    @Transactional(readOnly = true)
    public List<RubroResponse> listar() {
        return rubroRepository.listarScoped().stream().map(RubroResponse::from).toList();
    }

    /** Un rubro de otra ferreteria da 404, igual que uno que no existe: no se revela que existe. */
    @Transactional(readOnly = true)
    public RubroResponse obtener(Long id) {
        return RubroResponse.from(buscar(id));
    }

    @Transactional
    public RubroResponse crear(RubroRequest request) {
        String nombre = request.nombre().trim();
        if (rubroRepository.existeNombreScoped(nombre)) {
            throw new ConflictException("Ya existe un rubro llamado " + nombre + ".");
        }
        Rubro rubro = new Rubro(TenantContext.requireFerreteriaId(), nombre, request.margenPorcentaje());
        return RubroResponse.from(rubroRepository.save(rubro));
    }

    /**
     * Si cambia el margen, recalcula en la misma transaccion los articulos que lo heredan (con
     * historial MASIVO a nombre de usuarioId); si falla, se revierte tambien el cambio del rubro.
     */
    @Transactional
    public RubroResponse actualizar(Long id, RubroRequest request, Long usuarioId) {
        Rubro rubro = buscar(id);
        String nombre = request.nombre().trim();
        if (rubroRepository.existeOtroConNombreScoped(nombre, id)) {
            throw new ConflictException("Ya existe un rubro llamado " + nombre + ".");
        }
        // compareTo y no equals: 35 y 35.00 son el mismo margen. El margen del rubro nunca es null.
        boolean cambioElMargen = rubro.getMargenPorcentaje().compareTo(request.margenPorcentaje()) != 0;
        rubro.actualizar(nombre, request.margenPorcentaje());
        if (cambioElMargen) {
            recalculoPreciosService.porCambioDeMargenDeRubro(rubro, usuarioId);
        }
        return RubroResponse.from(rubro);
    }

    /**
     * Cuantos articulos activos del rubro no tienen margen propio: los que se recalculan si cambia su margen.
     * Un rubro ajeno da 404.
     */
    @Transactional(readOnly = true)
    public long contarArticulosQueHeredan(Long id) {
        buscar(id);
        return articuloRepository.contarHeredanMargenDeRubroScoped(id);
    }

    private Rubro buscar(Long id) {
        return rubroRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
    }
}
