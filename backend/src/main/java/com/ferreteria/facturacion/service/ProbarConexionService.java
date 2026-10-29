package com.ferreteria.facturacion.service;

import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.ClientesWsfe;
import com.ferreteria.facturacion.arca.PruebaConexion;
import com.ferreteria.facturacion.arca.PruebaDeConexion;
import com.ferreteria.facturacion.arca.TicketsDeAcceso;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * «Probar conexión» del Dueño (rama 6, C1, CU-01 paso 4), de la ferretería del request ({@code TenantContext}, del JWT).
 * Lee lo que hace falta en una transacción corta ({@link CredencialArcaService#paraProbar()}: los dos 409), corre los cuatro
 * pasos de {@link PruebaDeConexion} contra el cliente de WSFEv1 de su ambiente, sin ninguna transacción abierta, y con un OK
 * completo levanta el bloqueo de C4 ({@link CredencialArcaService#levantarBloqueo()}, otra transacción corta).
 *
 * <p>Sin {@code @Transactional} a propósito: las llamadas a ARCA nunca van dentro de una transacción (el ticket de acceso lo
 * exige). El log no lleva el token, el sign ni el CMS: solo el ambiente y el resultado.
 */
@Service
public class ProbarConexionService {

    private static final Logger log = LoggerFactory.getLogger(ProbarConexionService.class);

    private final CredencialArcaService credenciales;
    private final ClientesWsfe clientes;
    private final TicketsDeAcceso tickets;

    public ProbarConexionService(CredencialArcaService credenciales, ClientesWsfe clientes, TicketsDeAcceso tickets) {
        this.credenciales = credenciales;
        this.clientes = clientes;
        this.tickets = tickets;
    }

    /** Corre la prueba; 409 sin ambiente o punto de venta, o sin un certificado vigente. */
    public PruebaConexion probar() {
        CredencialArcaService.ParaProbar datos = credenciales.paraProbar();
        PruebaConexion prueba = new PruebaDeConexion(clientes.de(datos.ambiente()),
                ambiente -> tickets.probar(ambiente, ClienteWsfe.SERVICIO))
                .correr(new PruebaDeConexion.Datos(datos.ambiente(), datos.puntoVenta(), datos.cuit(),
                        datos.condicionIva()));
        if (prueba.ok()) {
            credenciales.levantarBloqueo();
        }
        log.info("«Probar conexión» en {}: {}", datos.ambiente(), prueba.ok() ? "OK"
                : "falló en " + prueba.pasos().get(prueba.pasos().size() - 1).paso());
        return prueba;
    }
}
