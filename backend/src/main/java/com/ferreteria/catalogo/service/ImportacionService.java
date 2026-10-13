package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.ArticuloAusente;
import com.ferreteria.catalogo.dto.ImportacionResponse;
import com.ferreteria.catalogo.dto.MuestraResponse;
import com.ferreteria.catalogo.dto.ValidacionBonificaciones;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.catalogo.dto.VistaPreviaResponse;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.AltaPlaneada;
import com.ferreteria.catalogo.model.ArchivoLista;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloVigente;
import com.ferreteria.catalogo.model.Bonificaciones;
import com.ferreteria.catalogo.model.CambioPlaneado;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.ConteosImportacion;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.EntradaPlan;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.NormalizadorCodigo;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.PlanImportacion;
import com.ferreteria.catalogo.model.PlanificadorImportacion;
import com.ferreteria.catalogo.model.PrecioListaPlaneado;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.catalogo.service.lectura.LectorDeListas;
import com.ferreteria.catalogo.service.lectura.LimitesDeLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErroresDeValidacion;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import com.ferreteria.tenant.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Importacion de la lista de precios de un proveedor (CU-04). La vista previa y aplicar arman el plan
 * con el mismo camino (preparar + el planificador puro); aplicar ademas verifica que los contadores no
 * hayan cambiado y recien entonces escribe, todo en una transaccion.
 */
@Service
public class ImportacionService {

    static final int MAXIMO_DETALLE = 1000;

    private final LectorDeListas lector;
    private final ProveedorRepository proveedorRepository;
    private final RubroRepository rubroRepository;
    private final ArticuloRepository articuloRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ImportacionListaRepository importacionListaRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final Validator validator;

    public ImportacionService(
            LectorDeListas lector,
            ProveedorRepository proveedorRepository,
            RubroRepository rubroRepository,
            ArticuloRepository articuloRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ImportacionListaRepository importacionListaRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            Validator validator) {
        this.validator = validator;
        this.lector = lector;
        this.proveedorRepository = proveedorRepository;
        this.rubroRepository = rubroRepository;
        this.articuloRepository = articuloRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.importacionListaRepository = importacionListaRepository;
        this.configuracionPreciosService = configuracionPreciosService;
    }

    /**
     * TODAS las filas leidas de una hoja (con su numero original) y todas sus celdas, tal como vienen,
     * para que el Dueno vea la hoja completa y arme el mapeo. El archivo no se guarda en el servidor
     * (se vuelve a subir en cada pedido), asi que no se pagina. Las filas las acota el tope del lector
     * ({@link LimitesDeLista#MAXIMO_FILAS_LEIDAS}) y las columnas, {@link LimitesDeLista#MAXIMO_COLUMNAS_DE_MUESTRA}.
     * No toca la base.
     */
    public MuestraResponse muestra(ArchivoLista archivo, int hoja) {
        LecturaLista lectura = lector.leer(archivo.contenido(), hoja);
        List<MuestraResponse.FilaMuestra> filas = lectura.filas().stream()
                .map(fila -> new MuestraResponse.FilaMuestra(fila.numero(), celdasComoTexto(fila)))
                .toList();
        int columnas = filas.stream().mapToInt(fila -> fila.celdas().size()).max().orElse(0);
        return new MuestraResponse(lectura.hojas(), hoja, columnas, filas);
    }

    /** Lo que pasaria al aplicar. No escribe nada: ni articulos, ni historial, ni el proveedor. */
    @Transactional(readOnly = true)
    public VistaPreviaResponse vistaPrevia(ArchivoLista archivo, VistaPreviaRequest request) {
        Proveedor proveedor = buscarProveedor(request.proveedorId());
        validarBonificaciones(request);
        Preparacion preparacion = preparar(
                proveedor, archivo, request.mapeo().toMapeo(), request.listaIncluyeIva(), request.toBonificaciones());
        return respuestaDe(preparacion.plan());
    }

