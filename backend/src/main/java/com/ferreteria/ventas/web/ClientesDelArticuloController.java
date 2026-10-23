package com.ferreteria.ventas.web;

import com.ferreteria.ventas.service.ClientesDelArticulo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Clientes que compraron un articulo (G29), solo el Dueño, como la consulta de ventas. Vive en el modulo ventas aunque la
 * ruta cuelgue del articulo. Un articulo ajeno da 404.
 */
@RestController
@RequestMapping("/api/articulos")
public class ClientesDelArticuloController {

    /** La pagina con la forma de siempre ({@code PageResponse}) mas las ventas sin cliente. */
    public record Respuesta(List<ClientesDelArticulo.Cliente> items, int page, int size, long totalElements,
                            int totalPages, long ventasSinCliente) {
    }

    private final ClientesDelArticulo clientesDelArticulo;

    public ClientesDelArticuloController(ClientesDelArticulo clientesDelArticulo) {
        this.clientesDelArticulo = clientesDelArticulo;
    }

    @GetMapping("/{id}/clientes")
    @PreAuthorize("hasRole('DUENO')")
    public Respuesta listar(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        ClientesDelArticulo.Resultado resultado = clientesDelArticulo.listar(id, page, size);
        var clientes = resultado.clientes();
        return new Respuesta(clientes.getContent(), clientes.getNumber(), clientes.getSize(),
                clientes.getTotalElements(), clientes.getTotalPages(), resultado.ventasSinCliente());
    }
}
