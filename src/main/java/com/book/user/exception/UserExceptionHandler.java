package com.book.user.exception;

import java.util.Map;
import com.book.user.security.AuthErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.book.user")
public class UserExceptionHandler {
    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, Object>> auth(AuthException failure) {
        return ResponseEntity.status(failure.status()).header("Cache-Control", "no-store")
                .body(AuthErrors.body(failure.status().value(), failure.code(), failure.getMessage()));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException failure) {
        int status = failure.getStatusCode().value();
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(AuthErrors.body(status, status == 401 ? "AUTHENTICATION_REQUIRED" : "ACCOUNT_REQUEST_FAILED",
                        failure.getReason() == null ? "The account request could not be completed." : failure.getReason()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformed() {
        return ResponseEntity.badRequest().body(AuthErrors.body(400, "INVALID_REQUEST", "Provide a valid JSON request."));
    }
}
