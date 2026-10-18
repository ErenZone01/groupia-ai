package sn.groupia.shared.infrastructure.web;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import sn.groupia.shared.api.ApiException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> applicationFailure(ApiException ex, WebRequest request) {
        var body = problem(ex.status(), ex.code(), ex.getMessage());
        if (!ex.details().isEmpty()) {
            body.setProperty("details", ex.details());
        }
        var headers = new HttpHeaders();
        if ((ex.code().equals("AUTH_RATE_LIMITED")
                || ex.code().equals("AUTH_REFRESH_RATE_LIMITED")))
            headers.set("Retry-After", ex.details().get("retryAfterSeconds").toString());
        return handleExceptionInternal(
                ex, body, headers, HttpStatusCode.valueOf(ex.status()), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        var body = problem(400, "VALIDATION_FAILED", "La requête contient des données invalides.");
        List<FieldError> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                        .distinct()
                        .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (!(body instanceof ProblemDetail detail
                && detail.getProperties() != null
                && detail.getProperties().containsKey("code"))) {
            String code =
                    switch (status.value()) {
                        case 404 -> "RESOURCE_NOT_FOUND";
                        case 405 -> "METHOD_NOT_ALLOWED";
                        case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                        default -> "INVALID_REQUEST";
                    };
            body = problem(status.value(), code, "La requête ne peut pas être traitée.");
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpectedFailure(Exception ex) {
        // Do not log payloads, SQL parameters or exception messages containing credentials.
        logger.error("Unhandled API exception of type " + ex.getClass().getName());
        return ResponseEntity.status(500)
                .body(problem(500, "INTERNAL_ERROR", "Une erreur interne est survenue."));
    }

    private static ProblemDetail problem(int status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), message);
        detail.setProperty("code", code);
        return detail;
    }

    record FieldError(String field, String message) {}
}
