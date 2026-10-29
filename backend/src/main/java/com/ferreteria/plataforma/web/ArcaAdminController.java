package com.ferreteria.plataforma.web;

import com.ferreteria.facturacion.api.ChequeoArcaParaPlataforma;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El chequeo de ARCA del super admin (rama 6, C2, S9): {@code FEDummy} desde el servidor desplegado, en homologación y en
 * producción, para probar la red y el TLS. Solo el super admin: el Dueño y el Vendedor reciben 403 ({@code SecurityConfig}).
 * Pasa por {@code facturacion.api}: la plataforma no conoce el cliente de ARCA.
 */
@RestController
@RequestMapping("/api/admin/arca")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ArcaAdminController {

    private final ChequeoArcaParaPlataforma chequeo;

    public ArcaAdminController(ChequeoArcaParaPlataforma chequeo) {
        this.chequeo = chequeo;
    }

    /** Sin cuerpo; siempre 200, con el resultado de cada ambiente (una falla de ARCA va en su {@code error}). */
    @PostMapping("/fedummy")
    public ChequeoArcaParaPlataforma.ChequeoFeDummy feDummy() {
        return chequeo.feDummy();
    }
}
