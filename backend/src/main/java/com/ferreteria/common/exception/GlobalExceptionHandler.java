package com.ferreteria.common.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ferreteria.auth.service.CredencialesInvalidasException;
import com.ferreteria.common.ApiError;
import com.ferreteria.tenant.MissingTenantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Mensajes propios para los campos enumerados del articulo; el resto usa el mensaje generico. */
    private static final Map<String, String> MENSAJES_ENUM_POR_CAMPO = Map.of(
            "alicuotaIva", "La alícuota de IVA no es válida. Usá 0, 2,5, 5, 10,5, 21 o 27.",
            "unidad", "La unidad no es válida. Usá UNIDAD, METRO, KILO o LITRO.",
            "moneda", "La moneda no es válida. Usá ARS o USD.",
            "medioPago", "El medio de pago no es válido. Usá EFECTIVO, DEBITO, CREDITO, TRANSFERENCIA o CUENTA_CORRIENTE.",
            "medio", "El medio de pago no es válido. Usá EFECTIVO, DEBITO, CREDITO, TRANSFERENCIA o CUENTA_CORRIENTE.",
            "motivo", "El motivo no es válido. Usá INGRESO_MERCADERIA, ROTURA, CONTEO u OTRO.",
            "modo", "El modo no es válido. Usá STOCK_REAL o CANTIDAD.",
            "tipo", "El tipo de movimiento no es válido. Usá INGRESO, GASTO o RETIRO.");

    private static final Pattern BONIFICACION = Pattern.compile("bonificacion([123])");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ReglaDeNegocioException.class)
    public ResponseEntity<ApiError> reglaDeNegocio(ReglaDeNegocioException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        if (ex.errors().isEmpty()) {
            return build(HttpStatus.CONFLICT, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage(), ex.errors()));
    }

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<ApiError> credenciales(CredencialesInvalidasException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "No tenés permiso para hacer esto");
    }

    /** Un usuario sin ferreteria (super admin) pidiendo datos de una ferreteria. */
    @ExceptionHandler(MissingTenantException.class)
    public ResponseEntity<ApiError> missingTenant(MissingTenantException ex) {
        return build(HttpStatus.FORBIDDEN, "Este recurso requiere una ferretería");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        // Dos violaciones en un mismo campo: el rango (DecimalMin/DecimalMax) gana sobre Digits y, si no,
        // gana la primera. Ver ErroresDeValidacion.
        ErroresDeValidacion errores = new ErroresDeValidacion();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errores.agregar(e.getField(), e.getCode(), e.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Datos inválidos", errores.mensajes()));
    }

    /** Datos validados en el servicio: mismo formato que la validacion del cuerpo. */
    @ExceptionHandler(DatosInvalidosException.class)
    public ResponseEntity<ApiError> datosInvalidos(DatosInvalidosException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Datos inválidos", ex.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException formato) {
            if (formato.getTargetType() != null && formato.getTargetType().isEnum()) {
                return enumInvalido(formato);
            }
            Matcher bonificacion = BONIFICACION.matcher(ultimoCampo(formato));
            if (BigDecimal.class.equals(formato.getTargetType()) && bonificacion.matches()) {
                String mensaje = "La bonificación " + bonificacion.group(1) + " no es un número válido.";
                return ResponseEntity.badRequest().body(ApiError.of(
                        HttpStatus.BAD_REQUEST.value(), mensaje, Map.of(ultimoCampo(formato), mensaje)));
            }
        }
        return build(HttpStatus.BAD_REQUEST, "El cuerpo del pedido no es válido");
    }

    /** Un valor que no es de la enumeracion del campo (alicuota de IVA, unidad...): el mensaje nombra el campo. */
    private ResponseEntity<ApiError> enumInvalido(InvalidFormatException ex) {
        String campo = ultimoCampo(ex);
        String mensaje = MENSAJES_ENUM_POR_CAMPO.getOrDefault(campo, mensajeGenericoDeEnum(campo, ex));
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), mensaje, Map.of(campo, mensaje)));
    }

    private static String ultimoCampo(InvalidFormatException ex) {
        if (ex.getPath().isEmpty() || ex.getPath().get(ex.getPath().size() - 1).getFieldName() == null) {
            return "valor";
        }
        return ex.getPath().get(ex.getPath().size() - 1).getFieldName();
    }

    private static String mensajeGenericoDeEnum(String campo, InvalidFormatException ex) {
        String validos = Arrays.stream(ex.getTargetType().getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return "El valor de " + campo + " no es válido. Usá uno de estos: " + validos + ".";
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Mas especifico que MultipartException (su padre), asi que Spring lo elige primero. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> archivoDemasiadoGrande(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo es demasiado grande. El máximo es 25 MB.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> parteFaltante(MissingServletRequestPartException ex) {
        String mensaje = "archivo".equals(ex.getRequestPartName())
                ? "Falta el archivo de la lista."
                : "Faltan los datos de la importación.";
        return build(HttpStatus.BAD_REQUEST, mensaje);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> multipartInvalido(MultipartException ex) {
        return build(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Recurso no encontrado");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "Parámetro inválido: " + ex.getName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado");
    }

    /** Dos altas simultaneas que chocan contra una restriccion UNIQUE de la base. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integridad(DataIntegrityViolationException ex) {
        // Un valor que no entra en la columna no es un duplicado: es un 400, no un "ya existe".
        if (ErrorDeBase.es(ex, ErrorDeBase.FUERA_DE_RANGO)) {
            return build(HttpStatus.BAD_REQUEST, "Hay un valor demasiado grande.");
        }
        return build(HttpStatus.CONFLICT, "El dato ya existe o entra en conflicto con otro");
    }

    /**
     * Bloqueo optimista del articulo: otra escritura confirmo antes. Es la red de los choques que salen al confirmar
     * (la cascada del rubro, la baja, la reactivacion, el tercer intento del recalculo); los servicios que hacen
     * flush traducen el suyo antes de llegar aca.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> versionChocada(OptimisticLockingFailureException ex) {
        return build(HttpStatus.CONFLICT, ArticuloModificado.MENSAJE);
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
