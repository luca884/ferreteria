package com.ferreteria.ventas.service;

import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.TipoComprobante;
import com.ferreteria.ventas.repository.CajaRepository;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import com.ferreteria.ventas.repository.ItemComprobanteRepository;
import com.ferreteria.ventas.repository.PagoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Consulta de las ventas hechas (G60, GDS-15): listar y ver una para reimprimirla. Solo lectura: anular es la nota de
 * credito (CU-08, fase 5) y una venta no se borra. Una de otra ferreteria es indistinguible de una que no existe: 404.
 */
@Service
public class VentaGestion {

    static final int TAMANO_MAXIMO = 100;
    static final String MENSAJE_NO_ENCONTRADA = "Venta no encontrada";

    /** Una fila del listado: la venta y el nombre del usuario que la cobro. */
    public record VentaListada(Comprobante venta, String usuario) {}

    /** La venta con sus renglones y pagos, como al cobrarla, y el nombre del usuario que la cobro. */
    public record VentaConsultada(VentaRegistrada venta, String usuario) {}

    private final ComprobanteRepository comprobanteRepository;
    private final ItemComprobanteRepository itemRepository;
    private final PagoRepository pagoRepository;
    private final CajaRepository cajaRepository;

    public VentaGestion(ComprobanteRepository comprobanteRepository, ItemComprobanteRepository itemRepository,
            PagoRepository pagoRepository, CajaRepository cajaRepository) {
        this.comprobanteRepository = comprobanteRepository;
        this.itemRepository = itemRepository;
        this.pagoRepository = pagoRepository;
        this.cajaRepository = cajaRepository;
    }

    /**
     * Las mas nuevas primero. {@code desde} y {@code hasta} son dias, ambos inclusive; {@code cliente} busca el texto
     * dentro del nombre del cliente. Un filtro nulo o en blanco no filtra.
     */
    @Transactional(readOnly = true)
    public Page<VentaListada> listar(
            Long numero, String cliente, LocalDate desde, LocalDate hasta, int page, int size) {
        Page<Comprobante> ventas = comprobanteRepository.buscarVentasScoped(numero,
                cliente == null || cliente.isBlank() ? null : "%" + escapar(cliente.strip()) + "%",
                desde == null ? null : desde.atStartOfDay(),
                hasta == null ? null : hasta.plusDays(1).atStartOfDay(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO)));
        Map<Long, String> nombres = cajaRepository.nombresScoped(
                ventas.stream().map(Comprobante::getUsuarioId).distinct().toList());
        return ventas.map(v -> new VentaListada(v, nombres.get(v.getUsuarioId())));
    }

    @Transactional(readOnly = true)
    public VentaConsultada detalle(Long id) {
        Comprobante venta = comprobanteRepository.findScoped(id)
                .filter(c -> c.getTipo() == TipoComprobante.VENTA)
                .orElseThrow(() -> new ResourceNotFoundException(MENSAJE_NO_ENCONTRADA));
        String usuario = cajaRepository.nombresScoped(List.of(venta.getUsuarioId())).get(venta.getUsuarioId());
        return new VentaConsultada(new VentaRegistrada(venta, itemRepository.deComprobanteScoped(id),
                pagoRepository.deComprobanteScoped(id), List.of(), false), usuario);
    }

    /** Barra invertida, % y _ como texto: el escape de LIKE en MySQL es la barra invertida. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
