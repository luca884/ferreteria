package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.BadRequestException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Deteccion por bytes magicos: la extension y el Content-Type no se miran. */
class DetectorDeFormatoTest {

    private static final String MENSAJE_PDF = "Las listas en PDF todavía no se pueden importar.";
    private static final String MENSAJE_NO_VALIDO =
        "El archivo no es una lista válida. Subí un Excel (.xlsx o .xls) o un CSV.";
    private static final String MENSAJE_VACIO = "No se pudo leer el archivo. Verificá que no esté dañado.";

    private static byte[] bytes(int... valores) {
        byte[] resultado = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            resultado[i] = (byte) valores[i];
        }
        return resultado;
    }

    @Test
    @DisplayName("PK\\x03\\x04 es XLSX")
    void xlsx() {
        assertThat(DetectorDeFormato.detectar(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))).isEqualTo(FormatoLista.XLSX);
    }

    @Test
    @DisplayName("la firma OLE2 D0CF11E0A1B11AE1 es XLS")
    void xls() {
        byte[] ole = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0x00, 0x00);

        assertThat(DetectorDeFormato.detectar(ole)).isEqualTo(FormatoLista.XLS);
    }

    @Test
    @DisplayName("un PDF se rechaza con el mensaje de PDF")
    void pdf() {
        byte[] pdf = "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> DetectorDeFormato.detectar(pdf))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_PDF);
    }

    @Test
    @DisplayName("PNG, JPEG y GIF se rechazan como lista no valida")
    void imagenes() {
        byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        byte[] jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10);
        byte[] gif = "GIF89a".getBytes(StandardCharsets.ISO_8859_1);

        for (byte[] imagen : new byte[][] {png, jpeg, gif}) {
            assertThatThrownBy(() -> DetectorDeFormato.detectar(imagen))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(MENSAJE_NO_VALIDO);
        }
    }

    @Test
    @DisplayName("contenido binario con bytes nulos se rechaza como lista no valida")
    void bytesNulos() {
        byte[] binario = bytes(0x41, 0x42, 0x00, 0x43, 0x44);

        assertThatThrownBy(() -> DetectorDeFormato.detectar(binario))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_NO_VALIDO);
    }

    @Test
    @DisplayName("un archivo vacio o null no se puede leer")
    void vacio() {
        assertThatThrownBy(() -> DetectorDeFormato.detectar(new byte[0]))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_VACIO);
        assertThatThrownBy(() -> DetectorDeFormato.detectar(null))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_VACIO);
    }

    @Test
    @DisplayName("texto plano se lee como CSV, con o sin BOM")
    void csv() {
        byte[] plano = "codigo;descripcion;costo\nA-1;Tornillo;12,5\n".getBytes(StandardCharsets.UTF_8);
        byte[] conBom = bytes(0xEF, 0xBB, 0xBF, 'a', ';', 'b');
        byte[] windows1252 = "Arandela ½;12".getBytes(Charset.forName("windows-1252"));

        assertThat(DetectorDeFormato.detectar(plano)).isEqualTo(FormatoLista.CSV);
        assertThat(DetectorDeFormato.detectar(conBom)).isEqualTo(FormatoLista.CSV);
        assertThat(DetectorDeFormato.detectar(windows1252)).isEqualTo(FormatoLista.CSV);
    }

    @Test
    @DisplayName("un archivo de menos de 4 bytes que no es otra cosa se lee como CSV")
    void muyCorto() {
        assertThat(DetectorDeFormato.detectar(bytes('a'))).isEqualTo(FormatoLista.CSV);
        assertThat(DetectorDeFormato.detectar(bytes(0x50, 0x4B))).isEqualTo(FormatoLista.CSV);
    }

    @Test
    @DisplayName("los bytes nulos mas alla de los primeros 4 KB no rechazan el archivo")
    void nulosLejos() {
        byte[] largo = new byte[5000];
        Arrays.fill(largo, (byte) 'a');
        largo[4500] = 0;

        assertThat(DetectorDeFormato.detectar(largo)).isEqualTo(FormatoLista.CSV);
    }
}
