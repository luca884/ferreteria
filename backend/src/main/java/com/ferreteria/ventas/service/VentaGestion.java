package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import com.ferreteria.ventas.repository.RemitoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consulta de las ventas hechas (G60, GDS-15): listar y ver una para reimprimirla. Solo lectura: anular es la nota de
 * credito (CU-08, fase 5) y una venta no se borra. Una de otra ferreteria es indistinguible de una que no existe: 404.
 *
 * <p>CU-16 con la factura (cu-07-factura-mostrador S8 y S15): el listado suma el resumen fiscal de cada venta de la pagina y
 * busca por numero fiscal, y el detalle trae el documento fiscal para reimprimir la factura. Todo lo fiscal lo da
 * {@code facturacion} por su puerto: la busqueda le pide los ids de las ventas con ese numero y filtra por ellos, sin unir
 * tablas entre modulos.
 */
@Service
public class VentaGestion {

    static final int TAMANO_MAXIMO = 100;
    static final String MENSAJE_NO_ENCONTRADA = "Venta no encontrada";

    /** Una fila del listado: la venta, el nombre del usuario que la cobro y su resumen fiscal (null sin registro). */
    public record VentaListada(Comprobante venta, String usuario, ResumenFiscal fiscal) {}

    /** La venta con sus renglones y pagos, como al cobrarla, y el nombre del usuario que la cobro. */
    public record VentaConsultada(VentaRegistrada venta, String usuario) {}

    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final CajaRepository cajaRepository;
    private final RemitoRepository remitoRepository;
    private final FacturacionParaVenta facturacion;

    public VentaGestion(ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, CajaRepository cajaRepository, RemitoRepository remitoRepository,
            FacturacionParaVenta facturacion) {
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.cajaRepository = cajaRepository;
        this.remitoRepository = remitoRepository;
        this.facturacion = facturacion;
    }

    /**
     * Las mas nuevas primero. {@code desde} y {@code hasta} son dias, ambos inclusive; {@code cliente} busca el texto
     * dentro del nombre del cliente; {@code numeroFiscal} («12», «1-12» o «00001-00000012», S15) busca la factura con ese
     * numero en cualquier letra y ambiente (con otro formato, 400). Un filtro nulo o en blanco no filtra.
     */
    @Transactional(readOnly = true)
    public Page<VentaListada> listar(Long numero, String cliente, String numeroFiscal, LocalDate desde, LocalDate hasta,
            int page, int size) {
        Optional<NumeroFiscal> fiscal = NumeroFiscal.de(numeroFiscal);
        String clienteLike = cliente == null || cliente.isBlank() ? null : "%" + escapar(cliente.strip()) + "%";
        LocalDateTime desdeHora = desde == null ? null : desde.atStartOfDay();
        LocalDateTime hastaHora = hasta == null ? null : hasta.plusDays(1).atStartOfDay();
        PageRequest pagina = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));

        Page<Comprobante> ventas;
        if (fiscal.isPresent()) {
            List<Long> conEseNumero = facturacion.ventasConNumeroFiscal(fiscal.get().puntoVenta(), fiscal.get().numero());
            ventas = conEseNumero.isEmpty()
                    ? Page.empty(pagina)
                    : comprobanteRepository.buscarVentasEntreScoped(conEseNumero, numero, clienteLike, desdeHora,
                            hastaHora, pagina);
        } else {
            ventas = comprobanteRepository.buscarVentasScoped(numero, clienteLike, desdeHora, hastaHora, pagina);
        }
        Map<Long, String> nombres = cajaRepository.nombresScoped(
                ventas.stream().map(Comprobante::getUsuarioId).distinct().toList());
        Map<Long, ResumenFiscal> resumenes = facturacion.resumenes(ventas.map(Comprobante::getId).getContent());
        return ventas.map(v -> new VentaListada(v, nombres.get(v.getUsuarioId()), resumenes.get(v.getId())));
    }

    /** Con su documento fiscal si tiene registro (C1), para reimprimir la factura, y el numero de su remito. */
    @Transactional(readOnly = true)
    public VentaConsultada detalle(Long id) {
        Comprobante venta = comprobanteRepository.findScoped(id)
                .filter(c -> c.getTipo() == TipoComprobante.VENTA)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADA));
        String usuario = cajaRepository.nombresScoped(List.of(venta.getUsuarioId())).get(venta.getUsuarioId());
        Long remitoNumero = venta.getRemitoId() == null
                ? null
                : remitoRepository.findScoped(venta.getRemitoId()).map(Remito::getNumero).orElse(null);
        return new VentaConsultada(new VentaRegistrada(venta, itemRepository.deComprobanteScoped(id),
                pagoRepository.deComprobanteScoped(id), List.of(), false, false, facturacion.documento(id).orElse(null),
                remitoNumero), usuario);
    }

    /** Barra invertida, % y _ como texto: el escape de LIKE en MySQL es la barra invertida. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
