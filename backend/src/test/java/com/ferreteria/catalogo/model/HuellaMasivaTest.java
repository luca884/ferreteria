package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: la huella tiene que ser determinista, porque de eso depende el 409 al aplicar. */
class HuellaMasivaTest {

    private static final OperacionMasiva OPERACION = new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10"));
    private static final FiltroArticulos FILTRO = new FiltroArticulos(null, null, null, false);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static ArticuloVigente articulo(long id, String costo, String margen, String precio, boolean activo) {
        return new ArticuloVigente(id, "INT-" + id, null, "Articulo " + id, bd(costo), bd(precio),
            margen == null ? null : bd(margen), null, AlicuotaIva.IVA_21, activo);
    }

    private static ArticuloVigente a1() {
        return articulo(1, "100.00", null, "160.00", true);
    }

    /** Huella de un solo articulo: 100.00 -> 110.00, precio 160.00 -> 180.00. */
    private static String huella(OperacionMasiva operacion, FiltroArticulos filtro) {
        HuellaMasiva huella = new HuellaMasiva(operacion, filtro);
        huella.agregar(a1(), bd("110.00"), null, bd("180.00"));
        return huella.calcular();
    }

    private static String huellaDe(ArticuloVigente articulo, String costoNuevo, String margenNuevo, String precioNuevo) {
        HuellaMasiva huella = new HuellaMasiva(OPERACION, FILTRO);
        huella.agregar(articulo, bd(costoNuevo), margenNuevo == null ? null : bd(margenNuevo), bd(precioNuevo));
        return huella.calcular();
    }

    @Test
    @DisplayName("la misma entrada da el mismo hash, de 64 caracteres hexadecimales en minuscula")
    void determinista() {
        String primera = huella(OPERACION, FILTRO);
        String segunda = huella(OPERACION, FILTRO);

        assertThat(primera).isEqualTo(segunda).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("el formato v2 es SHA-256 de las lineas op, filtro (con importacionId) y a (una por articulo)")
    void formatoV2() throws Exception {
        String texto = "v2\n"
            + "op|COSTO_PORCENTAJE|10.00\n"
            + "filtro|7|~|~|true|5:cable\n"
            + "a|1|true|100.00|~|160.00|110.00|~|180.00\n"
            + "a|2|false|0.50|50.00|10.00|E\n";
        String esperado = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));

        HuellaMasiva huella = new HuellaMasiva(OPERACION, new FiltroArticulos(7L, null, "  cable ", true));
        huella.agregar(a1(), bd("110.00"), null, bd("180.00"));
        huella.agregarError(articulo(2, "0.50", "50.00", "10.00", false));

        assertThat(huella.calcular()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("170 y 170.00 (y 100 y 100.00) dan la misma huella")
    void escalasEquivalentes() {
        String conEscala = huellaDe(articulo(1, "100.00", "30.00", "170.00", true), "110.00", "30.00", "180.00");
        String sinEscala = huellaDe(articulo(1, "100", "30", "170", true), "110", "30", "180");

        assertThat(sinEscala).isEqualTo(conEscala);
    }

    @Test
    @DisplayName("la operacion cambia la huella: el modo y el valor")
    void operacion() {
        String base = huella(OPERACION, FILTRO);

        assertThat(huella(new OperacionMasiva(ModoMasivo.MARGEN, bd("10")), FILTRO)).isNotEqualTo(base);
        assertThat(huella(new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("15")), FILTRO)).isNotEqualTo(base);
        assertThat(huella(new OperacionMasiva(ModoMasivo.COSTO_PORCENTAJE, bd("10.00")), FILTRO)).isEqualTo(base);
    }

