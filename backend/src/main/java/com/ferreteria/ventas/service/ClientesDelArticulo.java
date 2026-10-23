package com.ferreteria.ventas.service;

import com.ferreteria.catalogo.api.ArticulosParaVenta;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.ventas.repository.ComprobanteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Clientes que compraron un articulo (G29), como el Ctrl+A del listado de articulos de GDS: una fila por cliente, la
 * compra mas nueva primero. Las ventas sin cliente (consumidor final) no tienen a quien llamar: solo se cuentan.
 */
@Service
public class ClientesDelArticulo {

    static final int TAMANO_MAXIMO = 100;

    public record Cliente(Long clienteId, String razonSocial, boolean activo, long compras, BigDecimal cantidad,
                          LocalDate ultimaCompra) {
    }

    /** Una pagina de clientes y cuantas ventas sin cliente tiene el articulo. */
    public record Resultado(Page<Cliente> clientes, long ventasSinCliente) {
    }

    private final ArticulosParaVenta articulosParaVenta;
    private final ComprobanteRepository comprobanteRepository;

    public ClientesDelArticulo(ArticulosParaVenta articulosParaVenta, ComprobanteRepository comprobanteRepository) {
        this.articulosParaVenta = articulosParaVenta;
        this.comprobanteRepository = comprobanteRepository;
    }

    /** Un articulo ajeno o inexistente da 404. */
    @Transactional(readOnly = true)
    public Resultado listar(Long articuloId, int page, int size) {
        if (articulosParaVenta.stockActual(articuloId).isEmpty()) {
            throw new ResourceNotFoundException("Artículo no encontrado");
        }
        PageRequest pedido = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO));
        Page<Cliente> clientes = comprobanteRepository.clientesDelArticuloScoped(articuloId, pedido)
                .map(c -> new Cliente(c.getClienteId(), c.getRazonSocial(), Boolean.TRUE.equals(c.getActivo()),
                        c.getCompras(), c.getCantidad(), LocalDate.parse(c.getUltimaCompra())));
        return new Resultado(clientes, comprobanteRepository.ventasSinClienteDelArticuloScoped(articuloId));
    }
}
