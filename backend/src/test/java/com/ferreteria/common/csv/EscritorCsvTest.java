package com.ferreteria.common.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CSV para Excel argentino: BOM, separador ;, coma decimal, CRLF y textos que no se ejecutan como formula. */
class EscritorCsvTest {

    private static String escribir(List<String> encabezado, List<Object>... filas) {
        byte[] bytes = EscritorCsv.escribir(encabezado, Arrays.asList(filas));
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("arranca con el BOM y separa con ; y CRLF, una linea por fila despues del encabezado")
    void formatoBasico() {
        String csv = escribir(List.of("Código", "Descripción"), List.of("A-1", "Tornillo"), List.of("B-2", "Tuerca"));

        assertThat(csv).isEqualTo("Código;Descripción\r\nA-1;Tornillo\r\nB-2;Tuerca\r\n");
    }

    @Test
    @DisplayName("los numeros salen con coma decimal, sin separador de miles y tal como vienen; null es la celda vacia")
    void numerosYVacios() {
        String csv = escribir(List.of("a", "b", "c", "d"),
                Arrays.asList(new BigDecimal("1234567.50"), new BigDecimal("-3"), null, new BigDecimal("0.125")));

        assertThat(csv).endsWith("\r\n1234567,50;-3;;0,125\r\n");
    }

    @Test
    @DisplayName("un texto con ;, comillas o saltos de linea va entre comillas dobles, con las internas duplicadas")
    void comillas() {
        String csv = escribir(List.of("a", "b", "c", "d"),
                List.of("Caño 1/2\"", "Pinza; corte", "dos\nlineas", "retorno\rsolo"));

        assertThat(csv).endsWith("\r\n\"Caño 1/2\"\"\";\"Pinza; corte\";\"dos\nlineas\";\"retorno\rsolo\"\r\n");
    }

    @Test
    @DisplayName("un texto que empieza con = + - @ tab o retorno lleva un apostrofo adelante (inyeccion CSV); un numero no")
    void textosQueParecenFormula() {
        String csv = escribir(List.of("a", "b", "c", "d", "e", "f", "g"),
                Arrays.asList("=1+1", "+54 223", "-10%", "@SUMA(A1)", "\tTab", "\rRet", new BigDecimal("-5")));

        assertThat(csv).endsWith("\r\n'=1+1;'+54 223;'-10%;'@SUMA(A1);'\tTab;\"'\rRet\";-5\r\n");
    }

    @Test
    @DisplayName("un codigo sale como texto de Excel =\"...\", que conserva los ceros; null es la celda vacia")
    void codigos() {
        String csv = escribir(List.of("a", "b", "c", "d", "e"), Arrays.asList(
                EscritorCsv.codigo("0012"), EscritorCsv.codigo("AB\"C"), EscritorCsv.codigo("=1+1"),
                EscritorCsv.codigo("a;b"), EscritorCsv.codigo(null)));

        assertThat(csv).endsWith("\r\n=\"0012\";=\"AB\"\"C\";=\"=1+1\";\"=\"\"a;b\"\"\";\r\n");
    }

    @Test
    @DisplayName("el encabezado tambien se escapa, y sin filas queda solo el encabezado")
    void soloEncabezado() {
        assertThat(escribir(List.of("Precio; final", "=x"))).isEqualTo("\"Precio; final\";'=x\r\n");
    }
}
