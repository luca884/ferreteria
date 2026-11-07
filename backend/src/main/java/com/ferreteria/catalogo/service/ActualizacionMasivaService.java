package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.ActualizacionMasivaResponse;
import com.ferreteria.catalogo.dto.AplicarActualizacionMasivaRequest;
import com.ferreteria.catalogo.dto.PedidoMasivo;
import com.ferreteria.catalogo.dto.ValidacionActualizacionMasiva;
import com.ferreteria.catalogo.dto.VistaPreviaMasivaResponse;
import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.ArticuloVigente;
import com.ferreteria.catalogo.model.CambioMasivo;
import com.ferreteria.catalogo.model.CambioPrecio;
import com.ferreteria.catalogo.model.ContextoPrecio;
import com.ferreteria.catalogo.model.ContextosDePrecio;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.model.ModoMasivo;
import com.ferreteria.catalogo.model.Moneda;
import com.ferreteria.catalogo.model.OperacionMasiva;
import com.ferreteria.catalogo.model.ValorMasivoInvalidoException;
import com.ferreteria.catalogo.model.OrigenPrecio;
import com.ferreteria.catalogo.model.PlanMasivo;
import com.ferreteria.catalogo.model.PlanificadorMasivo;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;
import com.ferreteria.catalogo.repository.ProveedorRepository;
import com.ferreteria.catalogo.repository.RubroRepository;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ErroresDeValidacion;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Actualizacion masiva de precios (CU-05). La vista previa y aplicar arman el plan con el mismo
 * camino ({@code preparar} mas el planificador puro); aplicar ademas compara la huella con la de la
 * vista previa y recien entonces escribe, todo en una transaccion.
 */
@Service
public class ActualizacionMasivaService {

    /** Tope de articulos por operacion. Mismo valor que el de filas de una lista (CU-04), pero propio. */
    public static final int MAXIMO_ARTICULOS = 20_000;

    /** La deriva: el alcance cambio entre la vista previa y el aplicar (huella distinta o un choque de version). */
    public static final String DERIVA = "Los artículos cambiaron desde la vista previa. "
            + "Volvé a generar la vista previa y confirmá de nuevo.";

    static final int MAXIMO_DETALLE = 1_000;

    private final ArticuloRepository articuloRepository;
    private final ProveedorRepository proveedorRepository;
    private final RubroRepository rubroRepository;
    private final HistorialPrecioRepository historialPrecioRepository;
    private final ConfiguracionPreciosService configuracionPreciosService;
    private final Validator validator;
    private final DolarDePrecios dolarDePrecios;
    private final ImportacionListaRepository importacionListaRepository;

    public ActualizacionMasivaService(
            ArticuloRepository articuloRepository,
            ProveedorRepository proveedorRepository,
            RubroRepository rubroRepository,
            HistorialPrecioRepository historialPrecioRepository,
            ConfiguracionPreciosService configuracionPreciosService,
            Validator validator,
            DolarDePrecios dolarDePrecios,
            ImportacionListaRepository importacionListaRepository) {
        this.articuloRepository = articuloRepository;
        this.proveedorRepository = proveedorRepository;
        this.rubroRepository = rubroRepository;
        this.historialPrecioRepository = historialPrecioRepository;
        this.configuracionPreciosService = configuracionPreciosService;
        this.validator = validator;
        this.dolarDePrecios = dolarDePrecios;
        this.importacionListaRepository = importacionListaRepository;
    }

    /** Lo que pasaria al aplicar. No escribe nada: ni articulos ni historial. */
    @Transactional(readOnly = true)
    public VistaPreviaMasivaResponse vistaPrevia(ActualizacionMasivaRequest request) {
        PlanMasivo plan = preparar(request, false).plan();
        boolean recortado = plan.cambios().size() > MAXIMO_DETALLE || plan.errores().size() > MAXIMO_DETALLE;
        return new VistaPreviaMasivaResponse(
                plan.conteos(), recortar(plan.cambios()), recortar(plan.errores()), recortado, plan.huella(),
                plan.enPesos(), plan.enDolares(), plan.advertenciaMezcla());
    }

