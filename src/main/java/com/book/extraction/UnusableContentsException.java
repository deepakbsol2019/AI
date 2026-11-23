package com.book.extraction;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Model/source extraction quality failure, distinct from provider, database, or filesystem failures. */
public class UnusableContentsException extends ResponseStatusException {
    public UnusableContentsException(String reason) {
        super(HttpStatus.BAD_GATEWAY, reason);
    }

    public UnusableContentsException(String reason, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, reason, cause);
    }
}
