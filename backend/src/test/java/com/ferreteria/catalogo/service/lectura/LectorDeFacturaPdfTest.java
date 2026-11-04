package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.api.FacturaLeida;
import com.ferreteria.catalogo.api.FacturaLeida.Control;
import com.ferreteria.catalogo.api.FacturaLeida.Renglon;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** La lectura de punta a punta de una factura inventada, por la misma JVM aparte que las listas de precios. */
class LectorDeFacturaPdfTest {

    private final LectorDeFacturaPdf lector = new LectorDeFacturaPdf(new LectorPdf());

    @Test
    void leeUnPdfGeneradoConEncabezadoRenglonesRecargoYControl() {
        byte[] pdf = ListasDePrueba.pdf(List.of(
            List.of("FACTURA", "Codigo 01", "0004 - 00001234"),
            List.of("FECHA:", "03/11/2026", "C.U.I.T.: 30-71234567-1"),
            List.of("TOR-1", "10.0", "TORNILLO", "2.50", "25.00"),
            List.of("TUE-2", "4.0", "TUERCA", "12.50", "50.00"),
            List.of("EMBALAJE Y CARGA :", "4.00%", "3.00"),
            List.of("TOTAL", "78.00")));

        FacturaLeida factura = lector.leer(pdf);

        assertThat(factura.encabezado().letra()).isEqualTo("A");
        assertThat(factura.encabezado().puntoVenta()).isEqualTo(4);
        assertThat(factura.encabezado().numero()).isEqualTo(1234L);
        assertThat(factura.encabezado().fecha()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(factura.encabezado().cuits()).containsExactly("30712345671");
        assertThat(factura.renglones()).extracting(Renglon::codigo).containsExactly("TOR-1", "TUE-2");
        assertThat(factura.renglones().get(1).descripcion()).isEqualTo("TUERCA");
        assertThat(factura.renglones().get(1).neto()).isEqualByComparingTo("50.00");
        assertThat(factura.recargos()).hasSize(1);
        assertThat(factura.recargos().get(0).nombre()).isEqualTo("EMBALAJE Y CARGA");
        assertThat(factura.control()).isEqualTo(Control.CUADRA);
    }

    @Test
    void unPdfSinTextoAvisaConElTextoDeLaFacturaYNoElDeLaLista() {
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.pdfSinTexto()))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El PDF no tiene texto para leer (parece escaneado). Cargá la compra a mano.");
    }
}
