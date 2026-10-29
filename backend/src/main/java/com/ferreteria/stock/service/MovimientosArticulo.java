package com.ferreteria.stock.service;

import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.stock.repository.MovimientoStockRepository;
import com.ferreteria.stock.repository.MovimientoStockRepository.MovimientoListado;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Movimientos de stock de un articulo (G29, GDS-17), como el historico de GDS: el mas nuevo primero, cada uno con el
 * saldo que dejo y el comprobante que lo origino.
 */
@Service
public class MovimientosArticulo {

    static final int TAMANO_MAXIMO = 100;

    /** Un movimiento listado, con el saldo de stock que quedo despues de el y su comprobante ya armado. */
    public record Movimiento(
            Long id, LocalDateTime fecha, String tipo, BigDecimal cantidad, BigDecimal saldo, String comprobante,
            String usuario, String motivo) {
    }

    private final ArticulosParaVenta articulosParaVenta;
    private final MovimientoStockRepository movimientoRepository;

    public MovimientosArticulo(ArticulosParaVenta articulosParaVenta, MovimientoStockRepository movimientoRepository) {
        this.articulosParaVenta = articulosParaVenta;
        this.movimientoRepository = movimientoRepository;
    }

    /**
     * Una pagina de movimientos del articulo, con desde y hasta como dias completos (los dos opcionales). El saldo se
     * calcula hacia atras desde el stock de hoy, asi el ultimo movimiento muestra el stock actual aunque falte el stock
     * inicial. Un articulo ajeno o inexistente da 404.
     */
    @Transactional(readOnly = true)
    public Page<Movimiento> listar(Long articuloId, LocalDate desde, LocalDate hasta, int page, int size) {
        BigDecimal stockActual = articulosParaVenta.stockActual(articuloId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo no encontrado"));
        PageRequest pedido = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));
        Page<MovimientoListado> filas = movimientoRepository.deArticuloScoped(articuloId,
                desde == null ? null : desde.atStartOfDay(),
                hasta == null ? null : hasta.plusDays(1).atStartOfDay(),
                pedido);
        if (filas.isEmpty()) {
            return new PageImpl<>(List.of(), pedido, filas.getTotalElements());
        }
        MovimientoListado primero = filas.getContent().get(0);
        BigDecimal saldo = stockActual.subtract(
                movimientoRepository.sumaPosteriorScoped(articuloId, primero.getFecha(), primero.getId()));
        List<Movimiento> movimientos = new ArrayList<>();
        for (MovimientoListado fila : filas) {
            movimientos.add(new Movimiento(fila.getId(), fila.getFecha(), fila.getTipo(), fila.getCantidad(), saldo,
                    comprobante(fila), fila.getUsuario(), fila.getMotivo()));
            saldo = saldo.subtract(fila.getCantidad());
        }
        return new PageImpl<>(movimientos, pedido, filas.getTotalElements());
    }

    /**
     * Como lo nombra cada pantalla: «Venta N.º 12», «Nota de crédito N.º 13» o «Anulación N.º 14» (CU-08: la anulación
     * interna es la nota sin factura, con motivo), «Remito N.º 5» y la compra con el codigo de tipo de GDS y su proveedor
     * («FA 00003-00001234 · Bulonera Sur»). El ajuste no tiene comprobante.
     */
    static String comprobante(MovimientoListado fila) {
        if (fila.getVentaNumero() != null) {
            if ("NOTA_CREDITO".equals(fila.getVentaTipo())) {
                return (fila.getMotivoAnulacion() != null ? "Anulación N.º " : "Nota de crédito N.º ")
                        + fila.getVentaNumero();
            }
            return "Venta N.º " + fila.getVentaNumero();
        }
        if (fila.getRemitoNumero() != null) {
            return "Remito N.º " + fila.getRemitoNumero();
        }
        if (fila.getCompraNumero() != null) {
            String numero = "%s %05d-%08d".formatted(fila.getCompraTipo(), fila.getCompraPuntoVenta(),
                    fila.getCompraNumero());
            return fila.getProveedor() == null ? numero : numero + " · " + fila.getProveedor();
        }
        return null;
    }
}
