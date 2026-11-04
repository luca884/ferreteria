package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.LectorDeFacturas;
import org.springframework.stereotype.Component;

/**
 * Lee una factura de proveedor en PDF con texto (CU-13). Las palabras salen por el mismo camino que una lista de
 * precios ({@link LectorPdf#palabras}: JVM aparte, 256 MB, 30 s, tope de palabras, un PDF por vez) y {@link FacturaDePdf}
 * las interpreta.
 */
@Component
public class LectorDeFacturaPdf implements LectorDeFacturas {

    /** El PDF escaneado de una factura: el de la lista de precios pide la lista en Excel, que aca no aplica. */
    static final String SIN_TEXTO = "El PDF no tiene texto para leer (parece escaneado). Cargá la compra a mano.";

    private final LectorPdf lectorPdf;

    public LectorDeFacturaPdf(LectorPdf lectorPdf) {
        this.lectorPdf = lectorPdf;
    }

    @Override
    public FacturaLeida leer(byte[] pdf) {
        return FacturaDePdf.leer(lectorPdf.palabras(pdf, SIN_TEXTO));
    }
}
