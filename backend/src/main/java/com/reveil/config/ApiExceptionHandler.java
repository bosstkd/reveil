package com.reveil.config;

import com.reveil.auth.EmailAlreadyUsedException;
import com.reveil.auth.InvalidCredentialsException;
import com.reveil.event.EventNotFoundException;
import com.reveil.event.InvalidListQueryException;
import com.reveil.photo.InvalidPhotoException;
import com.reveil.photo.PhotoNotFoundException;
import com.reveil.session.InvalidRefreshTokenException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Format d'erreur commun : { "message": ..., "errors": [{ "field", "message" }] }. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record FieldError(String field, String message) {
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<Map<String, Object>> emailAlreadyUsed(EmailAlreadyUsedException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), List.of(new FieldError("email", e.getMessage())));
    }

    /** Message générique, sans champ en cause (ne révèle pas si l'e-mail existe). */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(InvalidCredentialsException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<Map<String, Object>> invalidRefreshToken(InvalidRefreshTokenException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidListQueryException.class)
    public ResponseEntity<Map<String, Object>> invalidListQuery(InvalidListQueryException e) {
        return body(HttpStatus.BAD_REQUEST, "Paramètres invalides", List.of(new FieldError(e.getField(), e.getMessage())));
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<Map<String, Object>> eventNotFound(EventNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(PhotoNotFoundException.class)
    public ResponseEntity<Map<String, Object>> photoNotFound(PhotoNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(InvalidPhotoException.class)
    public ResponseEntity<Map<String, Object>> invalidPhoto(InvalidPhotoException e) {
        return body(e.getStatus(), e.getMessage(), List.of(new FieldError("file", e.getMessage())));
    }

    /** Requête multipart au-delà de spring.servlet.multipart.max-file-size / max-request-size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException e) {
        String message = "Photo trop volumineuse";
        return body(HttpStatus.PAYLOAD_TOO_LARGE, message, List.of(new FieldError("file", message)));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> badMultipart(MultipartException e) {
        return body(HttpStatus.BAD_REQUEST, "Requête multipart invalide", List.of());
    }

    /** Paramètre de requête non convertible (ex. {@code page=abc}). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "Paramètres invalides",
                List.of(new FieldError(e.getName(), "Valeur invalide")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, "Données invalides", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "Requête illisible", List.of());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, List<FieldError> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("errors", errors);
        return ResponseEntity.status(status).body(body);
    }
}
