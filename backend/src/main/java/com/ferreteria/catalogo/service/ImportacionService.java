package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.ArticuloAusente;
import com.ferreteria.catalogo.dto.ImportacionResponse;
import com.ferreteria.catalogo.dto.MuestraResponse;
import com.ferreteria.catalogo.dto.FilaSinCambio;
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
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.DatosArticulo;
import com.ferreteria.catalogo.model.EntradaPlan;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ImportacionLista;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.model.ListaCambioException;
import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.NormalizadorCodigo;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.PlanImportacion;
import com.ferreteria.catalogo.model.PlanificadorImportacion;
import com.ferreteria.catalogo.model.PrecioListaPlaneado;
import com.ferreteria.catalogo.model.Proveedor;
import com.ferreteria.catalogo.model.ReconocedorDeColumnas;
import com.ferreteria.catalogo.model.Rubro;
import com.ferreteria.catalogo.model.SeleccionDeFilas;
import com.ferreteria.catalogo.model.SeleccionInvalidaException;
import com.ferreteria.catalogo.model.Unidad;
import com.ferreteria.catalogo.model.ZonaDeDatos;
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
import com.ferreteria.cotizacion.api.DolarDePrecios;
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

    private final LectorDeListas lector;
    private final ProveedorRepository proveedorRepository;
    private final RubroRepository rubroRepository;
    private final ArticuloRepository articuloRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ImportacionListaRepository importacionListaRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final DolarDePrecios dolarDePrecios;
    private final Validator validator;

    public ImportacionService(
            LectorDeListas lector,
            ProveedorRepository proveedorRepository,
            RubroRepository rubroRepository,
            ArticuloRepository articuloRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ImportacionListaRepository importacionListaRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            DolarDePrecios dolarDePrecios,
            Validator validator) {
        this.validator = validator;
        this.lector = lector;
        this.proveedorRepository = proveedorRepository;
        this.rubroRepository = rubroRepository;
        this.articuloRepository = articuloRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.importacionListaRepository = importacionListaRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.dolarDePrecios = dolarDePrecios;
    }

    /**
     * El archivo ENTERO para armar el mapeo: todas las hojas, cada una con TODAS sus filas leidas (con su numero
     * original) y todas sus celdas tal como vienen, mas la propuesta de mapeo que reconoce los encabezados. El archivo
     * no se guarda en el servidor (se vuelve a subir en cada pedido), asi que no se pagina. Las filas las acota el tope
     * del lector ({@link LimitesDeLista#MAXIMO_FILAS_LEIDAS}) y las columnas, {@link LimitesDeLista#MAXIMO_COLUMNAS_DE_MUESTRA}.
     * El libro se lee una sola vez y no se toca la base: la propuesta sale solo del archivo.
     */
    public MuestraResponse muestra(ArchivoLista archivo) {
        LecturaLista lectura = lector.leer(archivo.contenido());
        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lectura);
        List<MuestraResponse.HojaMuestra> hojas = new ArrayList<>();
        for (ReconocedorDeColumnas.HojaReconocida hoja : reconocimiento.hojas()) {
            List<MuestraResponse.FilaMuestra> filas = lectura.filasDeHoja(hoja.indice()).stream()
                    .map(fila -> new MuestraResponse.FilaMuestra(fila.numero(), celdasComoTexto(fila)))
                    .toList();
            int columnas = filas.stream().mapToInt(fila -> fila.celdas().size()).max().orElse(0);
            hojas.add(new MuestraResponse.HojaMuestra(
                    hoja.indice(), hoja.nombre(), columnas, hoja.filaEncabezado(), filas));
        }
        return new MuestraResponse(hojas, reconocimiento.propuesta());
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
     * Aplica la lista en UNA transaccion. Primero lee y planifica; despues aplica la seleccion de filas: una fila que
     * no existe o con error es un 400 y si los contadores de lo elegido no coinciden con los que el cliente vio
     * (deriva) un 409, los dos SIN haber escrito nada. Recien despues escribe: guarda el registro de la importacion (para
     * tener su id), cambia los articulos elegidos (ya administrados por JPA, se escriben al terminar la transaccion),
     * crea los nuevos elegidos, y guarda el historial y el mapeo del proveedor. Cualquier falla revierte todo. Lo que
     * no se eligio no se toca.
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

        // Seleccion y deriva: ANTES de la primera escritura.
        PlanImportacion plan = elegir(preparacion.plan(), request);

        Map<Long, Articulo> articulosPorId = new HashMap<>();
        preparacion.articulos().forEach(articulo -> articulosPorId.put(articulo.getId(), articulo));

        // Primero el registro de la importacion, con los contadores de lo aplicado: hace falta su id para marcar cada articulo.
        int creados = request.crearNuevos() ? plan.altas().size() : 0;
        ConteosImportacion conteos = new ConteosImportacion(
                plan.conteos().suben(), plan.conteos().bajan(), creados, plan.conteos().sinCambio(),
                plan.conteos().noEstan(), plan.conteos().conError(), plan.conteos().cambianMoneda(),
                plan.conteos().corrigen(), plan.conteos().salteadas());
        ImportacionLista importacion = importacionListaRepository.save(ImportacionLista.aplicada(
                TenantContext.requireFerreteriaId(), proveedor.getId(), usuarioId, archivo.nombre(), conteos));
        Long importacionId = importacion.getId();

        List<HistorialPrecio> historial = new ArrayList<>();
        for (List<CambioPlaneado> cambios : List.of(plan.cambios(), plan.cambiosDeMoneda(), plan.correcciones())) {
            for (CambioPlaneado cambio : cambios) {
                Articulo articulo = articulosPorId.get(cambio.articuloId());
                articulo.aplicarLista(cambio.costoNuevo(), cambio.precioLista(), cambio.monedaNueva(),
                                cambio.alicuotaNueva(), cambio.ajustesNuevos(), importacionId,
                                preparacion.contextoDe(articulo))
                        .ifPresent(c -> historial.add(HistorialPrecio.de(
                                articulo, c.cambio(), OrigenPrecio.IMPORTACION, usuarioId, c.monedaAnterior())));
            }
        }
        for (PrecioListaPlaneado sinCambio : plan.sinCambio()) {
            // El costo y el precio no cambian, pero la fila puede traer otro IVA: se guarda sin dejar historial.
            Articulo articulo = articulosPorId.get(sinCambio.articuloId());
            articulo.aplicarLista(sinCambio.costo(), sinCambio.precioLista(), null, sinCambio.alicuotaNueva(),
                            articulo.getAjustes(), importacionId, preparacion.contextoDe(articulo))
                    .ifPresent(c -> historial.add(HistorialPrecio.de(
                            articulo, c.cambio(), OrigenPrecio.IMPORTACION, usuarioId, c.monedaAnterior())));
        }

        if (request.crearNuevos() && !plan.altas().isEmpty()) {
            crearNuevos(plan.altas(), rubro, proveedor, preparacion, importacionId, usuarioId, historial);
        }

        // Unico lugar donde se guardan el mapeo, el IVA y las bonificaciones (solo si todo salio bien).
        proveedor.configurarLista(
                mapeo.conEncabezados(preparacion.encabezado()), request.listaIncluyeIva(), bonificaciones);

        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        return ImportacionResponse.from(importacion, proveedor.getNombre());
    }

    /**
     * El historial de importaciones de la ferreteria, de la mas reciente a la mas vieja y sin paginar. Con
     * {@code proveedorId} solo las de ese proveedor, que tiene que ser de la ferreteria (si no, 404).
     */
    @Transactional(readOnly = true)
    public List<ImportacionResponse> listar(Long proveedorId) {
        Map<Long, String> nombres = new HashMap<>();
        if (proveedorId != null) {
            Proveedor proveedor = buscarProveedor(proveedorId);
            nombres.put(proveedor.getId(), proveedor.getNombre());
        } else {
            proveedorRepository.findAllScoped().forEach(proveedor -> nombres.put(proveedor.getId(), proveedor.getNombre()));
        }
        return importacionListaRepository.listarScoped(proveedorId).stream()
                .map(importacion -> ImportacionResponse.from(importacion, nombres.get(importacion.getProveedorId())))
                .toList();
    }

    /** La seleccion de filas sobre el plan: 400 si no se puede aplicar y 409 si la lista cambio. */
    private static PlanImportacion elegir(PlanImportacion plan, AplicarRequest request) {
        try {
            return SeleccionDeFilas.aplicar(plan, request.clavesElegidas(), request.conteosEsperados().toConteos());
        } catch (SeleccionInvalidaException e) {
            throw new BadRequestException(e.getMessage());
        } catch (ListaCambioException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    /**
     * Crea los articulos nuevos con los defaults de la importacion (codigo interno = codigo del proveedor, activo,
     * stock minimo 0, sin margen propio) y los datos de la fila: moneda, IVA, ajustes, unidad y codigo de barras. El
     * rubro es el de la columna de rubro o, si la fila no lo nombra o no existe, el elegido al importar. Cada alta usa el
     * contexto de SU rubro. Se guardan TODOS antes de armar su historial, que necesita los ids que genera la base.
     */
    private void crearNuevos(
            List<AltaPlaneada> altas, Rubro rubroElegido, Proveedor proveedor, Preparacion preparacion,
            Long importacionId, Long usuarioId, List<HistorialPrecio> historial) {
        Long rubroElegidoId = rubroElegido == null ? null : rubroElegido.getId();
        List<Articulo> nuevos = new ArrayList<>();
        List<CambioPrecio> cambios = new ArrayList<>();
        for (AltaPlaneada alta : altas) {
            Long rubroId = alta.rubroId() != null ? alta.rubroId() : rubroElegidoId;
            Articulo nuevo = new Articulo(TenantContext.requireFerreteriaId());
            Optional<CambioPrecio> cambio = nuevo.actualizar(new DatosArticulo(
                    alta.codigo(), alta.codigoBarras(), alta.codigo(), alta.descripcion(), rubroId, proveedor.getId(),
                    alta.costo(), null, alta.alicuotaIva(), alta.unidad(), BigDecimal.ZERO, null, true, alta.moneda()),
                    preparacion.contextoDeRubro(rubroId));
            nuevo.fijarAjustes(alta.ajustes());
            nuevo.registrarPrecioLista(alta.precioLista());
            nuevo.marcarImportacion(importacionId);
            nuevos.add(nuevo);
            cambios.add(cambio.orElseThrow());
        }
        articuloRepository.saveAll(nuevos);
        for (int i = 0; i < nuevos.size(); i++) {
            historial.add(HistorialPrecio.de(nuevos.get(i), cambios.get(i), OrigenPrecio.IMPORTACION, usuarioId));
        }
    }

    // ---- preparar ----

    /**
     * Todo lo que se lee, ANTES de mutar nada (asi Hibernate no hace flush a mitad del recorrido): el libro entero
     * (UNA lectura), los articulos del proveedor en una consulta, un contexto de precio por rubro y otro sin rubro
     * (con el dolar vigente de la ferreteria), los rubros por nombre, y los codigos internos y de barras ocupados. Las
     * bonificaciones son las de la solicitud. El tope de 20.000 filas cuenta las filas de datos de las hojas elegidas.
     */
    private Preparacion preparar(
            Proveedor proveedor, ArchivoLista archivo, MapeoLista mapeo, boolean listaIncluyeIva,
            Bonificaciones bonificaciones) {
        LecturaLista lectura = lector.leer(archivo.contenido());
        for (int hoja : mapeo.hojasElegidas()) {
            if (hoja >= lectura.hojas().size()) {
                throw new BadRequestException("La hoja elegida no existe en el archivo.");
            }
        }
        // Los textos del encabezado salen de la fila anterior a la primera de datos de la hoja de referencia.
        FilaLeida encabezado = lectura.filas().stream()
                .filter(fila -> fila.hoja() == mapeo.hoja() && mapeo.filaDesde() > 1
                        && fila.numero() == mapeo.filaDesde() - 1)
                .findFirst()
                .orElse(null);
        ZonaDeDatos.Resultado zona = ZonaDeDatos.clasificar(lectura, mapeo.conEncabezados(encabezado));
        if (zona.filas().isEmpty()) {
            throw new BadRequestException("No hay filas de datos desde la fila " + mapeo.filaDesde() + ".");
        }
        if (zona.filas().size() > LimitesDeLista.MAXIMO_FILAS_DE_DATOS) {
            throw LimitesDeLista.demasiadasFilas();
        }

        List<Articulo> articulos = articuloRepository.deProveedorScoped(proveedor.getId());
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        List<Rubro> rubros = rubroRepository.listarScoped();
        // El dolar vigente de la ferreteria: con el se calculan las filas en dolares (nunca el de la API en este momento).
        ContextosDePrecio contextos = ContextosDePrecio.armar(
                rubros, configuracion.margenDefectoPorcentaje(), configuracion.redondeoMultiplo(),
                dolarDePrecios.vigente().orElse(null));
        Map<String, Long> rubrosPorNombre = new HashMap<>();
        for (Rubro rubro : rubros) {
            rubrosPorNombre.putIfAbsent(NormalizadorCodigo.clave(rubro.getNombre()), rubro.getId());
        }

        // La base devuelve los codigos como estan guardados: se normalizan igual que los del archivo.
        Set<String> ocupados = new HashSet<>();
        for (String codigo : articuloRepository.codigosInternosOcupadosScoped(
                PlanificadorImportacion.codigosCandidatosDeLaZona(zona.filas(), mapeo))) {
            String clave = NormalizadorCodigo.clave(codigo);
            if (clave != null) {
                ocupados.add(clave);
            }
        }
        List<String> barrasCandidatas = PlanificadorImportacion.codigosDeBarras(zona.filas(), mapeo);
        Set<String> barrasOcupadas = barrasCandidatas.isEmpty()
                ? Set.of()
                : new HashSet<>(articuloRepository.codigosBarrasActivosOcupadosScoped(barrasCandidatas));

        PlanImportacion plan = PlanificadorImportacion.planificar(new EntradaPlan(
                lectura.filas(), mapeo, listaIncluyeIva, bonificaciones,
                articulos.stream().map(ArticuloVigente::de).toList(),
                contextos.porRubro(), contextos.sinRubro(), ocupados, proveedor.getMapeoLista(), encabezado,
                lectura.hojas(), dolarDePrecios.activa(), rubrosPorNombre, barrasOcupadas));
        return new Preparacion(plan, articulos, contextos, encabezado);
    }

    /** Lo que sale de preparar: el plan y lo necesario para aplicarlo sobre las entidades ya cargadas. */
    private record Preparacion(
            PlanImportacion plan,
            List<Articulo> articulos,
            ContextosDePrecio contextos,
            FilaLeida encabezado) {

        /** Contexto de precio de un articulo: el de su rubro, o el de los articulos sin rubro. */
        ContextoPrecio contextoDe(Articulo articulo) {
            return contextos.de(articulo.getRubroId());
        }

        ContextoPrecio contextoDeRubro(Long rubroId) {
            return contextos.de(rubroId);
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

    private static List<String> celdasComoTexto(FilaLeida fila) {
        return fila.celdas().stream().limit(LimitesDeLista.MAXIMO_COLUMNAS_DE_MUESTRA).map(Celda::texto).toList();
    }

    private static VistaPreviaResponse respuestaDe(PlanImportacion plan) {
        return new VistaPreviaResponse(
                plan.conteos(),
                plan.cambios(),
                plan.altas(),
                plan.errores(),
                plan.advertencias(),
                plan.noEstan().stream().map(ArticuloAusente::de).toList(),
                false,
                plan.cambiosDeMoneda(),
                plan.correcciones(),
                plan.sinCambio().stream().map(FilaSinCambio::de).toList());
    }
}
