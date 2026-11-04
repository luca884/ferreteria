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

    private final LectorPdf lectorPdf;

    public LectorDeFacturaPdf(LectorPdf lectorPdf) {
        this.lectorPdf = lectorPdf;
    }

    @Override
    public FacturaLeida leer(byte[] pdf) {
        return FacturaDePdf.leer(lectorPdf.palabras(pdf));
    }
}
