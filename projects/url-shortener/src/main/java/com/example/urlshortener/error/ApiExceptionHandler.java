package com.example.urlshortener.error;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.urlshortener.admission.AdmissionDeniedException;
import com.example.urlshortener.link.LinkNotFoundException;
import com.example.urlshortener.url.InvalidUrlException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Renders every failure as an api-contract.md §4 problem body carrying an {@link ErrorCode}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} routes Spring MVC's own failures (bad JSON,
 * wrong media type, wrong method, no handler) through {@link #handleExceptionInternal} too, so
 * they get the same shape as application failures. Every response is built in that one method.
 *
 * <p>No response body ever carries an exception message, SQL state, or stack trace. A 5xx body
 * carries an {@code errorId} instead, logged beside the stack trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final int DATASTORE_RETRY_AFTER_SECONDS = 5;

    private static final String API_PATH_PREFIX = "/api/";

    private static final String NOT_FOUND_PAGE = "static/404.html";

    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    /** Bounds the caller-supplied field name echoed in an unknown-field detail. */
    private static final int MAX_ECHOED_FIELD_NAME_LENGTH = 64;

    private final String notFoundPage;

    public ApiExceptionHandler() {
        try {
            notFoundPage = new ClassPathResource(NOT_FOUND_PAGE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load " + NOT_FOUND_PAGE, e);
        }
    }

    /** One field-level validation failure; the element type of the problem body's {@code errors}. */
    record FieldViolation(String field, String message) {
    }

    /**
     * Everything the base class does not already route to {@link #handleExceptionInternal}:
     * the application's own exceptions, datastore failures, and anything unexpected.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleApplicationException(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ErrorCode code = errorCodeFor(ex, statusCode);

        // The framework's headers carry Allow (405) and Accept (415), and may be read-only.
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);

        if (code == ErrorCode.SLUG_NOT_FOUND && prefersHtml(servletRequest)) {
            responseHeaders.setContentType(HTML_UTF8);
            return super.handleExceptionInternal(ex, notFoundPage, responseHeaders, code.status(), request);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(servletRequest.getRequestURI()));
        problem.setProperty("code", code.name());

        if (ex instanceof MethodArgumentNotValidException invalid) {
            problem.setProperty("errors", fieldViolations(invalid));
        }
        if (ex instanceof HttpMessageNotReadableException
                && ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            problem.setDetail("Unrecognised field '" + truncate(unknown.getPropertyName()) + "'.");
        }
        if (ex instanceof AdmissionDeniedException denied) {
            responseHeaders.set(HttpHeaders.RETRY_AFTER, Integer.toString(denied.getRetryAfterSeconds()));
        }
        if (code == ErrorCode.SERVICE_UNAVAILABLE) {
            responseHeaders.set(HttpHeaders.RETRY_AFTER, Integer.toString(DATASTORE_RETRY_AFTER_SECONDS));
        }
        if (code.status().is5xxServerError()) {
            String errorId = UUID.randomUUID().toString();
            problem.setProperty("errorId", errorId);
            log.error("errorId={} {} {} failed with {}", errorId, servletRequest.getMethod(),
                    servletRequest.getRequestURI(), code, ex);
        }

        // Preset so an Accept of application/json still gets problem+json (api-contract.md §5.8).
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(ex, problem, responseHeaders, code.status(), request);
    }

    private static ErrorCode errorCodeFor(Exception ex, HttpStatusCode frameworkStatus) {
        return switch (ex) {
            case MethodArgumentNotValidException invalid -> beanValidationCode(invalid);
            case HttpMessageNotReadableException unreadable -> ErrorCode.REQUEST_BODY_MALFORMED;
            case HttpMediaTypeNotSupportedException unsupported -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case HttpRequestMethodNotSupportedException notAllowed -> ErrorCode.METHOD_NOT_ALLOWED;
            case InvalidUrlException invalidUrl -> ErrorCode.valueOf(invalidUrl.getCode().name());
            case LinkNotFoundException notFound -> ErrorCode.SLUG_NOT_FOUND;
            // A path that cannot be a slug never reaches the redirect handler; the contract makes
            // that miss indistinguishable from an unknown slug (api-contract.md §2.2).
            case NoResourceFoundException noResource -> ErrorCode.SLUG_NOT_FOUND;
            case NoHandlerFoundException noHandler -> ErrorCode.SLUG_NOT_FOUND;
            case AdmissionDeniedException denied -> ErrorCode.RATE_LIMITED;
            case CannotGetJdbcConnectionException noConnection -> ErrorCode.SERVICE_UNAVAILABLE;
            case DataAccessResourceFailureException resourceFailure -> ErrorCode.SERVICE_UNAVAILABLE;
            case QueryTimeoutException timeout -> ErrorCode.SERVICE_UNAVAILABLE;
            // What an @Transactional service method throws when the pool cannot supply a
            // connection: the datastore is unreachable before any repository call is made.
            case CannotCreateTransactionException noTransaction -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> byFrameworkStatus(frameworkStatus);
        };
    }

    /**
     * The registry has no code for the remaining framework failures (406, 413, a missing
     * parameter). A client error becomes a 400, keeping each code bound to its one status.
     */
    private static ErrorCode byFrameworkStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.SLUG_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.REQUEST_BODY_MALFORMED : ErrorCode.INTERNAL_ERROR;
        };
    }

    /** The request body has one validated field, {@code url}; the violated constraint picks the code. */
    private static ErrorCode beanValidationCode(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        if (first == null) {
            return ErrorCode.REQUEST_BODY_MALFORMED;
        }
        return switch (first.getCode()) {
            case "NotBlank", "NotNull", "NotEmpty" -> ErrorCode.URL_MISSING;
            case "Size" -> ErrorCode.URL_TOO_LONG;
            case null, default -> ErrorCode.REQUEST_BODY_MALFORMED;
        };
    }

    /** Constraint messages only; the rejected value is caller input and is never echoed. */
    private static List<FieldViolation> fieldViolations(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
    }

    /**
     * Whether the client ranks HTML above JSON. Only explicit types count: a bare wildcard
     * (curl's default) matches both equally and gets problem+json. The {@code /api/} surface is
     * JSON-first and never negotiates to HTML.
     */
    private static boolean prefersHtml(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (path.startsWith(API_PATH_PREFIX) || accept == null) {
            return false;
        }
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(accept);
        } catch (InvalidMediaTypeException malformedAcceptHeader) {
            return false;
        }
        double htmlQuality = bestQuality(accepted, type -> type.includes(MediaType.TEXT_HTML));
        double jsonQuality = bestQuality(accepted,
                type -> type.includes(MediaType.APPLICATION_JSON) || type.includes(MediaType.APPLICATION_PROBLEM_JSON));
        return htmlQuality > 0 && htmlQuality > jsonQuality;
    }

    private static double bestQuality(List<MediaType> accepted, Predicate<MediaType> matches) {
        return accepted.stream()
                .filter(type -> !type.isWildcardType())
                .filter(matches)
                .mapToDouble(MediaType::getQualityValue)
                .max()
                .orElse(0);
    }

    private static String truncate(String callerSupplied) {
        return callerSupplied.length() <= MAX_ECHOED_FIELD_NAME_LENGTH
                ? callerSupplied
                : callerSupplied.substring(0, MAX_ECHOED_FIELD_NAME_LENGTH) + "...";
    }
}
