package com.book.extraction;

/** The PDF is readable, but its contents cannot safely define a complete topic partition. */
public class ContentsUnavailableException extends RuntimeException {
    public ContentsUnavailableException(String message) {
        super(message);
    }
}