    /**
     * Aplica la lista en UNA transaccion. Primero lee y planifica; si los contadores no coinciden con
     * los que el cliente vio (deriva) responde 409 SIN haber escrito nada. Recien despues cambia los
     * articulos (ya administrados por JPA, se escriben al terminar la transaccion) y guarda el historial
     * y el registro de la importacion. Cualquier falla revierte todo.
     */
    @Transactional
    public ImportacionResponse aplicar(ArchivoLista archivo, AplicarRequest request, Long usuarioId) {
        Proveedor proveedor = buscarProveedor(request.proveedorId());
        // El rubro solo importa si se crean articulos; uno ajeno da 404 antes de leer el archivo.
        Rubro rubro = request.crearNuevos() && request.rubroId() != null
                ? rubroRepository.findScoped(request.rubroId())
                        .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"))
                : null;
        validarBonificaciones(request);
        MapeoLista mapeo = request.mapeo().toMapeo();
        Bonificaciones bonificaciones = request.toBonificaciones();
        Preparacion preparacion = preparar(proveedor, archivo, mapeo, request.listaIncluyeIva(), bonificaciones);
        PlanImportacion plan = preparacion.plan();

        // Deriva: se compara ANTES de la primera escritura.
        if (!plan.conteos().equals(request.conteosEsperados().toConteos())) {
            throw new ConflictException(
                    "La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.");
        }

        Map<Long, Articulo> articulosPorId = new HashMap<>();
        preparacion.articulos().forEach(articulo -> articulosPorId.put(articulo.getId(), articulo));

        List<HistorialPrecio> historial = new ArrayList<>();
        for (CambioPlaneado cambio : plan.cambios()) {
            Articulo articulo = articulosPorId.get(cambio.articuloId());
            articulo.actualizarCosto(cambio.costoNuevo(), cambio.precioLista(), preparacion.contextoDe(articulo))
                    .ifPresent(c -> historial.add(HistorialPrecio.de(articulo, c, OrigenPrecio.IMPORTACION, usuarioId)));
        }
        for (PrecioListaPlaneado sinCambio : plan.sinCambio()) {
            articulosPorId.get(sinCambio.articuloId()).registrarPrecioLista(sinCambio.precioLista());
        }

        int creados = 0;
        if (request.crearNuevos() && !plan.altas().isEmpty()) {
            creados = crearNuevos(plan.altas(), rubro, proveedor, preparacion, usuarioId, historial);
        }

        // Unico lugar donde se guardan el mapeo, el IVA y las bonificaciones (solo si todo salio bien).
        proveedor.configurarLista(
                mapeo.conEncabezados(preparacion.encabezado()), request.listaIncluyeIva(), bonificaciones);

        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        ConteosImportacion conteos = new ConteosImportacion(
                plan.conteos().suben(), plan.conteos().bajan(), creados, plan.conteos().sinCambio(),
                plan.conteos().noEstan(), plan.conteos().conError());
        ImportacionLista importacion = importacionListaRepository.save(ImportacionLista.aplicada(
                TenantContext.requireFerreteriaId(), proveedor.getId(), usuarioId, archivo.nombre(), conteos));
        return ImportacionResponse.from(importacion);
    }

    /**
     * Crea los articulos nuevos con los defaults de la importacion (codigo interno = codigo del
     * proveedor, UNIDAD, IVA_21, activo, stock minimo 0, sin margen propio). Se guardan TODOS antes de
     * armar su historial, que necesita los ids que genera la base. Devuelve cuantos se crearon.
     */
    private int crearNuevos(
            List<AltaPlaneada> altas, Rubro rubro, Proveedor proveedor, Preparacion preparacion, Long usuarioId,
            List<HistorialPrecio> historial) {
        Long rubroId = rubro == null ? null : rubro.getId();
        ContextoPrecio contexto = preparacion.contextoDeRubro(rubroId);
        List<Articulo> nuevos = new ArrayList<>();
        List<CambioPrecio> cambios = new ArrayList<>();
        for (AltaPlaneada alta : altas) {
            Articulo nuevo = new Articulo(TenantContext.requireFerreteriaId());
            Optional<CambioPrecio> cambio = nuevo.actualizar(new DatosArticulo(
                    alta.codigo(), null, alta.codigo(), alta.descripcion(), rubroId, proveedor.getId(), alta.costo(),
                    null, AlicuotaIva.IVA_21, Unidad.UNIDAD, BigDecimal.ZERO, null, true), contexto);
            nuevo.registrarPrecioLista(alta.precioLista());
            nuevos.add(nuevo);
            cambios.add(cambio.orElseThrow());
        }
        articuloRepository.saveAll(nuevos);
        for (int i = 0; i < nuevos.size(); i++) {
            historial.add(HistorialPrecio.de(nuevos.get(i), cambios.get(i), OrigenPrecio.IMPORTACION, usuarioId));
        }
        return nuevos.size();
    }

    // ---- preparar ----

