package com.ferreteria.facturacion.web;

import com.ferreteria.facturacion.arca.PruebaConexion;
import com.ferreteria.facturacion.credencial.ConfiguracionFacturacion;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.credencial.PedidoCertificado;
import com.ferreteria.facturacion.service.ProbarConexionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Facturación» del Dueño (CU-01 pasos 2 a 4, rama 5 C1 y rama 6 C1): el punto de venta, el pedido de certificado, el .crt
 * de ARCA, la firma con el simulador y «Probar conexión», de la ferretería del token. Solo el Dueño: el Vendedor y el super admin reciben 403 antes de
 * leer el cuerpo ({@code SecurityConfig}). Ninguna respuesta lleva la clave privada (D8, S7), y no hay forma de subir una.
 */
@RestController
@RequestMapping("/api/facturacion")
public class FacturacionController {

    private final CredencialArcaService credenciales;
    private final ProbarConexionService prueba;

    public FacturacionController(CredencialArcaService credenciales, ProbarConexionService prueba) {
        this.credenciales = credenciales;
        this.prueba = prueba;
    }

    @GetMapping("/configuracion")
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionFacturacion configuracion() {
        return credenciales.configuracion();
    }

    /** 400 en {@code puntoVenta} fuera de 1 a 99998; 409 con el simulador o con registros abiertos. */
    @PutMapping("/punto-venta")
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionFacturacion puntoVenta(@Valid @RequestBody PuntoVentaRequest request) {
        return credenciales.cambiarPuntoVenta(request.puntoVenta());
    }

    /** 201 con el CSR; el cuerpo es opcional ({@code cuit} null: el de la ferretería). */
    @PostMapping("/pedido-certificado")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DUENO')")
    public PedidoCertificado generarPedido(@RequestBody(required = false) PedidoCertificadoRequest request) {
        return credenciales.generarPedido(request == null ? null : request.cuit());
    }

    /** 404 si no hay pedido pendiente. */
    @GetMapping("/pedido-certificado")
    @PreAuthorize("hasRole('DUENO')")
    public PedidoCertificado pedidoPendiente() {
        return credenciales.pedidoPendiente();
    }

    /** 400 en {@code certificado} con el motivo; 409 sin pedido pendiente. */
    @PostMapping("/certificado")
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionFacturacion subirCertificado(@RequestBody(required = false) CertificadoRequest request) {
        return credenciales.subirCertificado(request == null ? null : request.certificado());
    }

    /** 409 fuera de SIMULADOR o sin pedido pendiente. */
    @PostMapping("/certificado/simulador")
    @PreAuthorize("hasRole('DUENO')")
    public ConfiguracionFacturacion firmarConElSimulador() {
        return credenciales.firmarConElSimulador();
    }

    /**
     * «Probar conexión» (rama 6, C1, CU-01 paso 4): FEDummy, el ticket de WSAA, el punto de venta y los últimos números, en
     * orden, contra el ambiente de la ferretería (en SIMULADOR, con el aviso). 409 sin ambiente o punto de venta, o sin un
     * certificado vigente. Un OK completo levanta el bloqueo de ARCA.
     */
    @PostMapping("/probar-conexion")
    @PreAuthorize("hasRole('DUENO')")
    public PruebaConexion probarConexion() {
        return prueba.probar();
    }
}