    /**
     * Aplica la operacion en UNA transaccion. Primero lee y planifica; si la huella no coincide con la de
     * la vista previa (deriva) responde 409 SIN haber escrito nada. Recien despues cambia SOLO los
     * articulos modificados (ya administrados por JPA, se escriben al terminar la transaccion) y guarda
     * una entrada de historial MASIVO por cada uno cuyo costo o precio cambia (al cambiar el rubro, uno que solo cambia
     * de rubro se escribe sin historial). Cualquier falla revierte todo.
     */
    @Transactional
    public ActualizacionMasivaResponse aplicar(AplicarActualizacionMasivaRequest request, Long usuarioId) {
        Preparacion preparacion = preparar(request, true);
        PlanMasivo plan = preparacion.plan();

        // Deriva: se compara ANTES de la primera escritura.
        if (!plan.huella().equals(request.huella())) {
            throw new ConflictException(DERIVA);
        }

        Map<Long, Articulo> articulosPorId = new HashMap<>();
        preparacion.articulos().forEach(articulo -> articulosPorId.put(articulo.getId(), articulo));

        OperacionMasiva operacion = preparacion.operacion();
        List<HistorialPrecio> historial = new ArrayList<>();
        for (CambioMasivo cambio : plan.cambios()) {
            Articulo articulo = articulosPorId.get(cambio.articuloId());
            ContextoPrecio contexto = preparacion.contextos().de(articulo.getRubroId());
            Optional<CambioPrecio> cambioDePrecio = switch (operacion.modo()) {
                case MARGEN -> articulo.fijarMargen(operacion.valor(), contexto);
                // CU-14: el precio de la vista previa queda escrito a mano.
                case PRECIO_PORCENTAJE, PRECIO_MONTO -> articulo.fijarPrecioManual(cambio.precioNuevo());
                case COSTO_PORCENTAJE, COSTO_MONTO -> articulo.ajustarCosto(cambio.costoNuevo(), contexto);
                // El precio sale del contexto del destino; historial solo si cambia el precio.
                case CAMBIAR_RUBRO -> articulo.cambiarRubro(
                        operacion.rubroDestinoId(), preparacion.contextos().de(operacion.rubroDestinoId()));
            };
            // El planificador y la entidad calculan con el mismo PrecioCalculator: si difieren hay un bug
            // y se revierte todo (500) antes de que se escriba el historial.
            if (articulo.getPrecioFinal().compareTo(cambio.precioNuevo()) != 0) {
                throw new IllegalStateException("El precio del artículo " + articulo.getId()
                        + " no coincide con el de la vista previa.");
            }
            cambioDePrecio.ifPresent(c -> historial.add(
                    HistorialPrecio.de(articulo, c, OrigenPrecio.MASIVO, usuarioId)));
        }

        if (!historial.isEmpty()) {
            historialPrecioRepository.saveAll(historial);
        }
        escribirOChocar();
        return new ActualizacionMasivaResponse(plan.conteos(), historial.size());
    }

    /**
     * Escribe los articulos ahora: si otra escritura confirmo un cambio en uno de los que este masivo modifica (bloqueo
     * optimista, R-227), los articulos cambiaron y se responde 409 con el texto de la deriva. La excepcion revierte la
     * transaccion entera: ni articulos ni historial.
     */
    private void escribirOChocar() {
        try {
            articuloRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(DERIVA);
        }
    }

    // ---- preparar ----

