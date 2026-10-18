package com.ferreteria.clientes.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CL-002: el documento del cliente, normalizado a dígitos y con el dígito verificador del CUIT y el CUIL. */
class DocumentoClienteTest {

    @Nested
    @DisplayName("CUIT y CUIL")
    class Cuit {

        @ParameterizedTest
        @ValueSource(strings = {"20123456786", "30712345671", "27203456781", "33500010911", "33693450239", "20271234563"})
        @DisplayName("acepta CUIT con prefijo y dígito verificador correctos")
        void aceptaValidos(String cuit) {
            assertThat(DocumentoCliente.normalizar(TipoDocumento.CUIT, cuit)).isEqualTo(cuit);
            assertThat(DocumentoCliente.normalizar(TipoDocumento.CUIL, cuit)).isEqualTo(cuit);
        }

        @Test
        @DisplayName("acepta guiones, puntos y espacios y guarda solo los dígitos")
        void normalizaSeparadores() {
            assertThat(DocumentoCliente.normalizar(TipoDocumento.CUIT, "30-71234567-1")).isEqualTo("30712345671");
            assertThat(DocumentoCliente.normalizar(TipoDocumento.CUIT, " 20.12345678.6 ")).isEqualTo("20123456786");
        }

        @Test
        @DisplayName("rechaza un dígito verificador equivocado")
        void rechazaDigitoVerificador() {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.CUIT, "20123456787"))
                    .isInstanceOf(DocumentoInvalidoException.class)
                    .hasMessage("El CUIT no es válido: revisá el dígito verificador.");
        }

        @Test
        @DisplayName("cuando la cuenta da 11 el dígito verificador es 0")
        void verificadorCero() {
            assertThat(DocumentoCliente.normalizar(TipoDocumento.CUIT, "20000000060")).isEqualTo("20000000060");
        }

        @ParameterizedTest
        @ValueSource(strings = {"20000000010", "20000000019", "20000000011"})
        @DisplayName("cuando la cuenta da 10 no existe dígito verificador válido: ninguno se acepta")
        void verificadorDiezNoExiste(String cuit) {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.CUIT, cuit))
                    .isInstanceOf(DocumentoInvalidoException.class)
                    .hasMessage("El CUIT no es válido: revisá el dígito verificador.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"10123456785", "21123456784", "99123456780"})
        @DisplayName("rechaza un prefijo que no es de persona ni de empresa")
        void rechazaPrefijo(String cuit) {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.CUIT, cuit))
                    .isInstanceOf(DocumentoInvalidoException.class)
                    .hasMessage("El CUIT no es válido: revisá los dos primeros dígitos.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"2012345678", "201234567866", "abc", "30-7123456"})
        @DisplayName("rechaza si no tiene 11 dígitos")
        void rechazaLargo(String cuit) {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.CUIT, cuit))
                    .isInstanceOf(DocumentoInvalidoException.class)
                    .hasMessage("El CUIT tiene que tener 11 dígitos.");
        }

        @Test
        @DisplayName("el mensaje del CUIL nombra al CUIL")
        void mensajeDelCuil() {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.CUIL, "20123456787"))
                    .hasMessage("El CUIL no es válido: revisá el dígito verificador.");
        }
    }

    @Nested
    @DisplayName("DNI")
    class Dni {

        @ParameterizedTest
        @ValueSource(strings = {"1234567", "12345678", "12.345.678", "9.876.543"})
        @DisplayName("acepta 7 u 8 dígitos, con puntos")
        void aceptaValidos(String dni) {
            assertThat(DocumentoCliente.normalizar(TipoDocumento.DNI, dni)).isEqualTo(dni.replace(".", ""));
        }

        @ParameterizedTest
        @ValueSource(strings = {"123456", "123456789", "12a45678", "0"})
        @DisplayName("rechaza si no tiene 7 u 8 dígitos")
        void rechazaLargo(String dni) {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.DNI, dni))
                    .isInstanceOf(DocumentoInvalidoException.class)
                    .hasMessage("El DNI tiene que tener 7 u 8 dígitos.");
        }

        @Test
        @DisplayName("rechaza un DNI de solo ceros")
        void rechazaCeros() {
            assertThatThrownBy(() -> DocumentoCliente.normalizar(TipoDocumento.DNI, "0000000"))
                    .isInstanceOf(DocumentoInvalidoException.class);
        }
    }
}
