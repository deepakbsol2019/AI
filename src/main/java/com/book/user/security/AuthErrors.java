package com.book.user.security;

import java.io.IOException;
import java.util.Map;
import com.book.user.exception.AuthException;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

public final class AuthErrors {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private AuthErrors() { }
    public static Map<String, Object> body(int status, String code, String detail) {
        return Map.of("status", status, "code", code, "detail", detail);
    }
    public static void write(HttpServletResponse response, AuthException failure) throws IOException {
        write(response, failure.status().value(), failure.code(), failure.getMessage());
    }
    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(JSON.writeValueAsString(body(status, code, detail)));
    }
}
