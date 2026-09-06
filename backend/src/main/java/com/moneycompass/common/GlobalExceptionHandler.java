package com.moneycompass.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves this application as an RFC 7807 problem detail.
 *
 * <p>{@code ProblemDetail} is built into Spring Framework, so there is no DTO
 * to maintain and the {@code application/problem+json} content type is set for
 * us.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "https://moneycompass.app/problems/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getTitle());
        problem.setType(URI.create(BASE_TYPE + slug(ex.getTitle())));
        return problem;
    }

    /** Bean validation failures, one entry per rejected field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.put(fieldError.getField(), fieldError.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(BASE_TYPE + "validation-failed"));
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Catch-all. The detail deliberately says nothing specific: an unhandled
     * exception message can leak a SQL statement or a file path. The stack
     * trace goes to the log instead.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side");
        problem.setTitle("Internal server error");
        problem.setType(URI.create(BASE_TYPE + "internal-error"));
        return problem;
    }

    private static String slug(String title) {
        return title.toLowerCase().replace(' ', '-');
    }
}
