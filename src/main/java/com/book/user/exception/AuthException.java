package com.book.user.exception;

import org.springframework.http.HttpStatus;

/** Carries public, deliberately non-secret error details across security and MVC boundaries. */
public class AuthException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AuthException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static AuthException unauthorized() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication has expired or is invalid. Please log in again.");
    }
}
