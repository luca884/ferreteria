package com.ferreteria.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.catalogo.dto.AplicarRequest;
import com.ferreteria.catalogo.dto.VistaPreviaRequest;
import com.ferreteria.common.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Handlers de la importacion de listas (CU-04): se llaman directo, sin servidor. */
class GlobalExceptionHandlerImportacionTest {

    private static final String RANGO = "La bonificación 1 debe estar entre 0 y 100.";
    private static final String DECIMALES = "La bonificación 1 puede tener como máximo 2 decimales.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unArchivoQueSuperaElMaximoEs413() {
        ResponseEntity<ApiError> respuesta = handler.archivoDemasiadoGrande(new MaxUploadSizeExceededException(10L * 1024 * 1024));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(413);
        assertThat(respuesta.getBody().message()).isEqualTo("El archivo es demasiado grande. El máximo es 10 MB.");
    }

    @Test
    void sinLaParteArchivoEsFaltaElArchivo() {
        ResponseEntity<ApiError> respuesta = handler.parteFaltante(new MissingServletRequestPartException("archivo"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("Falta el archivo de la lista.");
    }

    @Test
    void sinLaParteDatosFaltanLosDatos() {
        ResponseEntity<ApiError> respuesta = handler.parteFaltante(new MissingServletRequestPartException("datos"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("Faltan los datos de la importación.");
    }

    @Test
    void unMultipartMalFormadoEs400() {
        ResponseEntity<ApiError> respuesta = handler.multipartInvalido(new MultipartException("boundary roto"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @Test
    void unBadRequestExceptionEs400ConSuMensaje() {
        ResponseEntity<ApiError> respuesta = handler.badRequest(new BadRequestException("Las listas en PDF todavía no se pueden importar."));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("Las listas en PDF todavía no se pueden importar.");
    }

    @Test
    void unaBonificacionQueNoEsNumeroNombraLaBonificacion() {
        for (int n = 1; n <= 3; n++) {
            ResponseEntity<ApiError> respuesta = handler.unreadable(noLegible(
                    "{\"proveedorId\":1,\"bonificacion" + n + "\":\"abc\"}", VistaPreviaRequest.class));

            String mensaje = "La bonificación " + n + " no es un número válido.";
            assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
            assertThat(respuesta.getBody().message()).isEqualTo(mensaje);
            assertThat(respuesta.getBody().errors()).containsEntry("bonificacion" + n, mensaje);
        }
    }

    @Test
    void tambienEnLaSolicitudDeAplicar() {
        ResponseEntity<ApiError> respuesta = handler.unreadable(noLegible(
                "{\"proveedorId\":1,\"bonificacion2\":\"x1\"}", AplicarRequest.class));

        assertThat(respuesta.getBody().message()).isEqualTo("La bonificación 2 no es un número válido.");
    }

    @Test
    void otroCuerpoIlegibleSigueDandoElMensajeGenerico() {
        ResponseEntity<ApiError> respuesta = handler.unreadable(noLegible("{\"proveedorId\":\"zzz\"}", VistaPreviaRequest.class));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("El cuerpo del pedido no es valido");
    }

    @Test
    void conDosViolacionesEnUnCampoElRangoGanaSobreLosDecimalesSinImportarElOrden() throws Exception {
        String primero = mensajeDeBonificacion1(
                violacion("DecimalMax", RANGO), violacion("Digits", DECIMALES));
        String segundo = mensajeDeBonificacion1(
                violacion("Digits", DECIMALES), violacion("DecimalMax", RANGO));
        String conMinimo = mensajeDeBonificacion1(
                violacion("Digits", DECIMALES), violacion("DecimalMin", RANGO));

        assertThat(primero).isEqualTo(segundo).isEqualTo(conMinimo).isEqualTo(RANGO);
    }

    @Test
    void laPrioridadDelRangoNoDependeDelOrdenAlfabeticoDelTexto() throws Exception {
        // Si algun dia el texto del rango pasa a ser "mayor" que el de los decimales, el rango sigue ganando.
        String mensaje = mensajeDeBonificacion1(
                violacion("Digits", "Aaa: puede tener como máximo 2 decimales."),
                violacion("DecimalMax", "Zzz: debe estar entre 0 y 100."));

        assertThat(mensaje).isEqualTo("Zzz: debe estar entre 0 y 100.");
    }

    @Test
    void sinRangoEnJuegoGanaLaPrimeraViolacion() throws Exception {
        String primero = mensajeDeBonificacion1(violacion("NotNull", "Zzz: falta."), violacion("Digits", "Aaa: decimales."));
        String segundo = mensajeDeBonificacion1(violacion("Digits", "Aaa: decimales."), violacion("NotNull", "Zzz: falta."));

        assertThat(primero).isEqualTo("Zzz: falta.");
        assertThat(segundo).isEqualTo("Aaa: decimales.");
    }

    @Test
    void dosRangosEnElMismoCampoConservanElPrimero() throws Exception {
        String mensaje = mensajeDeBonificacion1(violacion("DecimalMin", "Zzz: rango."), violacion("DecimalMax", "Aaa: rango."));

        assertThat(mensaje).isEqualTo("Zzz: rango.");
    }

    @Test
    void cadaCampoConservaSuPropioMensaje() throws Exception {
        BeanPropertyBindingResult resultado = new BeanPropertyBindingResult(new Object(), "datos");
        resultado.addError(campo("bonificacion1", "Digits", DECIMALES));
        resultado.addError(campo("mapeo.hoja", "NotNull", "Elegí la hoja de la lista."));
        resultado.addError(campo("bonificacion1", "DecimalMax", RANGO));
        MethodParameter parametro = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);

        Map<String, String> errores = handler.validation(new MethodArgumentNotValidException(parametro, resultado))
                .getBody().errors();

        assertThat(errores).containsOnly(
                Map.entry("bonificacion1", RANGO), Map.entry("mapeo.hoja", "Elegí la hoja de la lista."));
    }

    @Test
    void unaViolacionSinCodigoNiMensajeNoRompeElHandler() throws Exception {
        BeanPropertyBindingResult resultado = new BeanPropertyBindingResult(new Object(), "datos");
        resultado.addError(new FieldError("datos", "x", null, false, null, null, null));
        MethodParameter parametro = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);

        ResponseEntity<ApiError> respuesta = handler.validation(new MethodArgumentNotValidException(parametro, resultado));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().errors()).containsEntry("x", "");
    }

    @Test
    void losDatosInvalidosValidadosEnElServicioSon400ConElMismoFormatoQueLaValidacionDelCuerpo() {
        ResponseEntity<ApiError> respuesta = handler.datosInvalidos(
                new DatosInvalidosException(Map.of("bonificacion1", RANGO)));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("Datos invalidos");
        assertThat(respuesta.getBody().errors()).containsOnly(Map.entry("bonificacion1", RANGO));
    }

    @Test
    void elCatchAllNoSeAltera() {
        ResponseEntity<ApiError> respuesta = handler.unexpected(new IllegalStateException("x"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(500);
        assertThat(respuesta.getBody().message()).isEqualTo("Error interno");
    }

    /** Una violacion: el codigo es el de la anotacion (DecimalMax, Digits...), como lo arma Spring. */
    private record Violacion(String codigo, String mensaje) {
    }

    private static Violacion violacion(String codigo, String mensaje) {
        return new Violacion(codigo, mensaje);
    }

    private static FieldError campo(String campo, String codigo, String mensaje) {
        // Spring arma los codigos de mas especifico a menos: el ultimo es el nombre de la anotacion.
        String[] codigos = {codigo + ".datos." + campo, codigo + "." + campo, codigo};
        return new FieldError("datos", campo, null, false, codigos, null, mensaje);
    }

    private String mensajeDeBonificacion1(Violacion... enOrden) throws Exception {
        BeanPropertyBindingResult resultado = new BeanPropertyBindingResult(new Object(), "datos");
        for (Violacion v : enOrden) {
            resultado.addError(campo("bonificacion1", v.codigo(), v.mensaje()));
        }
        MethodParameter parametro = new MethodParameter(Object.class.getDeclaredMethod("toString"), -1);
        return handler.validation(new MethodArgumentNotValidException(parametro, resultado))
                .getBody().errors().get("bonificacion1");
    }

    private HttpMessageNotReadableException noLegible(String json, Class<?> tipo) {
        try {
            mapper.readValue(json, tipo);
        } catch (Exception e) {
            return new HttpMessageNotReadableException("JSON parse error", e, (HttpInputMessage) null);
        }
        throw new AssertionError("El JSON debia ser invalido");
    }
}