    /**
     * Todo lo que se lee, ANTES de mutar nada (asi Hibernate no hace flush a mitad del recorrido): el
     * archivo, los articulos del proveedor en una consulta, un contexto de precio por rubro y otro
     * sin rubro, y los codigos internos ocupados. Las bonificaciones son las de la solicitud.
     */
    private Preparacion preparar(
            Proveedor proveedor, ArchivoLista archivo, MapeoLista mapeo, boolean listaIncluyeIva,
            Bonificaciones bonificaciones) {
        LecturaLista lectura = lector.leer(archivo.contenido(), mapeo.hoja());
        // Filas de datos = las no vacias desde la primera fila elegida; los titulos y el encabezado no cuentan.
        long filasDeDatos = lectura.filas().stream().filter(fila -> fila.numero() >= mapeo.filaDesde()).count();
        if (filasDeDatos == 0) {
            throw new BadRequestException("No hay filas de datos desde la fila " + mapeo.filaDesde() + ".");
        }
        if (filasDeDatos > LimitesDeLista.MAXIMO_FILAS_DE_DATOS) {
            throw LimitesDeLista.demasiadasFilas();
        }

        List<Articulo> articulos = articuloRepository.deProveedorScoped(proveedor.getId());
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        Map<Long, ContextoPrecio> contextoPorRubro = new HashMap<>();
        for (Rubro rubro : rubroRepository.listarScoped()) {
            contextoPorRubro.put(rubro.getId(), contextoDe(rubro.getMargenPorcentaje(), configuracion));
        }
        ContextoPrecio contextoSinRubro = contextoDe(null, configuracion);

        // La base devuelve los codigos como estan guardados: se normalizan igual que los del archivo.
        Set<String> ocupados = new HashSet<>();
        for (String codigo : articuloRepository.codigosInternosOcupadosScoped(
                PlanificadorImportacion.codigosCandidatos(lectura.filas(), mapeo))) {
            String clave = NormalizadorCodigo.clave(codigo);
            if (clave != null) {
                ocupados.add(clave);
            }
        }

        FilaLeida encabezado = lectura.filas().stream()
                .filter(fila -> mapeo.filaDesde() > 1 && fila.numero() == mapeo.filaDesde() - 1)
                .findFirst()
                .orElse(null);

        PlanImportacion plan = PlanificadorImportacion.planificar(new EntradaPlan(
                lectura.filas(), mapeo, listaIncluyeIva, bonificaciones,
                articulos.stream().map(ArticuloVigente::de).toList(),
                contextoPorRubro, contextoSinRubro, ocupados, proveedor.getMapeoLista(), encabezado));
        return new Preparacion(plan, articulos, contextoPorRubro, contextoSinRubro, encabezado);
    }

    /** Lo que sale de preparar: el plan y lo necesario para aplicarlo sobre las entidades ya cargadas. */
    private record Preparacion(
            PlanImportacion plan,
            List<Articulo> articulos,
            Map<Long, ContextoPrecio> contextoPorRubro,
            ContextoPrecio contextoSinRubro,
            FilaLeida encabezado) {

        /** Contexto de precio de un articulo: el de su rubro, o el de los articulos sin rubro. */
        ContextoPrecio contextoDe(Articulo articulo) {
            return contextoDeRubro(articulo.getRubroId());
        }

        ContextoPrecio contextoDeRubro(Long rubroId) {
            if (rubroId == null) {
                return contextoSinRubro;
            }
            ContextoPrecio contexto = contextoPorRubro.get(rubroId);
            if (contexto == null) {
                throw new IllegalStateException("Falta el contexto de precio del rubro " + rubroId);
            }
            return contexto;
        }
    }

    // ---- apoyo ----

    /**
     * Valida las bonificaciones de la solicitud (grupo {@link ValidacionBonificaciones}). Va DESPUES de
     * buscar el proveedor (y el rubro): un recurso ajeno responde 404 antes que un 400 por datos invalidos
     * (R-033). El formato del error es el de la validacion del cuerpo: un mensaje por campo.
     */
    private void validarBonificaciones(Object solicitud) {
        Set<ConstraintViolation<Object>> violaciones = validator.validate(solicitud, ValidacionBonificaciones.class);
        if (violaciones.isEmpty()) {
            return;
        }
        ErroresDeValidacion errores = new ErroresDeValidacion();
        for (ConstraintViolation<Object> violacion : violaciones) {
            errores.agregar(
                    violacion.getPropertyPath().toString(),
                    violacion.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                    violacion.getMessage());
        }
        throw new DatosInvalidosException(errores.mensajes());
    }

    private Proveedor buscarProveedor(Long id) {
        return proveedorRepository.findScoped(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));
    }

    private static ContextoPrecio contextoDe(BigDecimal margenRubro, ConfiguracionPrecios configuracion) {
        return new ContextoPrecio(margenRubro, configuracion.margenDefectoPorcentaje(), configuracion.redondeoMultiplo());
    }

    private static List<String> celdasComoTexto(FilaLeida fila) {
        return fila.celdas().stream().limit(LimitesDeLista.MAXIMO_COLUMNAS_DE_MUESTRA).map(Celda::texto).toList();
    }

    private static VistaPreviaResponse respuestaDe(PlanImportacion plan) {
        boolean recortado = plan.cambios().size() > MAXIMO_DETALLE
                || plan.altas().size() > MAXIMO_DETALLE
                || plan.errores().size() > MAXIMO_DETALLE
                || plan.advertencias().size() > MAXIMO_DETALLE
                || plan.noEstan().size() > MAXIMO_DETALLE;
        return new VistaPreviaResponse(
                plan.conteos(),
                recortar(plan.cambios()),
                recortar(plan.altas()),
                recortar(plan.errores()),
                recortar(plan.advertencias()),
                recortar(plan.noEstan()).stream().map(ArticuloAusente::de).toList(),
                recortado);
    }

    private static <T> List<T> recortar(List<T> lista) {
        return lista.size() <= MAXIMO_DETALLE ? lista : List.copyOf(lista.subList(0, MAXIMO_DETALLE));
    }
}
