package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.AjustesRequest;
import com.ferreteria.catalogo.dto.ArticuloRequest;
import com.ferreteria.catalogo.api.UltimasCompras;
import com.ferreteria.catalogo.api.UltimasCompras.UltimaCompra;
import com.ferreteria.catalogo.dto.ArticuloResponse;
import com.ferreteria.catalogo.dto.UltimaCompraResponse;
import com.ferreteria.catalogo.dto.OfertaRequest;
import com.ferreteria.catalogo.dto.ValidacionAjustes;
import com.ferreteria.catalogo.model.Ajustes;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloSinListaException;
import com.ferreteria.catalogo.model.CodigoSiguiente;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImporteArgentino;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.PageResponse;
import com.ferreteria.common.exception.ArticuloModificado;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErroresDeValidacion;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ArticuloService {

    static final int TAMANO_MAXIMO = 100;
    static final String COTIZACION_APAGADA = "Activá la cotización para cargar artículos en dólares";

    private final ArticuloRepository articuloRepository;
    private final RubroRepository rubroRepository;
    private final ProveedorRepository proveedorRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final DolarDePrecios dolarDePrecios;
    private final Validator validator;
    private final UltimasCompras ultimasCompras;

    public ArticuloService(
            ArticuloRepository articuloRepository,
            RubroRepository rubroRepository,
            ProveedorRepository proveedorRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            DolarDePrecios dolarDePrecios,
            Validator validator,
            UltimasCompras ultimasCompras) {
        this.articuloRepository = articuloRepository;
        this.rubroRepository = rubroRepository;
        this.proveedorRepository = proveedorRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.dolarDePrecios = dolarDePrecios;
        this.validator = validator;
        this.ultimasCompras = ultimasCompras;
    }

    /**
     * Pagina ordenada por descripcion y, para desempatar, por id: con descripciones repetidas el orden
     * tiene que ser total o un articulo puede repetirse o perderse entre dos paginas. q busca sin
     * distinguir mayusculas en codigos y descripcion.
     */
    @Transactional(readOnly = true)
    public PageResponse<ArticuloResponse> listar(String q, int page, int size, boolean incluirInactivos) {
        return listar(q, page, size, incluirInactivos, false);
    }

    /** Con {@code enOferta}, solo los que tienen hoy una oferta que baja el precio normal (ofertas, S3). */
    @Transactional(readOnly = true)
    public PageResponse<ArticuloResponse> listar(
            String q, int page, int size, boolean incluirInactivos, boolean enOferta) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO),
                Sort.by(Sort.Order.asc("descripcion"), Sort.Order.asc("id")));
        String patron = FiltroArticulos.patronDeBusqueda(q);
        PageResponse<ArticuloResponse> pagina = PageResponse.from(
                enOferta
                        ? articuloRepository.buscarEnOfertaScoped(patron, incluirInactivos, HoraArgentina.hoy(), pageable)
                        : articuloRepository.buscarScoped(patron, incluirInactivos, pageable),
                ArticuloResponse::from);
        // Una sola consulta para el «costo al» de toda la pagina (GDS-05).
        Map<Long, LocalDate> costoAl = articuloRepository.costoAlScoped(
                pagina.items().stream().map(ArticuloResponse::id).toList());
        return new PageResponse<>(pagina.items().stream().map(a -> a.conCostoAl(costoAl.get(a.id()))).toList(),
                pagina.page(), pagina.size(), pagina.totalElements(), pagina.totalPages());
    }

    /** Un articulo de otra ferreteria da 404, igual que uno que no existe. Tambien devuelve los inactivos. */
    @Transactional(readOnly = true)
    public ArticuloResponse obtener(Long id) {
        ArticuloResponse ficha = conCostoAl(ArticuloResponse.from(buscar(id)));
        UltimaCompra ultima = ultimasCompras.deLosArticulos(List.of(id)).get(id);
        return ultima == null ? ficha : ficha.conUltimaCompra(UltimaCompraResponse.de(ultima));
    }

    /**
     * El codigo interno que se sugiere en el alta (mejoras-ferreteros-bloque-1, S3): el ultimo cargado a mano de la
     * ferreteria del request mas uno ({@link CodigoSiguiente}), libre. Null si no hay ninguno o no termina en numero.
     * Es solo una sugerencia: el guardado sigue validando que el codigo no este ocupado.
     */
    @Transactional(readOnly = true)
    public String codigoSiguiente() {
        return articuloRepository.ultimoCodigoCargadoAManoScoped()
                .flatMap(ultimo -> CodigoSiguiente.despuesDe(ultimo,
                        codigo -> articuloRepository.existeCodigoInternoScoped(codigo, null)))
                .orElse(null);
    }

    @Transactional
    public ArticuloResponse crear(ArticuloRequest request, Long usuarioId) {
        DatosArticulo datos = request.toDatos();
        validarUnicidad(datos, null);
        ContextoPrecio ctx = contextoDePrecio(datos, false);

        Articulo articulo = new Articulo(TenantContext.requireFerreteriaId());
        Optional<CambioPrecio> cambio = articulo.actualizar(datos, request.toPedidoDePrecio(), request.toRecargosDeCosto(), ctx);
        // Primero el articulo: el historial necesita el id que genera la base.
        Articulo guardado = articuloRepository.save(articulo);
        guardarHistorial(guardado, cambio, usuarioId);
        return conCostoAl(ArticuloResponse.from(guardado));
    }

    @Transactional
    public ArticuloResponse actualizar(Long id, ArticuloRequest request, Long usuarioId) {
        Articulo articulo = buscar(id);
        // Antes de cualquier otra validacion: una version vieja es el aviso mas importante (R-225).
        verificarVersion(articulo, request.version());
        // Sin activo ni moneda en el pedido se conservan los actuales: un PUT no reactiva ni pasa a pesos por omision.
        DatosArticulo datos = request.toDatos(articulo.isActivo(), articulo.getMoneda());
        validarUnicidad(datos, id);
        ContextoPrecio ctx = contextoDePrecio(datos, articulo.getMoneda() == Moneda.USD);

        Moneda monedaAnterior = articulo.getMoneda();
        // El articulo ya esta administrado por JPA: los cambios se escriben al terminar la transaccion.
        Optional<CambioPrecio> cambio = articulo.actualizar(datos, request.toPedidoDePrecio(), request.toRecargosDeCosto(), ctx);
        guardarHistorial(articulo, cambio, usuarioId, monedaAnterior != articulo.getMoneda() ? monedaAnterior : null);
        // Escribe ahora: un choque con otra escritura sale como 409 y la respuesta trae la version NUEVA.
        escribirOChocar();
        // El stock no se escribe aca (updatable=false) y la entidad pudo leerse antes de una venta: la
        // respuesta muestra el que esta confirmado en la base, sin tocarlo.
        BigDecimal stockVigente = articuloRepository.stockVigenteScoped(id).orElse(articulo.getStockActual());
        return conCostoAl(ArticuloResponse.from(articulo, stockVigente));
    }

    /**
     * Vuelve a activar un articulo dado de baja. Revalida el codigo de barras (unico entre los activos); no
     * toca precios, no escribe historial y la respuesta trae el stock confirmado en la base.
     */
    @Transactional
    public ArticuloResponse reactivar(Long id) {
        Articulo articulo = buscar(id);
        if (articulo.getCodigoBarras() != null
                && articuloRepository.existeCodigoBarrasActivoScoped(articulo.getCodigoBarras(), id)) {
            throw new ConflictException(mensajeCodigoBarras(articulo.getCodigoBarras()));
        }
        articulo.reactivar();
        // Escribe ahora: la respuesta trae la version NUEVA y un choque sale como 409 desde aca.
        escribirOChocar();
        BigDecimal stockVigente = articuloRepository.stockVigenteScoped(id).orElse(articulo.getStockActual());
        return conCostoAl(ArticuloResponse.from(articulo, stockVigente));
    }

    /** Baja logica: el articulo y su historial de precios se conservan. No escribe historial. */
    @Transactional
    public void desactivar(Long id) {
        buscar(id).desactivar();
    }

    /**
     * Edita los tres ajustes de un articulo con lista (corte 3). Orden: 404, 400, 409 sin lista, 200 si no cambian,
     * 409 sin cotizacion, 409 por pisar el costo y recien despues la escritura, con historial MANUAL solo si cambia
     * el costo o el precio. El costo sale del precio de lista con la base GUARDADA en el articulo, nunca del proveedor
     * de hoy. Lee el articulo con lock de escritura. El 403 del Vendedor lo resuelve la seguridad antes de llegar aca.
     */
    @Transactional
    public ArticuloResponse editarAjustes(Long id, AjustesRequest request, Long usuarioId) {
        // Con lock ANTES de leer costo, base y ajustes: una importacion que confirma en el medio no se pierde, y el
        // chequeo de R-214 compara contra el costo vigente y no contra uno viejo.
        Articulo articulo = articuloRepository.findParaEditarScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        validar(request);
        verificarVersion(articulo, request.version());
        if (!articulo.tieneLista()) {
            throw new ConflictException(ArticuloSinListaException.MENSAJE);
        }
        Ajustes actuales = articulo.getAjustes();
        Ajustes nuevos = request.toAjustes();
        if (nuevos.equals(actuales)) {
            return conCostoAl(ArticuloResponse.from(articulo, stockVigente(id, articulo)));
        }
        // Un articulo en dolares ya estaba en dolares: con la cotizacion apagada se permite, con el dolar congelado.
        ContextoPrecio ctx = contextoDe(articulo.getRubroId(), articulo.getMoneda(), true);
        avisarSiPisaElCosto(articulo, actuales, nuevos, request.confirmaPisarCosto());

        Optional<CambioPrecio> cambio = articulo.editarAjustes(nuevos, ctx);
        guardarHistorial(articulo, cambio, usuarioId);
        escribirOChocar();
        // Despues del flush (C-221): el stock confirmado se lee con la escritura ya hecha.
        return conCostoAl(ArticuloResponse.from(articulo, stockVigente(id, articulo)));
    }

    /**
     * Pone el articulo en oferta (ofertas, S1), reemplazando la que tuviera. Solo cambia la oferta: el precio normal
     * y su historial quedan como estan. Mismo orden que los ajustes: 404, version, y despues las validaciones. Un
     * articulo dado de baja no se pone en oferta (422), como no se le ajusta el stock; sacarle la que tenia, si.
     */
    @Transactional
    public ArticuloResponse ponerOferta(Long id, OfertaRequest request) {
        Articulo articulo = articuloRepository.findParaEditarScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        verificarVersion(articulo, request.version());
        if (!articulo.isActivo()) {
            throw new ReglaDeNegocioException(
                    "\"" + articulo.getDescripcion() + "\" está dado de baja. Reactivalo para ponerle una oferta.");
        }
        articulo.fijarOferta(request.toOferta(), HoraArgentina.hoy());
        escribirOChocar();
        return conCostoAl(ArticuloResponse.from(articulo, stockVigente(id, articulo)));
    }

    /** Saca la oferta: el articulo vuelve a venderse a su precio normal. Sin oferta no cambia nada. */
    @Transactional
    public ArticuloResponse quitarOferta(Long id) {
        Articulo articulo = articuloRepository.findParaEditarScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        articulo.quitarOferta();
        escribirOChocar();
        return conCostoAl(ArticuloResponse.from(articulo, stockVigente(id, articulo)));
    }

    /**
     * La respuesta con su «costo al» (GDS-05), leido de la base: el historial que se acaba de guardar ya cuenta, porque
     * la consulta nativa escribe antes lo pendiente.
     */
    private ArticuloResponse conCostoAl(ArticuloResponse respuesta) {
        return respuesta.conCostoAl(articuloRepository.costoAlScoped(List.of(respuesta.id())).get(respuesta.id()));
    }

    private BigDecimal stockVigente(Long id, Articulo articulo) {
        return articuloRepository.stockVigenteScoped(id).orElse(articulo.getStockActual());
    }

    /**
     * R-214: si el costo cambia y el guardado no es el que hoy sale de la lista, el costo se pisaria (lo cargaron a
     * mano o lo movio un masivo). Sin la confirmacion es un 409 solo con mensaje.
     */
    private static void avisarSiPisaElCosto(Articulo articulo, Ajustes actuales, Ajustes nuevos, boolean confirmado) {
        BigDecimal costoNuevo = articulo.costoDeLista(nuevos).orElseThrow(ArticuloSinListaException::new);
        BigDecimal deLaListaHoy = articulo.costoDeLista(actuales).orElseThrow(ArticuloSinListaException::new);
        BigDecimal guardado = articulo.getCosto();
        boolean cambiaElCosto = costoNuevo.compareTo(guardado) != 0;
        boolean noCoincide = guardado.compareTo(deLaListaHoy) != 0;
        if (cambiaElCosto && noCoincide && !confirmado) {
            Moneda moneda = articulo.getMoneda();
            throw new ConflictException("El costo guardado (" + ImporteArgentino.formato(guardado, moneda)
                    + ") no coincide con el que sale de la lista (" + ImporteArgentino.formato(deLaListaHoy, moneda)
                    + "). Si guardás, el costo pasa a " + ImporteArgentino.formato(costoNuevo, moneda) + ".");
        }
    }

    /**
     * Valida con el grupo {@link ValidacionAjustes}, DESPUES de buscar el articulo (uno ajeno da 404 antes que 400).
     * Mismo formato de error que la validacion del cuerpo: un mensaje por campo.
     */
    private void validar(AjustesRequest request) {
        Set<ConstraintViolation<AjustesRequest>> violaciones = validator.validate(request, ValidacionAjustes.class);
        if (violaciones.isEmpty()) {
            return;
        }
        ErroresDeValidacion errores = new ErroresDeValidacion();
        for (ConstraintViolation<AjustesRequest> violacion : violaciones) {
            errores.agregar(
                    violacion.getPropertyPath().toString(),
                    violacion.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                    violacion.getMessage());
        }
        throw new DatosInvalidosException(errores.mensajes());
    }

    /**
     * Bloqueo optimista entre pedidos (R-225): el cliente tiene que mandar la version que leyo (sin ella es un 400,
     * decision 48) y, si ya no es la vigente, otro cambio se confirmo antes y el formulario esta viejo (409). Va
     * despues del 404 y antes de cualquier otra validacion o 409.
     */
    private static void verificarVersion(Articulo articulo, Long versionDelCliente) {
        if (versionDelCliente == null) {
            throw new BadRequestException(ArticuloModificado.FALTA_VERSION);
        }
        if (!versionDelCliente.equals(articulo.getVersion())) {
            throw new ConflictException(ArticuloModificado.MENSAJE);
        }
    }

    /**
     * Escribe el articulo ahora y no al terminar la transaccion: asi un choque con otra escritura que confirmo entre
     * leer y escribir sale como 409 desde aca, y la version que devuelve la respuesta ya es la nueva.
     */
    private void escribirOChocar() {
        try {
            articuloRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(ArticuloModificado.MENSAJE);
        }
    }

    private Articulo buscar(Long id) {
        return articuloRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
    }

    /**
     * Codigo interno: unico por ferreteria, contando los dados de baja. Codigo de barras: unico solo
     * entre activos, asi que un articulo inactivo no lo valida (reactivar si lo revalida). Codigo del
     * proveedor: unico por proveedor, y solo si hay proveedor. idPropio excluye al articulo editado.
     * Junta todos los repetidos en un solo 409, con un mensaje por campo.
     */
    private void validarUnicidad(DatosArticulo datos, Long idPropio) {
        Map<String, String> repetidos = new LinkedHashMap<>();
        if (articuloRepository.existeCodigoInternoScoped(datos.codigoInterno(), idPropio)) {
            repetidos.put("codigoInterno",
                    "Ya existe un artículo con el código interno " + datos.codigoInterno() + ".");
        }
        if (datos.activo() && datos.codigoBarras() != null
                && articuloRepository.existeCodigoBarrasActivoScoped(datos.codigoBarras(), idPropio)) {
            repetidos.put("codigoBarras", mensajeCodigoBarras(datos.codigoBarras()));
        }
        if (datos.proveedorId() != null && datos.codigoProveedor() != null
                && articuloRepository.existeCodigoProveedorScoped(datos.proveedorId(), datos.codigoProveedor(), idPropio)) {
            repetidos.put("codigoProveedor",
                    "Ya existe un artículo de este proveedor con el código " + datos.codigoProveedor() + ".");
        }
        if (!repetidos.isEmpty()) {
            throw new ConflictException(String.join(" ", repetidos.values()), repetidos);
        }
    }

    private static String mensajeCodigoBarras(String codigoBarras) {
        return "Ya existe un artículo activo con el código de barras " + codigoBarras + ".";
    }

    /**
     * Resuelve rubro y proveedor con findScoped (un id ajeno da 404, nunca se enlaza a otra ferreteria)
     * y arma lo que necesita el calculo del precio. Sin rubro, margenRubro es null. Un articulo en pesos no consulta
     * el dolar; uno en dolares se calcula con el dolar VIGENTE de la ferreteria (el que fijo su ultimo recalculo), no
     * con el valor de la API en ese momento.
     *
     * @param yaEraEnDolares true si el articulo que se edita ya estaba en USD antes de este cambio (false en el alta)
     */
    private ContextoPrecio contextoDePrecio(DatosArticulo datos, boolean yaEraEnDolares) {
        Rubro rubro = rubroScoped(datos.rubroId());
        if (datos.proveedorId() != null) {
            proveedorRepository.findScoped(datos.proveedorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));
        }
        return contextoDe(rubro, datos.moneda(), yaEraEnDolares);
    }

    /** El contexto de un articulo ya guardado: su rubro (con findScoped) y su moneda; el proveedor no interviene. */
    private ContextoPrecio contextoDe(Long rubroId, Moneda moneda, boolean yaEraEnDolares) {
        return contextoDe(rubroScoped(rubroId), moneda, yaEraEnDolares);
    }

    private Rubro rubroScoped(Long rubroId) {
        return rubroId == null ? null : rubroRepository.findScoped(rubroId)
                .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
    }

    private ContextoPrecio contextoDe(Rubro rubro, Moneda moneda, boolean yaEraEnDolares) {
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        return new ContextoPrecio(
                rubro == null ? null : rubro.getMargenPorcentaje(),
                configuracion.margenDefectoPorcentaje(),
                configuracion.redondeoMultiplo(),
                moneda == Moneda.USD ? dolarParaArticulosEnDolares(yaEraEnDolares) : null);
    }

    /**
     * El dolar vigente de la ferreteria. Dar de alta un articulo en USD o pasar uno de pesos a USD exige la cotizacion
     * activada (409); editar uno que ya esta en USD se permite con la cotizacion apagada, con el dolar vigente congelado.
     * Sin ningun valor guardado el precio no se puede calcular (409).
     */
    private BigDecimal dolarParaArticulosEnDolares(boolean yaEraEnDolares) {
        if (!yaEraEnDolares && !dolarDePrecios.activa()) {
            throw new ConflictException(COTIZACION_APAGADA);
        }
        return dolarDePrecios.vigente().orElseThrow(() -> new ConflictException(DolarDePrecios.SIN_COTIZACION));
    }

    /** Solo se escribe historial si cambio el costo o el precio final. */
    private void guardarHistorial(Articulo articulo, Optional<CambioPrecio> cambio, Long usuarioId) {
        guardarHistorial(articulo, cambio, usuarioId, null);
    }

    /** Con {@code monedaAnterior} si la moneda cambio (null si no), como en la importacion: cuenta para «Costo al». */
    private void guardarHistorial(Articulo articulo, Optional<CambioPrecio> cambio, Long usuarioId,
                                  Moneda monedaAnterior) {
        cambio.ifPresent(c -> historialPrecioRepository.save(
                HistorialPrecio.de(articulo, c, OrigenPrecio.MANUAL, usuarioId, monedaAnterior)));
    }
}
