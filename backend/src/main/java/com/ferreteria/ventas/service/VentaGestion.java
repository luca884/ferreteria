package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.AccionFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Bandeja;
import com.ferreteria.facturacion.api.FacturacionParaVenta.FacturaConsultada;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.PresupuestoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Consulta de las ventas hechas (G60, GDS-15): listar y ver una para reimprimirla. Solo lectura: anular es la nota de
 * credito (CU-08, fase 5) y una venta no se borra. Una de otra ferreteria es indistinguible de una que no existe: 404.
 *
 * <p>CU-16 con la factura (cu-07-factura-mostrador S8 y S15): el listado suma el resumen fiscal de cada venta de la pagina y
 * busca por numero fiscal, y el detalle trae el documento fiscal para reimprimir la factura. Todo lo fiscal lo da
 * {@code facturacion} por su puerto: la busqueda le pide los ids de las ventas con ese numero y filtra por ellos, sin unir
 * tablas entre modulos.
 *
 * <p>La bandeja (cu-07-contingencias C1) es un filtro mas, con los ids que da {@code facturacion}, y el detalle suma lo que
 * dijo ARCA y las acciones del Dueño. «Llego el CAE» (C7) lista las ventas que el usuario cobro en la caja abierta y
 * salieron con ticket provisorio; el Vendedor pide la hoja solo de una de esas (cualquier otra es 404), asi el cierre de
 * caja sigue ciego.
 */
@Service
public class VentaGestion {

    static final int TAMANO_MAXIMO = 100;
    static final String MENSAJE_NO_ENCONTRADA = "Venta no encontrada";

    /**
     * Una fila del listado: la venta (o la nota de credito, CU-08 S14), el nombre del usuario que la cobro (o la hizo) y su
     * resumen fiscal (null sin registro). Una venta anulada lleva su {@code anulacion}; una nota, la {@code ventaAnulada}.
     * Lo que no corresponde es null.
     */
    public record VentaListada(Comprobante venta, String usuario, ResumenFiscal fiscal, Anulacion anulacion,
            VentaAnulada ventaAnulada) {

        public VentaListada(Comprobante venta, String usuario, ResumenFiscal fiscal) {
            this(venta, usuario, fiscal, null, null);
        }
    }

    /**
     * Como se anulo una venta (CU-08, C4): la nota, su numero, si es la anulacion interna (con motivo) o la NC, quien y
     * cuando.
     */
    public record Anulacion(long notaId, long numero, boolean interna, String motivo, String usuario,
            LocalDateTime fecha) {}

    /** La venta que anula una nota de credito. */
    public record VentaAnulada(long id, long numero) {}

    /**
     * La venta con sus renglones y pagos, como al cobrarla, el nombre del usuario que la cobro y su factura como la ve el
     * detalle (null si no tiene registro fiscal). Como en el listado (CU-08, C4): una venta anulada lleva su
     * {@code anulacion} y una nota de credito, la {@code ventaAnulada}; lo que no corresponde es null.
     */
    public record VentaConsultada(VentaRegistrada venta, String usuario, FiscalConsultado fiscal, Anulacion anulacion,
            VentaAnulada ventaAnulada, Long presupuestoNumero) {

        public VentaConsultada(VentaRegistrada venta, String usuario, FiscalConsultado fiscal) {
            this(venta, usuario, fiscal, null, null, null);
        }
    }

    /** La factura de la venta en el detalle (C1) y el nombre de quien la dejo sin factura (null si nadie). */
    public record FiscalConsultado(FacturaConsultada factura, String sinFacturaPor) {}

    /** Una venta de la caja abierta a la que le llego el CAE (C7), con el texto de su factura. */
    public record ProvisoriaAutorizada(long ventaId, long numero, String factura) {}

    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final CajaRepository cajaRepository;
    private final RemitoRepository remitoRepository;
    private final PresupuestoRepository presupuestoRepository;
    private final FacturacionParaVenta facturacion;

    public VentaGestion(ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, CajaRepository cajaRepository, RemitoRepository remitoRepository,
            PresupuestoRepository presupuestoRepository, FacturacionParaVenta facturacion) {
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.cajaRepository = cajaRepository;
        this.remitoRepository = remitoRepository;
        this.presupuestoRepository = presupuestoRepository;
        this.facturacion = facturacion;
    }

