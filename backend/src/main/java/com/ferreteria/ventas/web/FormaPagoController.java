package com.ferreteria.ventas.web;

import com.ferreteria.ventas.service.FormaPagoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Formas de pago con recargo (GDS-11, RC-008). Las lee el Dueno y el Vendedor (el cobro del mostrador las necesita); las
 * crea y edita solo el Dueno. Una forma de otra ferreteria da 404.
 */
@RestController
@RequestMapping("/api/formas-pago")
public class FormaPagoController {

    private final FormaPagoService formaPagoService;

    public FormaPagoController(FormaPagoService formaPagoService) {
        this.formaPagoService = formaPagoService;
    }

    @GetMapping
    public List<FormaPagoResponse> listar(@RequestParam(name = "activas", defaultValue = "false") boolean activas) {
        return formaPagoService.listar(activas).stream().map(FormaPagoResponse::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public FormaPagoResponse crear(@Valid @RequestBody FormaPagoRequest request) {
        return FormaPagoResponse.de(formaPagoService.crear(request.aDatos()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public FormaPagoResponse actualizar(@PathVariable Long id, @Valid @RequestBody FormaPagoRequest request) {
        return FormaPagoResponse.de(formaPagoService.actualizar(id, request.aDatos()));
    }
}
