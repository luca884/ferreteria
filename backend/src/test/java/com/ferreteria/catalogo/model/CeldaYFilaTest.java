package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Registros puros de la lectura de listas: sin Spring ni POI. */
class CeldaYFilaTest {

    @Test
    @DisplayName("Celda.VACIA esta vacia y una celda en blanco o sin texto tambien")
    void celdaVacia() {
        assertThat(Celda.VACIA.vacia()).isTrue();
        assertThat(new Celda("   ", null).vacia()).isTrue();
        assertThat(new Celda(null, null).vacia()).isTrue();
        assertThat(new Celda(null, null).texto()).isEmpty();
    }

    @Test
    @DisplayName("una celda con texto o con numero no esta vacia")
    void celdaConContenido() {
        assertThat(new Celda("abc", null).vacia()).isFalse();
        assertThat(new Celda("0", new BigDecimal("0")).vacia()).isFalse();
    }

    @Test
    @DisplayName("una celda numerica entera trae texto 123 y numero 123")
    void celdaNumericaEntera() {
        Celda celda = new Celda("123", new BigDecimal("123"));

        assertThat(celda.texto()).isEqualTo("123");
        assertThat(celda.numero()).isEqualByComparingTo("123");
    }

    @Test
    @DisplayName("celda(i) fuera de rango devuelve VACIA")
    void celdaFueraDeRango() {
        FilaLeida fila = new FilaLeida(3, List.of(new Celda("a", null)));

        assertThat(fila.celda(0).texto()).isEqualTo("a");
        assertThat(fila.celda(1)).isSameAs(Celda.VACIA);
        assertThat(fila.celda(-1)).isSameAs(Celda.VACIA);
        assertThat(fila.celda(500)).isSameAs(Celda.VACIA);
        assertThat(fila.numero()).isEqualTo(3);
    }

    @Test
    @DisplayName("la fila esta vacia solo si todas sus celdas lo estan")
    void filaVacia() {
        assertThat(new FilaLeida(1, List.of()).vacia()).isTrue();
        assertThat(new FilaLeida(1, List.of(Celda.VACIA, new Celda(" ", null))).vacia()).isTrue();
        assertThat(new FilaLeida(1, List.of(Celda.VACIA, new Celda("x", null))).vacia()).isFalse();
    }

    @Test
    @DisplayName("LecturaLista guarda hojas y filas")
    void lecturaLista() {
        FilaLeida fila = new FilaLeida(7, List.of(new Celda("x", null)));
        LecturaLista lectura = new LecturaLista(List.of("Hoja1", "Hoja2"), List.of(fila));

        assertThat(lectura.hojas()).containsExactly("Hoja1", "Hoja2");
        assertThat(lectura.filas()).containsExactly(fila);
    }

    // ---- varias hojas (corte 2) ----

    @Test
    @DisplayName("FilaLeida(hoja, numero, celdas) expone la hoja; el constructor viejo la deja en 0")
    void filaConHoja() {
        FilaLeida deLaTercera = new FilaLeida(2, 7, List.of(new Celda("x", null)));
        FilaLeida vieja = new FilaLeida(7, List.of(new Celda("x", null)));

        assertThat(deLaTercera.hoja()).isEqualTo(2);
        assertThat(deLaTercera.numero()).isEqualTo(7);
        assertThat(deLaTercera.celda(0).texto()).isEqualTo("x");
        assertThat(vieja.hoja()).isZero();
        assertThat(vieja.numero()).isEqualTo(7);
    }

    @Test
    @DisplayName("AvisoDeFila(hoja, fila, codigo, mensaje) expone la hoja; el constructor viejo la deja en null")
    void avisoConHoja() {
        AvisoDeFila conHoja = new AvisoDeFila(3, 57, "A-1", "Falta el costo");
        AvisoDeFila viejo = new AvisoDeFila(57, "A-1", "Falta el costo");

        assertThat(conHoja.hoja()).isEqualTo(3);
        assertThat(conHoja.fila()).isEqualTo(57);
        assertThat(conHoja.codigo()).isEqualTo("A-1");
        assertThat(conHoja.mensaje()).isEqualTo("Falta el costo");
        assertThat(viejo.hoja()).isNull();
        assertThat(viejo.fila()).isEqualTo(57);
        assertThat(viejo.mensaje()).isEqualTo("Falta el costo");
    }

