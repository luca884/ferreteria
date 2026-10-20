package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.ventas.service.RegistroAnulacion;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CCT3B1-601 (CC-055, CC-056, CC-057): la forma del pedido de anulación, sin Spring. El DTO NO valida el motivo (sin
 * {@code @NotBlank}): lo valida el servicio con un único mensaje y DESPUÉS de recortar (así un motivo de 200 caracteres con
 * espacios alrededor vale y el 400 es uno solo venga de donde venga). El {@code @Size} del DTO es una red contra cuerpos
 * gigantes, bien por encima del tope del servicio.
 */
class AnulacionRequestTest {

    private static final String CORTO = "Escribí el motivo de la anulación (al menos 5 letras).";
    private static final String LARGO = "El motivo admite hasta 200 caracteres.";
    private static final Validator VALIDADOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnulacionRequest leer(String cuerpo) throws Exception {
        return JSON.readValue(cuerpo, AnulacionRequest.class);
    }

    private static Set<ConstraintViolation<AnulacionRequest>> violaciones(AnulacionRequest request) {
        return VALIDADOR.validate(request);
    }

    /** Lo que decide el servicio con el motivo del pedido (la clave del 400 y su texto), o el motivo recortado. */
    private static String servicio(AnulacionRequest request) {
        return RegistroAnulacion.validarMotivo(request.motivo());
    }

    @Test
    @DisplayName("un motivo bien escrito no tiene errores y el servicio lo recorta (  Cobro mal cargado  → Cobro mal cargado)")
    void valido() throws Exception {
        AnulacionRequest request = leer("{\"motivo\":\"  Cobro mal cargado  \"}");

        assertThat(request.motivo()).isEqualTo("  Cobro mal cargado  ");
        assertThat(violaciones(request)).isEmpty();
        assertThat(servicio(request)).isEqualTo("Cobro mal cargado");
    }

    @Test
    @DisplayName("el DTO deja pasar el motivo ausente, nulo, en blanco o corto (no usa @NotBlank): el 400 con la clave motivo sale del servicio")
    void laFormaLaDecideElServicio() throws Exception {
        for (String cuerpo : new String[] {"{}", "{\"motivo\":null}", "{\"motivo\":\"\"}", "{\"motivo\":\"    \"}",
                "{\"motivo\":\"abc\"}", "{\"motivo\":\"1234\"}", "{\"motivo\":\" abc \"}"}) {
            AnulacionRequest request = leer(cuerpo);

            assertThat(violaciones(request)).as("DTO %s", cuerpo).isEmpty();
            assertThatThrownBy(() -> servicio(request)).as("servicio %s", cuerpo).isInstanceOf(DatosInvalidosException.class)
                    .satisfies(e -> assertThat(((DatosInvalidosException) e).errors()).as(cuerpo).containsOnlyKeys("motivo")
                            .containsEntry("motivo", CORTO));
        }
    }

    @Test
    @DisplayName("5 y 200 caracteres valen, «12345» vale (S-1: sin exigir letras) y 201 da «El motivo admite hasta 200 caracteres.»")
    void bordes() throws Exception {
        assertThat(servicio(leer("{\"motivo\":\"12345\"}"))).isEqualTo("12345");
        assertThat(servicio(leer("{\"motivo\":\"" + "a".repeat(5) + "\"}"))).hasSize(5);
        assertThat(servicio(leer("{\"motivo\":\"" + "a".repeat(200) + "\"}"))).hasSize(200);

        AnulacionRequest largo = leer("{\"motivo\":\"" + "a".repeat(201) + "\"}");
        assertThatThrownBy(() -> servicio(largo)).isInstanceOf(DatosInvalidosException.class)
                .satisfies(e -> assertThat(((DatosInvalidosException) e).errors()).containsOnlyKeys("motivo")
                        .containsEntry("motivo", LARGO));
    }

    @Test
    @DisplayName("el @Size del DTO es solo una red: 200 caracteres con espacios alrededor (202 sin recortar) NO lo rompen y el servicio los acepta recortados")
    void sizeEsSoloUnaRed() throws Exception {
        AnulacionRequest conEspacios = leer("{\"motivo\":\"  " + "a".repeat(200) + "  \"}");
        AnulacionRequest justoPasado = leer("{\"motivo\":\"" + "a".repeat(201) + "\"}");
        AnulacionRequest gigante = leer("{\"motivo\":\"" + "a".repeat(1001) + "\"}");

        assertThat(violaciones(conEspacios)).as("202 sin recortar").isEmpty();
        assertThat(servicio(conEspacios)).hasSize(200);
        assertThat(violaciones(justoPasado)).as("201: lo rechaza el servicio, no el DTO").isEmpty();
        assertThat(violaciones(gigante)).hasSize(1).first().satisfies(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("motivo");
            assertThat(v.getMessage()).isEqualTo(LARGO);
        });
    }

    @Test
    @DisplayName("el motivo no lleva @NotBlank ni @NotNull ni @NotEmpty")
    void sinNotBlank() throws NoSuchFieldException {
        assertThat(AnulacionRequest.class.getRecordComponents()).extracting(RecordComponent::getName).containsExactly("motivo");
        assertThat(AnulacionRequest.class.getDeclaredField("motivo").getAnnotations())
                .extracting(a -> a.annotationType().getSimpleName()).containsExactly("Size");
    }
}
