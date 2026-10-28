package com.ferreteria.plataforma.web;

import jakarta.validation.Valid;
import com.ferreteria.plataforma.service.FerreteriasAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ferreterías de la plataforma: las gestiona solo el super admin. El 403 de cualquier otro rol sale antes de leer el cuerpo
 * (SecurityConfig). El super admin no ve ni toca datos de ventas. Razón social, CUIT y condición de IVA los carga en el
 * alta; después los edita solo el Dueño (CU-01, /api/datos-fiscales). La marca «de prueba», el simulador y la facturación
 * habilitada (CU-01 paso 2) los cambia solo el super admin.
 */
@RestController
@RequestMapping("/api/admin/ferreterias")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class FerreteriasAdminController {

    private final FerreteriasAdminService servicio;

    public FerreteriasAdminController(FerreteriasAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public EstadoGeneralResponse estadoGeneral() {
        return EstadoGeneralResponse.de(servicio.listar());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FerreteriaAdminResponse alta(@Valid @RequestBody AltaFerreteriaRequest request) {
        return FerreteriaAdminResponse.de(servicio.alta(request.aDatos()));
    }

    @PatchMapping("/{id}/activo")
    public FerreteriaAdminResponse cambiarActivo(@PathVariable Long id, @Valid @RequestBody CambiarActivoRequest request) {
        return FerreteriaAdminResponse.de(servicio.cambiarActivo(id, request.activo()));
    }

    /** La marca «de prueba» (CU-01, «Ambiente de ARCA»): no se saca mientras la ferretería use el simulador. */
    @PatchMapping("/{id}/de-prueba")
    public FerreteriaAdminResponse cambiarDePrueba(@PathVariable Long id,
            @Valid @RequestBody CambiarDePruebaRequest request) {
        return FerreteriaAdminResponse.de(servicio.cambiarDePrueba(id, request.dePrueba()));
    }

    /**
     * Asigna el simulador (solo a una ferretería de prueba, sin registros de homologación o producción ni abiertos; punto
     * de venta 1) o lo quita (con la facturación apagada y sin registros abiertos). Una regla rota da 422.
     */
    @PatchMapping("/{id}/simulador")
    public FerreteriaAdminResponse cambiarSimulador(@PathVariable Long id,
            @Valid @RequestBody CambiarSimuladorRequest request) {
        return FerreteriaAdminResponse.de(servicio.cambiarSimulador(id, request.simulador()));
    }

    /** Prende la facturación (con ambiente y los datos de CU-01 completos; si no, 422) o la apaga. */
    @PatchMapping("/{id}/facturacion")
    public FerreteriaAdminResponse cambiarFacturacion(@PathVariable Long id,
            @Valid @RequestBody CambiarFacturacionRequest request) {
        return FerreteriaAdminResponse.de(servicio.cambiarFacturacionHabilitada(id, request.habilitada()));
    }

    @PutMapping("/{id}/duenos/{usuarioId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetearContrasenaDelDueno(@PathVariable Long id, @PathVariable Long usuarioId,
            @Valid @RequestBody ResetContrasenaRequest request) {
        servicio.resetearContrasenaDeDueno(id, usuarioId, request.password());
    }
}
