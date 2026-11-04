package com.ferreteria.catalogo.controller;

import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.catalogo.dto.AjustesRequest;
import com.ferreteria.catalogo.dto.ArticuloLectura;
import com.ferreteria.catalogo.dto.ArticuloRequest;
import com.ferreteria.catalogo.dto.ArticuloResponse;
import com.ferreteria.catalogo.dto.ArticuloVendedorResponse;
import com.ferreteria.catalogo.dto.BusquedaMostradorResponse;
import com.ferreteria.catalogo.dto.CodigoSiguienteResponse;
import com.ferreteria.catalogo.dto.DesglosePrecioResponse;
import com.ferreteria.catalogo.service.ArticuloService;
import com.ferreteria.catalogo.service.BusquedaMostradorService;
import com.ferreteria.catalogo.service.DesglosePrecioService;
import com.ferreteria.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura para Dueno y Vendedor (ambos ven costo y margen); las escrituras solo para el Dueno. La busqueda
 * del mostrador (/mostrador) tambien es para los dos, pero con un DTO fino: sin costo ni margen.
 */
@RestController
@RequestMapping("/api/articulos")
public class ArticuloController {

    private final ArticuloService articuloService;
    private final BusquedaMostradorService busquedaMostradorService;
    private final DesglosePrecioService desglosePrecioService;

    public ArticuloController(
            ArticuloService articuloService,
            BusquedaMostradorService busquedaMostradorService,
            DesglosePrecioService desglosePrecioService) {
        this.articuloService = articuloService;
        this.busquedaMostradorService = busquedaMostradorService;
        this.desglosePrecioService = desglosePrecioService;
    }

    /** Pagina de 20 por defecto (el servicio la acota entre 1 y 100), solo activos salvo incluirInactivos. */
    @GetMapping
    public PageResponse<ArticuloLectura> listar(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean incluirInactivos,
            @AuthenticationPrincipal AuthUser authUser) {
        PageResponse<ArticuloResponse> pagina = articuloService.listar(q, page, size, incluirInactivos);
        return new PageResponse<>(pagina.items().stream().map(a -> segunRol(a, authUser)).toList(),
                pagina.page(), pagina.size(), pagina.totalElements(), pagina.totalPages());
    }

    /** CU-06. La ruta literal le gana a /{id}. Un q ausente o en blanco da SIN_RESULTADOS, no el catalogo. */
    @GetMapping("/mostrador")
    public BusquedaMostradorResponse buscarEnMostrador(@RequestParam(required = false) String q) {
        return busquedaMostradorService.buscar(q);
    }

    /** Tambien devuelve los articulos dados de baja. */
    @GetMapping("/{id}")
    public ArticuloLectura obtener(@PathVariable Long id, @AuthenticationPrincipal AuthUser authUser) {
        return segunRol(articuloService.obtener(id), authUser);
    }

    /** El Dueno ve costo y margen; el Vendedor, el articulo sin nada de eso (H07). */
    private static ArticuloLectura segunRol(ArticuloResponse articulo, AuthUser authUser) {
        return authUser.veCostos() ? articulo : ArticuloVendedorResponse.from(articulo);
    }

    /** De donde sale el precio, paso a paso: costo, bonificaciones y margen, asi que solo el Dueno (H07). Uno ajeno da 404. */
    @GetMapping("/{id}/desglose")
    @PreAuthorize("hasRole('DUENO')")
    public DesglosePrecioResponse desglosar(@PathVariable Long id) {
        return desglosePrecioService.obtener(id);
    }

    /** El codigo interno sugerido para el alta (solo el Dueno, que es quien da de alta); null si no hay sugerencia. */
    @GetMapping("/codigo-siguiente")
    @PreAuthorize("hasRole('DUENO')")
    public CodigoSiguienteResponse codigoSiguiente() {
        return new CodigoSiguienteResponse(articuloService.codigoSiguiente());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public ArticuloResponse crear(@Valid @RequestBody ArticuloRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return articuloService.crear(request, authUser.userId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public ArticuloResponse actualizar(
            @PathVariable Long id, @Valid @RequestBody ArticuloRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return articuloService.actualizar(id, request, authUser.userId());
    }

    /**
     * Edita los descuentos y recargos del articulo (solo Dueno). Sin @Valid: el servicio valida despues del 404. No
     * recibe costo: el costo sale de la lista con la base guardada.
     */
    @PutMapping("/{id}/ajustes")
    @PreAuthorize("hasRole('DUENO')")
    public ArticuloResponse editarAjustes(
            @PathVariable Long id, @RequestBody AjustesRequest request, @AuthenticationPrincipal AuthUser authUser) {
        return articuloService.editarAjustes(id, request, authUser.userId());
    }

    /** Vuelve a activar un articulo dado de baja; no toca precios ni stock. */
    @PostMapping("/{id}/reactivacion")
    @PreAuthorize("hasRole('DUENO')")
    public ArticuloResponse reactivar(@PathVariable Long id) {
        return articuloService.reactivar(id);
    }

    /** Baja logica: el articulo queda inactivo y su historial de precios se conserva. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DUENO')")
    public void desactivar(@PathVariable Long id) {
        articuloService.desactivar(id);
    }
}
