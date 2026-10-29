package com.ferreteria.ventas.web;

import com.ferreteria.auth.model.Rol;
import com.ferreteria.auth.security.AuthUser;
import com.ferreteria.common.PageResponse;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Bandeja;
import com.ferreteria.ventas.service.AnulacionDeVenta;
import com.ferreteria.ventas.service.FacturaDeLaVenta;
import com.ferreteria.ventas.service.VentaGestion;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Cobrar un ticket (CU-07). Lo pueden usar el Dueno y el Vendedor: sin {@code @PreAuthorize}, entra por
 * "autenticado". La ferreteria, el usuario y su rol salen solo del JWT: el rol decide quien puede autorizar un exceso del limite
 * de cuenta corriente (CC-005). Consultar las ventas hechas (G60) es solo del Dueno: con el listado el Vendedor podria
 * sumar el efectivo del dia y el cierre de caja dejaria de ser ciego (CU-10). Lo que el cobro anticipa del comprobante
 * ({@code GET /api/ventas/facturacion}, cu-07-factura-mostrador C3) es de los dos: no muestra ninguna venta. La
 * reimpresion inmediata del Vendedor (D7) sale de la respuesta del cobro, sin otra lectura.
 *
 * <p>Rama 7 (cu-07-contingencias): la bandeja ({@code fiscal}) y las dos acciones sobre la factura son del Dueño; «llego el
 * CAE» y la hoja de esas ventas ({@code /provisorias-autorizadas} y {@code /{id}/factura}) son de los dos, pero el
 * Vendedor solo ve las ventas que cobro en la caja abierta y salieron con ticket provisorio: el cierre sigue ciego.
 *
 * <p>Rama 8 (CU-08): anular una venta ({@code /{id}/anular}) es solo del Dueño.
 */
@RestController
@RequestMapping("/api/ventas")
public class VentaController {

    private final VentaService ventaService;
    private final VentaGestion ventaGestion;
    private final FacturaDeLaVenta facturaDeLaVenta;
    private final FacturacionParaVenta facturacion;
    private final AnulacionDeVenta anulacionDeVenta;

    public VentaController(VentaService ventaService, VentaGestion ventaGestion, FacturaDeLaVenta facturaDeLaVenta,
            FacturacionParaVenta facturacion, AnulacionDeVenta anulacionDeVenta) {
        this.anulacionDeVenta = anulacionDeVenta;
        this.ventaService = ventaService;
        this.ventaGestion = ventaGestion;
        this.facturaDeLaVenta = facturaDeLaVenta;
        this.facturacion = facturacion;
    }

