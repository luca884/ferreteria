package com.ferreteria.clientes.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clientes (CL-003). Los lee, crea y edita el Vendedor y el Dueño (el mostrador da de alta al cliente en el momento);
 * desactivar y reactivar es solo del Dueño. Un cliente de otra ferretería da 404. El saldo y el límite de cuenta corriente
 * viajan solo en las respuestas del Dueño (CC-007, CC-019); el rol sale del JWT.
 */
@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @GetMapping
    public PageResponse<ClienteResponse> listar(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean activos, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthUser authUser) {
        boolean conCifras = veCifras(authUser);
        return PageResponse.from(clienteService.buscar(q, activos, page, size), c -> ClienteResponse.de(c, conCifras));
    }

    @GetMapping("/{id}")
    public ClienteResponse detalle(@PathVariable Long id, @AuthenticationPrincipal AuthUser authUser) {
        return ClienteResponse.de(clienteService.obtener(id), veCifras(authUser));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClienteResponse crear(@Valid @RequestBody ClienteRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return ClienteResponse.de(clienteService.crear(request.aDatos()), veCifras(authUser));
    }

    @PutMapping("/{id}")
    public ClienteResponse actualizar(@PathVariable Long id, @Valid @RequestBody ClienteRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        return ClienteResponse.de(clienteService.actualizar(id, request.aDatos()), veCifras(authUser));
    }

    @PatchMapping("/{id}/activo")
    @PreAuthorize("hasRole('DUENO')")
    public ClienteResponse cambiarActivo(@PathVariable Long id, @Valid @RequestBody CambiarActivoRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        return ClienteResponse.de(clienteService.cambiarActivo(id, request.activo()), veCifras(authUser));
    }

    private static boolean veCifras(AuthUser authUser) {
        return authUser.rol() == Rol.DUENO;
    }
}
