package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Los contadores de una importacion: los seis de CU-04 y los tres del corte 2. */
class ConteosImportacionTest {

    @Test
    @DisplayName("suma cambianMoneda, corrigen y salteadas a los seis de siempre")
    void nueveContadores() {
        ConteosImportacion conteos = new ConteosImportacion(200, 12, 2700, 100, 5, 7, 3, 164, 42);

        assertThat(conteos.suben()).isEqualTo(200);
        assertThat(conteos.bajan()).isEqualTo(12);
        assertThat(conteos.nuevos()).isEqualTo(2700);
        assertThat(conteos.sinCambio()).isEqualTo(100);
        assertThat(conteos.noEstan()).isEqualTo(5);
        assertThat(conteos.conError()).isEqualTo(7);
        assertThat(conteos.cambianMoneda()).isEqualTo(3);
        assertThat(conteos.corrigen()).isEqualTo(164);
        assertThat(conteos.salteadas()).isEqualTo(42);
    }

    @Test
    @DisplayName("el constructor viejo de seis deja los tres nuevos en 0")
    void constructorViejo() {
        ConteosImportacion conteos = new ConteosImportacion(4, 2, 1, 3, 5, 6);

        assertThat(conteos.suben()).isEqualTo(4);
        assertThat(conteos.conError()).isEqualTo(6);
        assertThat(conteos.cambianMoneda()).isZero();
        assertThat(conteos.corrigen()).isZero();
        assertThat(conteos.salteadas()).isZero();
        assertThat(conteos).isEqualTo(new ConteosImportacion(4, 2, 1, 3, 5, 6, 0, 0, 0));
        assertThat(conteos).isNotEqualTo(new ConteosImportacion(4, 2, 1, 3, 5, 6, 1, 0, 0));
    }
}
