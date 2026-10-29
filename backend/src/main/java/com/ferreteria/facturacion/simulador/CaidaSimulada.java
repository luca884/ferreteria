package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Solo en el perfil dev (rama 7, S9 de cu-07-contingencias): ARCA simulada caída. Mientras existe el archivo de
 * {@code app.facturacion.simulador.caida}, el WSFEv1 del simulador no contesta: cada pedido termina en
 * {@link PedidoNoLlegoException}, como un ARCA que no responde, y las ventas quedan pendientes hasta que el job las autoriza.
 * WSAA sigue contestando (el ticket de acceso no entra en la prueba). Se prende y se apaga desde la terminal, creando y borrando
 * el archivo, sin endpoint. Fuera de dev el bean no existe y el simulador contesta siempre.
 */
@Component
@Profile("dev")
class CaidaSimulada {

    private final Path archivo;

    CaidaSimulada(@Value("${app.facturacion.simulador.caida}") Path archivo) {
        this.archivo = archivo;
    }

    boolean caida() {
        return Files.exists(archivo);
    }
}