    @Test
    @DisplayName("el filtro cambia la huella: proveedor, rubro, texto e incluirInactivos")
    void filtro() {
        String base = huella(OPERACION, FILTRO);

        assertThat(huella(OPERACION, new FiltroArticulos(1L, null, null, false))).isNotEqualTo(base);
        assertThat(huella(OPERACION, new FiltroArticulos(null, 1L, null, false))).isNotEqualTo(base);
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, "cable", false))).isNotEqualTo(base);
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, null, true))).isNotEqualTo(base);
        assertThat(huella(OPERACION, new FiltroArticulos(1L, null, null, false)))
            .isNotEqualTo(huella(OPERACION, new FiltroArticulos(null, 1L, null, false)));
    }

    @Test
    @DisplayName("una q en blanco o con espacios sobrantes da la misma huella que la normalizada")
    void qNormalizada() {
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, "   ", false)))
            .isEqualTo(huella(OPERACION, new FiltroArticulos(null, null, null, false)));
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, " cable ", false)))
            .isEqualTo(huella(OPERACION, new FiltroArticulos(null, null, "cable", false)));
    }

    @Test
    @DisplayName("una q con | no colisiona con otros filtros (el texto lleva prefijo de longitud)")
    void qConSeparador() {
        String conBarra = huella(OPERACION, new FiltroArticulos(null, null, "a|b", false));

        assertThat(conBarra).isNotEqualTo(huella(OPERACION, new FiltroArticulos(null, null, "a", false)));
        assertThat(conBarra).isNotEqualTo(huella(OPERACION, new FiltroArticulos(null, null, "b", false)));
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, "1|2", false)))
            .isNotEqualTo(huella(OPERACION, new FiltroArticulos(1L, null, "2", false)));
    }

    @Test
    @DisplayName("cambian la huella el costo, el margen propio, el precio y el activo de un articulo")
    void datosDelArticulo() {
        String base = huellaDe(a1(), "110.00", null, "180.00");

        assertThat(huellaDe(articulo(1, "100.01", null, "160.00", true), "110.00", null, "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(articulo(1, "100.00", "30.00", "160.00", true), "110.00", null, "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(articulo(1, "100.00", null, "170.00", true), "110.00", null, "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(articulo(1, "100.00", null, "160.00", false), "110.00", null, "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(articulo(2, "100.00", null, "160.00", true), "110.00", null, "180.00")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("cambian la huella los valores nuevos: costo, margen propio y precio")
    void valoresNuevos() {
        String base = huellaDe(a1(), "110.00", null, "180.00");

        assertThat(huellaDe(a1(), "110.01", null, "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(a1(), "110.00", "50.00", "180.00")).isNotEqualTo(base);
        assertThat(huellaDe(a1(), "110.00", null, "190.00")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("un margen propio de 0 no es lo mismo que un margen propio nulo")
    void margenCeroNoEsNulo() {
        assertThat(huellaDe(articulo(1, "100.00", "0.00", "160.00", true), "110.00", null, "180.00"))
            .isNotEqualTo(huellaDe(a1(), "110.00", null, "180.00"));
    }

    @Test
    @DisplayName("agregar o quitar un articulo cambia la huella")
    void membresia() {
        HuellaMasiva uno = new HuellaMasiva(OPERACION, FILTRO);
        uno.agregar(a1(), bd("110.00"), null, bd("180.00"));
        HuellaMasiva dos = new HuellaMasiva(OPERACION, FILTRO);
        dos.agregar(a1(), bd("110.00"), null, bd("180.00"));
        dos.agregar(articulo(2, "200.00", null, "320.00", true), bd("220.00"), null, bd("350.00"));
        HuellaMasiva ninguno = new HuellaMasiva(OPERACION, FILTRO);

        assertThat(uno.calcular()).isNotEqualTo(dos.calcular()).isNotEqualTo(ninguno.calcular());
    }

    @Test
    @DisplayName("una fila con error se hashea distinto de una modificada")
    void filaConError() {
        HuellaMasiva modificada = new HuellaMasiva(OPERACION, FILTRO);
        modificada.agregar(a1(), bd("100.00"), null, bd("160.00"));
        HuellaMasiva conError = new HuellaMasiva(OPERACION, FILTRO);
        conError.agregarError(a1());

        assertThat(conError.calcular()).isNotEqualTo(modificada.calcular());
    }

    @Test
    @DisplayName("un valor con mas de 2 decimales lanza en vez de redondearse en silencio")
    void masDeDosDecimales() {
        HuellaMasiva huella = new HuellaMasiva(OPERACION, FILTRO);

        assertThatThrownBy(() -> huella.agregar(a1(), bd("110.005"), null, bd("180.00")))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("la importacion cambia la huella: con y sin importacionId, y una contra otra")
    void importacion() {
        String sin = huella(OPERACION, FILTRO);
        String conI1 = huella(OPERACION, new FiltroArticulos(null, null, null, false, 1L));
        String conI2 = huella(OPERACION, new FiltroArticulos(null, null, null, false, 2L));

        assertThat(conI1).isNotEqualTo(sin);
        assertThat(conI1).isNotEqualTo(conI2);
        assertThat(huella(OPERACION, new FiltroArticulos(null, null, null, false, 1L))).isEqualTo(conI1);
    }

    @Test
    @DisplayName("la linea de filtro lleva importacionId entre rubroId e incluirInactivos, con ~ si falta")
    void lineaDeFiltroConImportacion() throws Exception {
        String texto = "v2\n"
            + "op|COSTO_PORCENTAJE|10.00\n"
            + "filtro|~|3|12|false|~\n"
            + "a|1|true|100.00|~|160.00|110.00|~|180.00\n";
        String esperado = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));

        HuellaMasiva huella = new HuellaMasiva(OPERACION, new FiltroArticulos(null, 3L, null, false, 12L));
        huella.agregar(a1(), bd("110.00"), null, bd("180.00"));

        assertThat(huella.calcular()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("la huella v1 real de este alcance (SHA-256 del texto v1) ya no coincide con la v2")
    void huellaV1YaNoCoincide() {
        // SHA-256 de "v1\nop|COSTO_PORCENTAJE|10.00\nfiltro|~|~|false|~\na|1|true|100.00|~|160.00|110.00|~|180.00\n".
        String huellaV1 = "9179ba7d2c17dae9206adc23d78720b5d5ff2939714bb033a419b53da372c08a";

        assertThat(huella(OPERACION, FILTRO)).isNotEqualTo(huellaV1);
    }
}