    /**
     * Resuelve y valida en el orden de R-002 (404 del proveedor, del rubro, de la importacion y del rubro destino, 400 del
     * modo y el valor, 400 del tope contando ANTES de cargar, y al aplicar 400 si el alcance esta vacio), lee todo UNA vez
     * (alcance, rubros, configuracion) y planifica.
     */
    private Preparacion preparar(PedidoMasivo pedido, boolean esAplicar) {
        if (pedido.proveedorId() != null) {
            proveedorRepository.findScoped(pedido.proveedorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Proveedor no encontrado"));
        }
        if (pedido.rubroId() != null) {
            rubroRepository.findScoped(pedido.rubroId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
        }
        if (pedido.importacionId() != null) {
            importacionListaRepository.findScoped(pedido.importacionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Importación no encontrada"));
        }
        // CAMBIAR_RUBRO: un destino de otra ferreteria da 404, igual que uno inexistente, antes de validar.
        if (pedido.rubroDestinoId() != null) {
            rubroRepository.findScoped(pedido.rubroDestinoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Rubro no encontrado"));
        }
        validar(pedido);
        FiltroArticulos filtro = pedido.toFiltro();
        OperacionMasiva operacion = operacionDe(pedido);

        long alcance = articuloRepository.contarScoped(filtro);
        if (alcance > MAXIMO_ARTICULOS) {
            throw new BadRequestException("El filtro abarca " + entero(alcance) + " artículos y el máximo es "
                    + entero(MAXIMO_ARTICULOS) + ". Afiná el filtro.");
        }
        if (esAplicar && alcance == 0) {
            throw new BadRequestException("No hay artículos para actualizar con ese filtro.");
        }

        List<Articulo> articulos = articuloRepository.filtrarScoped(filtro);
        ConfiguracionPrecios configuracion = configuracionPreciosService.actual();
        ContextosDePrecio contextos = ContextosDePrecio.armar(
                rubroRepository.listarScoped(), configuracion.margenDefectoPorcentaje(), configuracion.redondeoMultiplo(),
                dolarVigenteSiHayArticulosEnDolares(articulos));
        PlanMasivo plan = PlanificadorMasivo.planificar(
                articulos.stream().map(ArticuloVigente::de).toList(), filtro, operacion, contextos);
        return new Preparacion(plan, articulos, contextos, operacion);
    }

    /**
     * El dolar vigente de la ferreteria (no el valor de la API en ese momento), solo si el alcance tiene algun articulo
     * en USD: con todo en pesos no se consulta. Sin ninguna cotizacion guardada no se puede calcular su precio: 409.
     */
    private BigDecimal dolarVigenteSiHayArticulosEnDolares(List<Articulo> articulos) {
        if (articulos.stream().noneMatch(articulo -> articulo.getMoneda() == Moneda.USD)) {
            return null;
        }
        return dolarDePrecios.vigente().orElseThrow(() -> new ConflictException(DolarDePrecios.SIN_COTIZACION));
    }

    /** Lo que sale de preparar: el plan y lo necesario para aplicarlo sobre las entidades ya cargadas. */
    private record Preparacion(
            PlanMasivo plan,
            List<Articulo> articulos,
            ContextosDePrecio contextos,
            OperacionMasiva operacion) {
    }

    // ---- apoyo ----

    /**
     * Valida el pedido con el grupo {@link ValidacionActualizacionMasiva}. Va DESPUES de buscar el
     * proveedor y el rubro: un recurso ajeno responde 404 antes que un 400 por datos invalidos (R-002).
     * El formato del error es el de la validacion del cuerpo: un mensaje por campo.
     */
    private void validar(Object pedido) {
        Set<ConstraintViolation<Object>> violaciones = validator.validate(pedido, ValidacionActualizacionMasiva.class);
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

    /**
     * La operacion del pedido ya validado. Si el dominio igual rechaza el valor (cero o decimales de mas) es un
     * 400 con su mensaje, nunca un 500.
     */
    private static OperacionMasiva operacionDe(PedidoMasivo pedido) {
        try {
            return pedido.toOperacion();
        } catch (ValorMasivoInvalidoException e) {
            throw new BadRequestException(e.getMessage());
        } catch (ArithmeticException e) {
            throw new BadRequestException("El valor puede tener como máximo 2 decimales.");
        }
    }

    /** Las primeras {@link #MAXIMO_DETALLE} filas (las de id mas bajo, que el plan ya trae ordenadas). */
    private static <T> List<T> recortar(List<T> lista) {
        return lista.size() <= MAXIMO_DETALLE ? lista : List.copyOf(lista.subList(0, MAXIMO_DETALLE));
    }

    /** Entero con el punto como separador de miles, como se escribe en Argentina: 20.001. */
    private static String entero(long numero) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        return new DecimalFormat("#,##0", simbolos).format(numero);
    }
}