    /** 201 si la venta es nueva; 200 si la clave ya estaba registrada con el mismo contenido (R-011). */
    @PostMapping
    public ResponseEntity<VentaResponse> cobrar(
            @Valid @RequestBody ConfirmarVentaRequest request, @AuthenticationPrincipal AuthUser authUser) {
        VentaRegistrada venta = ventaService.cobrar(request.aPedido(), authUser.userId(), authUser.rol() == Rol.DUENO);
        return ResponseEntity.status(venta.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(VentaResponse.de(venta));
    }

    /**
     * Las de la ferreteria, mas nuevas primero, con el resumen de su factura. {@code desde} y {@code hasta} son dias
     * (aaaa-mm-dd), ambos inclusive; {@code cliente} busca el texto dentro del nombre del cliente; {@code numeroFiscal}
     * busca el numero de la factura («12», «1-12» o «00001-00000012»; con otro formato, 400); {@code fiscal} es la bandeja
     * (cu-07-contingencias C1): PENDIENTES o RECHAZADAS (otro valor, 400).
     */
    @GetMapping
    @PreAuthorize("hasRole('DUENO')")
    public PageResponse<VentaResumenResponse> listar(
            @RequestParam(required = false) Long numero, @RequestParam(required = false) String cliente,
            @RequestParam(required = false) String numeroFiscal, @RequestParam(required = false) Bandeja fiscal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(ventaGestion.listar(numero, cliente, numeroFiscal, fiscal, desde, hasta, page, size),
                VentaResumenResponse::de);
    }

    /**
     * «Llego el CAE» (cu-07-contingencias C7): las ventas que el usuario cobro en la caja abierta, que salieron con ticket
     * provisorio y ya estan AUTORIZADO. El Dueño y el Vendedor (sin {@code @PreAuthorize}): cada uno ve solo las suyas.
     */
    @GetMapping("/provisorias-autorizadas")
    public List<AccionesFacturaRequests.ProvisoriaAutorizada> provisoriasAutorizadas(
            @AuthenticationPrincipal AuthUser authUser) {
        return ventaGestion.provisoriasAutorizadas(authUser.userId()).stream()
                .map(p -> new AccionesFacturaRequests.ProvisoriaAutorizada(p.ventaId(), p.numero(), p.factura()))
                .toList();
    }

    /**
     * Lo que el cobro anticipa del comprobante (S3, C3): si factura, el ambiente y la letra por condicion del cliente. El
     * Dueno y el Vendedor (sin {@code @PreAuthorize}): no muestra ventas.
     */
    @GetMapping("/facturacion")
    public FacturacionMostradorResponse facturacion() {
        return FacturacionMostradorResponse.de(facturacion.paraElMostrador());
    }

    /** La venta como al cobrarla (renglones y pagos), quien la cobro y su factura, para verla y reimprimirla. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DUENO')")
    public VentaResponse detalle(@PathVariable Long id) {
        return VentaResponse.de(ventaGestion.detalle(id));
    }

    /**
     * La hoja de la venta para reimprimir la factura cuando llego su CAE (C7): la misma respuesta que el detalle. El Dueño
     * la pide de cualquier venta; el Vendedor, solo de una de {@code provisorias-autorizadas}: cualquier otra es 404.
     */
    @GetMapping("/{id}/factura")
    public VentaResponse factura(@PathVariable Long id, @AuthenticationPrincipal AuthUser authUser) {
        return VentaResponse.de(ventaGestion.factura(id, authUser.userId(), authUser.rol() == Rol.DUENO));
    }

    /**
     * «Reintentar» la factura de una venta rechazada o fuera de fecha (C2): la factura nueva con la copia de hoy, y su
     * estado despues de la espera corta del CAE. 409 y 422 con el cuerpo de los rechazos de la venta.
     */
    @PostMapping("/{id}/factura/reintentar")
    @PreAuthorize("hasRole('DUENO')")
    public VentaResponse.FiscalConsultadoResponse reintentar(@PathVariable Long id,
            @RequestBody(required = false) AccionesFacturaRequests.Reintentar request) {
        return VentaResponse.FiscalConsultadoResponse.de(
                facturaDeLaVenta.reintentar(id, request != null && request.conFechaDeHoy()));
    }

    /** «Dejar sin factura» (C3): definitivo, con el motivo. 400 en {@code motivo}; 409 sin una rechazada. */
    @PostMapping("/{id}/factura/sin-factura")
    @PreAuthorize("hasRole('DUENO')")
    public VentaResponse.FiscalConsultadoResponse sinFactura(@PathVariable Long id,
            @RequestBody(required = false) AccionesFacturaRequests.SinFactura request,
            @AuthenticationPrincipal AuthUser authUser) {
        return VentaResponse.FiscalConsultadoResponse.de(facturaDeLaVenta.dejarSinFactura(id,
                request == null ? null : request.motivo(), authUser.userId()));
    }

    /**
     * Anular la venta (CU-08): con su factura AUTORIZADO, la nota de crédito total con su CAE; sin CAE, la anulación
     * interna con el motivo. Responde 201 con la nota como la ve el detalle. 400 en {@code motivo}; 404 «Venta no
     * encontrada»; 409 ya anulada, una nota de crédito, «Esperá el CAE» u otro ambiente; 422 con un pago a cuenta corriente.
     */
    @PostMapping("/{id}/anular")
    @PreAuthorize("hasRole('DUENO')")
    public ResponseEntity<VentaResponse> anular(@PathVariable Long id,
            @RequestBody(required = false) AccionesFacturaRequests.Anular request,
            @AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(VentaResponse.de(anulacionDeVenta.anular(id,
                request == null ? null : request.motivo(), authUser.userId())));
    }

    /** Local: le gana al advice global. El estado sale del motivo (404, 409 o 422). */
    @ExceptionHandler(VentaRechazadaException.class)
    public ResponseEntity<VentaRechazadaResponse> rechazada(VentaRechazadaException e) {
        return ResponseEntity.status(e.motivo().estadoHttp()).body(VentaRechazadaResponse.de(e));
    }
}
