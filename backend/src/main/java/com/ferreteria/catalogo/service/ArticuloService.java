package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ArticuloRequest;
import com.ferreteria.catalogo.dto.ArticuloResponse;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.PageResponse;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ArticuloService {

    static final int TAMANO_MAXIMO = 100;

    private final ArticuloRepository articuloRepository;
    private final RubroRepository rubroRepository;
    private final ProveedorRepository proveedorRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;

    public ArticuloService(
            ArticuloRepository articuloRepository,
            RubroRepository rubroRepository,
            ProveedorRepository proveedorRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService) {
        this.articuloRepository = articuloRepository;
        this.rubroRepository = rubroRepository;
        this.proveedorRepository = proveedorRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
    }

    /** Pagina ordenada por descripcion; q busca sin distinguir mayusculas en codigos y descripcion. */
    @Transactional(readOnly = true)
    public PageResponse<ArticuloResponse> listar(String q, int page, int size, boolean incluirInactivos) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO), Sort.by("descripcion"));
        return PageResponse.from(
                articuloRepository.buscarScoped(patronDeBusqueda(q), incluirInactivos, pageable),
                ArticuloResponse::from);
    }

    /** Un articulo de otra ferreteria da 404, igual que uno que no existe. Tambien devuelve los inactivos. */
    @Transactional(readOnly = true)
    public ArticuloResponse obtener(Long id) {
        return ArticuloResponse.from(buscar(id));
    }

    @Transactional
    public ArticuloResponse crear(ArticuloRequest request, Long usuarioId) {
        DatosArticulo datos = request.toDatos();
        validarUnicidad(datos, null);
        ContextoPrecio ctx = contextoDePrecio(datos);

        Articulo articulo = new Articulo(TenantContext.requireFerreteriaId());
        Optional<CambioPrecio> cambio = articulo.actualizar(datos, ctx);
        // Primero el articulo: el historial necesita el id que genera la base.
        Articulo guardado = articuloRepository.save(articulo);
        guardarHistorial(guardado, cambio, usuarioId);
        return ArticuloResponse.from(guardado);
    }

    @Transactional
    public ArticuloResponse actualizar(Long id, ArticuloRequest request, Long usuarioId) {
        Articulo articulo = buscar(id);
        DatosArticulo datos = request.toDatos();
        validarUnicidad(datos, id);
        ContextoPrecio ctx = contextoDePrecio(datos);

        // El articulo ya esta administrado por JPA: los cambios se escriben al terminar la transaccion.
        Optional<CambioPrecio> cambio = articulo.actualizar(datos, ctx);
        guardarHistorial(articulo, cambio, usuarioId);
        return ArticuloResponse.from(articulo);
    }

    /** Baja logica: el articulo y su historial de precios se conservan. No escribe historial. */
    @Transactional
    public void desactivar(Long id) {
        buscar(id).desactivar();
    }

    private Articulo buscar(Long id) {
        return articuloRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
    }

    /**
     * Codigo interno: unico por ferreteria, contando los dados de baja. Codigo de barras: unico solo
     * entre activos, asi que un articulo inactivo no lo valida (reactivar si lo revalida). Codigo del
     * proveedor: unico por proveedor, y solo si hay proveedor. idPropio excluye al articulo editado.
     */
    private void validarUnicidad(DatosArticulo datos, Long idPropio) {
        if (articuloRepository.existeCodigoInternoScoped(datos.codigoInterno(), idPropio)) {
            throw new ConflictException("Ya existe un artículo con el código interno " + datos.codigoInterno() + ".");
        }
        if (datos.activo() && datos.codigoBarras() != null
                && articuloRepository.existeCodigoBarrasActivoScoped(datos.codigoBarras(), idPropio)) {
            throw new ConflictException(
                    "Ya existe un artículo activo con el código de barras " + datos.codigoBarras() + ".");
        }
        if (datos.proveedorId() != null && datos.codigoProveedor() != null
                && articuloRepository.existeCodigoProveedorScoped(datos.proveedorId(), datos.codigoProveedor(), idPropio)) {
            throw new ConflictException(
                    "Ya existe un artículo de este proveedor con el código " + datos.codigoProveedor() + ".");
        }
    }

    /**
     * Resuelve rubro y proveedor con findScoped (un id ajeno da 404, nunca se enlaza a otra ferreteria)
     * y arma lo que necesita el calculo del precio. Sin rubro, margenRubro es null.
     */
    private ContextoPrecio contextoDePrecio(DatosArticulo datos) {
        Rubro rubro = datos.rubroId() == null ? null : rubroRepository.findScoped(datos.rubroId())
                .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
        if (datos.proveedorId() != null) {
            proveedorRepository.findScoped(datos.proveedorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));
        }
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        return new ContextoPrecio(
                rubro == null ? null : rubro.getMargenPorcentaje(),
                configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo());
    }

    /** Solo se escribe historial si cambio el costo o el precio final. */
    private void guardarHistorial(Articulo articulo, Optional<CambioPrecio> cambio, Long usuarioId) {
        cambio.ifPresent(c -> historialPrecioRepository.save(
                HistorialPrecio.de(articulo, c, OrigenPrecio.MANUAL, usuarioId)));
    }

    /** null si q esta vacia; si no, %q% con \, % y _ escapados para que se busquen como texto. */
    private static String patronDeBusqueda(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escapado = q.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escapado + "%";
    }
}
