package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.RubroRequest;
import com.ferreteria.catalogo.dto.RubroResponse;
import com.ferreteria.catalogo.model.Rubro;
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

    public RubroService(RubroRepository rubroRepository) {
        this.rubroRepository = rubroRepository;
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
            throw new ConflictException("Ya existe un rubro llamado " + nombre);
        }
        Rubro rubro = new Rubro(TenantContext.requireFerreteriaId(), nombre, request.margenPorcentaje());
        return RubroResponse.from(rubroRepository.save(rubro));
    }

    @Transactional
    public RubroResponse actualizar(Long id, RubroRequest request) {
        Rubro rubro = buscar(id);
        String nombre = request.nombre().trim();
        if (rubroRepository.existeOtroConNombreScoped(nombre, id)) {
            throw new ConflictException("Ya existe un rubro llamado " + nombre);
        }
        rubro.actualizar(nombre, request.margenPorcentaje());
        return RubroResponse.from(rubro);
    }

    private Rubro buscar(Long id) {
        return rubroRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
    }
}