    @Test
    @DisplayName("Celda trae porcentaje (false por defecto) sin romper los constructores existentes")
    void celdaConPorcentaje() {
        Celda comun = new Celda("0.21", new BigDecimal("0.21"));
        Celda conFormatoPorcentaje = new Celda("0.21", new BigDecimal("0.21"), true);

        assertThat(comun.porcentaje()).isFalse();
        assertThat(conFormatoPorcentaje.porcentaje()).isTrue();
        assertThat(conFormatoPorcentaje.numero()).isEqualByComparingTo("0.21");
        assertThat(Celda.VACIA.porcentaje()).isFalse();
        assertThat(new Celda(null, null, true).texto()).isEmpty();
    }

    @Test
    @DisplayName("una celda con formato porcentaje y sin valor sigue estando vacia")
    void celdaPorcentajeVacia() {
        assertThat(new Celda("", null, true).vacia()).isTrue();
        assertThat(new Celda("21 %", null, false).vacia()).isFalse();
    }

    @Test
    @DisplayName("LecturaLista lleva las filas de todas las hojas y se puede pedir las de una")
    void lecturaConVariasHojas() {
        FilaLeida primera = new FilaLeida(0, 1, List.of(new Celda("a", null)));
        FilaLeida segunda = new FilaLeida(1, 1, List.of(new Celda("b", null)));
        FilaLeida otraDeLaSegunda = new FilaLeida(1, 4, List.of(new Celda("c", null)));
        LecturaLista lectura = new LecturaLista(List.of("TORNILLOS", "TUERCAS"),
            List.of(primera, segunda, otraDeLaSegunda));

        assertThat(lectura.filas()).containsExactly(primera, segunda, otraDeLaSegunda);
        assertThat(lectura.filasDeHoja(0)).containsExactly(primera);
        assertThat(lectura.filasDeHoja(1)).containsExactly(segunda, otraDeLaSegunda);
        assertThat(lectura.filasDeHoja(2)).isEmpty();
    }

    @Test
    @DisplayName("ArchivoLista quita la ruta del nombre, con barras comunes e invertidas")
    void archivoSinRuta() {
        assertThat(new ArchivoLista("C:\\listas\\ferrum.xlsx", new byte[0]).nombre()).isEqualTo("ferrum.xlsx");
        assertThat(new ArchivoLista("/tmp/listas/ferrum.csv", new byte[0]).nombre()).isEqualTo("ferrum.csv");
        assertThat(new ArchivoLista("ferrum.xls", new byte[0]).nombre()).isEqualTo("ferrum.xls");
    }

    @Test
    @DisplayName("ArchivoLista usa lista cuando el nombre esta vacio, es null o solo trae la ruta")
    void archivoSinNombre() {
        assertThat(new ArchivoLista(null, new byte[0]).nombre()).isEqualTo("lista");
        assertThat(new ArchivoLista("", new byte[0]).nombre()).isEqualTo("lista");
        assertThat(new ArchivoLista("   ", new byte[0]).nombre()).isEqualTo("lista");
        assertThat(new ArchivoLista("carpeta/", new byte[0]).nombre()).isEqualTo("lista");
    }

    @Test
    @DisplayName("ArchivoLista corta el nombre a 255 code points sin partir un par sustituto")
    void archivoNombreLargo() {
        String largo = "a".repeat(300);
        assertThat(new ArchivoLista(largo, new byte[0]).nombre()).hasSize(255);

        // U+1F527 ocupa 2 chars: 200 de ellos son 200 code points y 400 chars
        String emojis = "\uD83D\uDD27".repeat(300);
        String cortado = new ArchivoLista(emojis, new byte[0]).nombre();
        assertThat(cortado.codePointCount(0, cortado.length())).isEqualTo(255);
        assertThat(Character.isHighSurrogate(cortado.charAt(cortado.length() - 2))).isTrue();
        assertThat(Character.isLowSurrogate(cortado.charAt(cortado.length() - 1))).isTrue();
    }

    @Test
    @DisplayName("ArchivoLista conserva el contenido")
    void archivoContenido() {
        byte[] bytes = {1, 2, 3};

        assertThat(new ArchivoLista("a.csv", bytes).contenido()).containsExactly(1, 2, 3);
    }
}
