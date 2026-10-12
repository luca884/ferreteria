package com.ferreteria.common.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ferreteria.auth.service.CredencialesInvalidasException;
import com.ferreteria.common.ApiError;
import com.ferreteria.tenant.MissingTenantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Mensajes propios para los campos enumerados del articulo; el resto usa el mensaje generico. */
    private static final Map<String, String> MENSAJES_ENUM_POR_CAMPO = Map.of(
            "alicuotaIva", "La alícuota de IVA no es válida. Usá 0, 2,5, 5, 10,5, 21 o 27.",
            "unidad", "La unidad no es válida. Usá UNIDAD, METRO, KILO o LITRO.");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<ApiError> credenciales(CredencialesInvalidasException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "No tenes permiso para hacer esto");
    }

    /** Un usuario sin ferreteria (super admin) pidiendo datos de una ferreteria. */
    @ExceptionHandler(MissingTenantException.class)
    public ResponseEntity<ApiError> missingTenant(MissingTenantException ex) {
        return build(HttpStatus.FORBIDDEN, "Este recurso requiere una ferreteria");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Datos invalidos", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException formato
                && formato.getTargetType() != null && formato.getTargetType().isEnum()) {
            return enumInvalido(formato);
        }
        return build(HttpStatus.BAD_REQUEST, "El cuerpo del pedido no es valido");
    }

    /** Un valor que no es de la enumeracion del campo (alicuota de IVA, unidad...): el mensaje nombra el campo. */
    private ResponseEntity<ApiError> enumInvalido(InvalidFormatException ex) {
        String campo = ex.getPath().isEmpty() ? "valor" : ex.getPath().get(ex.getPath().size() - 1).getFieldName();
        String mensaje = MENSAJES_ENUM_POR_CAMPO.getOrDefault(campo, mensajeGenericoDeEnum(campo, ex));
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), mensaje, Map.of(campo, mensaje)));
    }

    private static String mensajeGenericoDeEnum(String campo, InvalidFormatException ex) {
        String validos = Arrays.stream(ex.getTargetType().getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return "El valor de " + campo + " no es válido. Usá uno de estos: " + validos + ".";
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Recurso no encontrado");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Metodo no permitido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "Parametro invalido: " + ex.getName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado");
    }

    /** Dos altas simultaneas que chocan contra una restriccion UNIQUE de la base. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integridad(DataIntegrityViolationException ex) {
        return build(HttpStatus.CONFLICT, "El dato ya existe o entra en conflicto con otro");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), message));
    }
}
