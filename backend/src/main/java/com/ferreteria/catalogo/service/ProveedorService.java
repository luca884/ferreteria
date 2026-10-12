package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ProveedorRequest;
import com.ferreteria.catalogo.dto.ProveedorResponse;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProveedorService {

    private final ProveedorRepository proveedorRepository;

    public ProveedorService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    /** Solo los activos: la UI ofrece unicamente esos al asignar un proveedor a un articulo. */
    @Transactional(readOnly = true)
    public List<ProveedorResponse> listar() {
        return proveedorRepository.listarScoped().stream().map(ProveedorResponse::from).toList();
    }

    /** Un proveedor de otra ferreteria da 404, igual que uno que no existe: no se revela que existe. */
    @Transactional(readOnly = true)
    public ProveedorResponse obtener(Long id) {
        return ProveedorResponse.from(buscar(id));
    }

    @Transactional
    public ProveedorResponse crear(ProveedorRequest request) {
        String nombre = request.nombre().trim();
        if (proveedorRepository.existeNombreScoped(nombre)) {
            throw new ConflictException(mensajeDuplicado(nombre));
        }
        Proveedor proveedor = new Proveedor(TenantContext.requireFerreteriaId(), nombre, request.cuit());
        if (Boolean.FALSE.equals(request.activo())) {
            proveedor.desactivar();
        }
        return ProveedorResponse.from(proveedorRepository.save(proveedor));
    }

    @Transactional
    public ProveedorResponse actualizar(Long id, ProveedorRequest request) {
        Proveedor proveedor = buscar(id);
        String nombre = request.nombre().trim();
        if (proveedorRepository.existeOtroConNombreScoped(nombre, id)) {
            throw new ConflictException(mensajeDuplicado(nombre));
        }
        // Sin activo en el pedido se conserva el estado actual: un PUT no reactiva por omision.
        boolean activo = request.activo() == null ? proveedor.isActivo() : request.activo();
        proveedor.actualizar(nombre, request.cuit(), activo);
        return ProveedorResponse.from(proveedor);
    }

    /** Baja logica: la fila y las referencias de los articulos se conservan. */
    @Transactional
    public void desactivar(Long id) {
        buscar(id).desactivar();
    }

    private Proveedor buscar(Long id) {
        return proveedorRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));
    }

    private String mensajeDuplicado(String nombre) {
        return "Ya existe un proveedor llamado " + nombre + ".";
    }
}