    /**
     * Las mas nuevas primero. {@code desde} y {@code hasta} son dias, ambos inclusive; {@code cliente} busca el texto
     * dentro del nombre del cliente; {@code numeroFiscal} («12», «1-12» o «00001-00000012», S15) busca la factura con ese
     * numero en cualquier letra y ambiente (con otro formato, 400); {@code bandeja} (C1) deja solo las pendientes o las
     * rechazadas. Un filtro nulo o en blanco no filtra.
     */
    @Transactional(readOnly = true)
    public Page<VentaListada> listar(Long numero, String cliente, String numeroFiscal, Bandeja bandeja, LocalDate desde,
            LocalDate hasta, int page, int size) {
        Optional<NumeroFiscal> fiscal = NumeroFiscal.de(numeroFiscal);
        String clienteLike = cliente == null || cliente.isBlank() ? null : "%" + escapar(cliente.strip()) + "%";
        LocalDateTime desdeHora = desde == null ? null : desde.atStartOfDay();
        LocalDateTime hastaHora = hasta == null ? null : hasta.plusDays(1).atStartOfDay();
        PageRequest pagina = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));

        // Los filtros fiscales los resuelve facturacion: los ids de las ventas que cumplen cada uno, y entre ellas se busca.
        Set<Long> entre = null;
        if (fiscal.isPresent()) {
            entre = new HashSet<>(facturacion.ventasConNumeroFiscal(fiscal.get().puntoVenta(), fiscal.get().numero()));
        }
        if (bandeja != null) {
            List<Long> enLaBandeja = facturacion.ventasEnBandeja(bandeja);
            if (entre == null) {
                entre = new HashSet<>(enLaBandeja);
            } else {
                entre.retainAll(enLaBandeja);
            }
        }
        Page<Comprobante> ventas;
        if (entre == null) {
            ventas = comprobanteRepository.buscarVentasScoped(numero, clienteLike, desdeHora, hastaHora, pagina);
        } else {
            ventas = entre.isEmpty()
                    ? Page.empty(pagina)
                    : comprobanteRepository.buscarVentasEntreScoped(entre, numero, clienteLike, desdeHora, hastaHora,
                            pagina);
        }
        // La anulacion de cada venta de la pagina y la venta de cada nota (CU-08, C4), en dos lecturas.
        Map<Long, Comprobante> notaDe = new HashMap<>();
        comprobanteRepository.notasDeScoped(ventas.stream().filter(c -> c.getTipo() == TipoComprobante.VENTA)
                .map(Comprobante::getId).toList()).forEach(n -> notaDe.put(n.getComprobanteAsociadoId(), n));
        Map<Long, Long> numeroDe = new HashMap<>();
        comprobanteRepository.numerosDeScoped(ventas.stream().map(Comprobante::getComprobanteAsociadoId)
                .filter(Objects::nonNull).distinct().toList()).forEach(f -> numeroDe.put((Long) f[0], (Long) f[1]));
        Map<Long, String> nombres = cajaRepository.nombresScoped(Stream.concat(ventas.stream(), notaDe.values().stream())
                .map(Comprobante::getUsuarioId).distinct().toList());
        Map<Long, ResumenFiscal> resumenes = facturacion.resumenes(ventas.map(Comprobante::getId).getContent());
        return ventas.map(v -> {
            Comprobante nota = notaDe.get(v.getId());
            Anulacion anulacion = nota == null ? null : new Anulacion(nota.getId(), nota.getNumero(),
                    nota.esAnulacionInterna(), nota.getMotivoAnulacion(), nombres.get(nota.getUsuarioId()),
                    nota.getFecha());
            VentaAnulada ventaAnulada = v.getComprobanteAsociadoId() == null ? null
                    : new VentaAnulada(v.getComprobanteAsociadoId(), numeroDe.get(v.getComprobanteAsociadoId()));
            return new VentaListada(v, nombres.get(v.getUsuarioId()), resumenes.get(v.getId()), anulacion,
                    ventaAnulada);
        });
    }

    /**
     * Con su factura si tiene registro (C1 de cu-07-factura-mostrador y de cu-07-contingencias), para reimprimirla y para
     * la bandeja, y el numero de su remito. Tambien el de una nota de credito (CU-08, S14: desde su detalle se reimprime).
     */
    @Transactional(readOnly = true)
    public VentaConsultada detalle(Long id) {
        Comprobante venta = ventaONota(id);
        String usuario = cajaRepository.nombresScoped(List.of(venta.getUsuarioId())).get(venta.getUsuarioId());
        Long remitoNumero = venta.getRemitoId() == null
                ? null
                : remitoRepository.findScoped(venta.getRemitoId()).map(Remito::getNumero).orElse(null);
        // «Salió del presupuesto N.º n» (informacion-y-links, S2): como el remito, el de la ferreteria del request.
        Long presupuestoNumero = venta.getPresupuestoId() == null
                ? null
                : presupuestoRepository.findScoped(venta.getPresupuestoId()).map(Presupuesto::getNumero).orElse(null);
        FiscalConsultado fiscal = fiscalDe(venta).orElse(null);
        return new VentaConsultada(new VentaRegistrada(venta, itemRepository.deComprobanteScoped(id),
                pagoRepository.deComprobanteScoped(id), List.of(), false, false,
                fiscal == null ? null : fiscal.factura().documento(), remitoNumero), usuario, fiscal,
                anulacionDe(venta), ventaAnuladaDe(venta), presupuestoNumero);
    }

    /** Como se anulo la venta (CU-08, C4), o null si no esta anulada o es una nota. */
    private Anulacion anulacionDe(Comprobante venta) {
        if (venta.getTipo() != TipoComprobante.VENTA) {
            return null;
        }
        return comprobanteRepository.notasDeScoped(List.of(venta.getId())).stream().findFirst()
                .map(nota -> new Anulacion(nota.getId(), nota.getNumero(), nota.esAnulacionInterna(),
                        nota.getMotivoAnulacion(),
                        cajaRepository.nombresScoped(List.of(nota.getUsuarioId())).get(nota.getUsuarioId()),
                        nota.getFecha()))
                .orElse(null);
    }

    /** La venta que anula una nota de credito, o null en una venta. */
    private VentaAnulada ventaAnuladaDe(Comprobante nota) {
        Long ventaId = nota.getComprobanteAsociadoId();
        if (ventaId == null) {
            return null;
        }
        return comprobanteRepository.numerosDeScoped(List.of(ventaId)).stream().findFirst()
                .map(fila -> new VentaAnulada(ventaId, (Long) fila[1]))
                .orElse(null);
    }

    /**
     * La hoja de una venta para reimprimirla cuando llego su CAE (C7): lo mismo que {@link #detalle}. El Dueño la pide de
     * cualquier venta; el Vendedor, solo de una de {@link #provisoriasAutorizadas}: cualquier otra, aunque exista, es 404.
     */
    @Transactional(readOnly = true)
    public VentaConsultada factura(Long id, Long usuarioId, boolean esDueno) {
        if (!esDueno && provisoriasAutorizadas(usuarioId).stream().noneMatch(p -> p.ventaId() == id)) {
            throw new ResourceNotFoundException(MENSAJE_NO_ENCONTRADA);
        }
        return detalle(id);
    }

    /**
     * Las ventas que el usuario cobro en la caja abierta (de hoy o de otro dia) que salieron con el ticket provisorio y ya
     * estan AUTORIZADO (C7), por numero. Sin caja abierta, ninguna.
     */
    @Transactional(readOnly = true)
    public List<ProvisoriaAutorizada> provisoriasAutorizadas(Long usuarioId) {
        Optional<Caja> caja = cajaRepository.abiertaScoped();
        if (caja.isEmpty()) {
            return List.of();
        }
        List<Object[]> cobradas = comprobanteRepository.ventasDelUsuarioEnLaCajaScoped(caja.get().getId(), usuarioId);
        Map<Long, String> facturas = facturacion.provisoriasAutorizadas(
                cobradas.stream().map(fila -> (Long) fila[0]).toList());
        return cobradas.stream()
                .filter(fila -> facturas.containsKey((Long) fila[0]))
                .map(fila -> new ProvisoriaAutorizada((Long) fila[0], (Long) fila[1], facturas.get((Long) fila[0])))
                .toList();
    }

    /**
     * La factura de la venta (o de la nota de credito) como la ve el detalle, con el nombre de quien la dejo sin factura.
     * Vacio sin registro. Una nota de credito rechazada solo se reintenta (CU-08 punto 2): «Dejar sin factura» no vale
     * sobre una NC, asi que no se ofrece.
     */
    @Transactional(readOnly = true)
    public Optional<FiscalConsultado> fiscalDe(Long id) {
        return fiscalDe(ventaONota(id));
    }

    private Optional<FiscalConsultado> fiscalDe(Comprobante comprobante) {
        boolean esNota = comprobante.getTipo() == TipoComprobante.NOTA_CREDITO;
        return facturacion.consulta(comprobante.getId()).map(factura -> esNota ? sinDejarSinFactura(factura) : factura)
                .map(factura -> new FiscalConsultado(factura,
                factura.usuarioSinFacturaId() == null ? null
                        : cajaRepository.nombresScoped(List.of(factura.usuarioSinFacturaId()))
                                .get(factura.usuarioSinFacturaId())));
    }

    /**
     * Una venta de la ferreteria del request; otra cosa (una nota de credito, o de otra ferreteria) es 404 «Venta no
     * encontrada». Es la de las acciones sobre la factura de la venta («Reintentar», «Dejar sin factura»).
     */
    @Transactional(readOnly = true)
    public Comprobante venta(Long id) {
        return comprobanteRepository.findScoped(id)
                .filter(c -> c.getTipo() == TipoComprobante.VENTA)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADA));
    }

    /**
     * Una venta o una nota de credito de la ferreteria del request (CU-08, S14: la lista y el detalle las muestran); otra
     * cosa (o de otra ferreteria) es 404 «Venta no encontrada».
     */
    @Transactional(readOnly = true)
    public Comprobante ventaONota(Long id) {
        return comprobanteRepository.findScoped(id)
                .filter(c -> c.getTipo() == TipoComprobante.VENTA || c.getTipo() == TipoComprobante.NOTA_CREDITO)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADA));
    }

    /** La misma factura sin la accion «Dejar sin factura». */
    private static FacturaConsultada sinDejarSinFactura(FacturaConsultada f) {
        return new FacturaConsultada(f.documento(), f.errores(), f.observaciones(), f.motivoSinFactura(),
                f.usuarioSinFacturaId(), f.sinFacturaEn(),
                f.acciones().stream().filter(a -> a != AccionFiscal.DEJAR_SIN_FACTURA).toList(), f.fechaNuevaRequerida(),
                f.motivoFueraDeFecha(), f.reimprimir());
    }

    /** Barra invertida, % y _ como texto: el escape de LIKE en MySQL es la barra invertida. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
