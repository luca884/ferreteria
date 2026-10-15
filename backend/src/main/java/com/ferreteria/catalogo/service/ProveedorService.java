package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ProveedorRequest;
import com.ferreteria.catalogo.dto.ProveedorResponse;
import com.ferreteria.catalogo.model.Cuit;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ProveedorService {

    private final ProveedorRepository proveedorRepository;

    public ProveedorService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    /**
     * Por defecto solo los activos (la UI ofrece unicamente esos al asignar un proveedor a un articulo); con
     * incluirInactivos tambien los dados de baja, para poder reactivarlos.
     */
    @Transactional(readOnly = true)
    public List<ProveedorResponse> listar(boolean incluirInactivos) {
        return proveedorRepository.listarScoped(incluirInactivos).stream().map(ProveedorResponse::from).toList();
    }


    /** Un proveedor de otra ferreteria da 404, igual que uno que no existe: no se revela que existe. */
    @Transactional(readOnly = true)
    public ProveedorResponse obtener(Long id) {
        return ProveedorResponse.from(buscar(id));
    }

    /**
     * El nombre es unico contando los dados de baja: si el repetido esta de baja, el 409 lo dice para que el
     * Dueno lo reactive en vez de quedar sin salida.
     */
    @Transactional
    public ProveedorResponse crear(ProveedorRequest request) {
        String cuit = cuitValido(request.cuit());
        String nombre = request.nombre().trim();
        proveedorRepository.buscarPorNombreScoped(nombre).ifPresent(existente -> {
            throw new ConflictException(existente.isActivo()
                    ? mensajeDuplicado(existente.getNombre())
                    : "Ya existe un proveedor llamado " + existente.getNombre()
                            + ", pero está dado de baja. Reactivalo para volver a usarlo.");
        });
        Proveedor proveedor = new Proveedor(TenantContext.requireFerreteriaId(), nombre, cuit);
        if (Boolean.FALSE.equals(request.activo())) {
            proveedor.desactivar();
        }
        return ProveedorResponse.from(proveedorRepository.save(proveedor));
    }

    @Transactional
    public ProveedorResponse actualizar(Long id, ProveedorRequest request) {
        Proveedor proveedor = buscar(id);
        String cuit = cuitValido(request.cuit());
        String nombre = request.nombre().trim();
        if (proveedorRepository.existeOtroConNombreScoped(nombre, id)) {
            throw new ConflictException(mensajeDuplicado(nombre));
        }
        // Sin activo en el pedido se conserva el estado actual: un PUT no reactiva por omision.
        boolean activo = request.activo() == null ? proveedor.isActivo() : request.activo();
        proveedor.actualizar(nombre, cuit, activo);
        return ProveedorResponse.from(proveedor);
    }

    /** Vuelve a activar un proveedor dado de baja. No revalida el CUIT: solo cambia el estado. */
    @Transactional
    public ProveedorResponse reactivar(Long id) {
        Proveedor proveedor = buscar(id);
        proveedor.reactivar();
        return ProveedorResponse.from(proveedor);
    }

    /** El CUIT con guiones (o null) si es valido; si no, 400 en el campo cuit. */
    private static String cuitValido(String cuit) {
        try {
            return Cuit.normalizar(cuit);
        } catch (IllegalArgumentException e) {
            throw new DatosInvalidosException(Map.of("cuit", e.getMessage()));
        }
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
