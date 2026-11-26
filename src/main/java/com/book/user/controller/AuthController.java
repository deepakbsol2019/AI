package com.book.user.controller;

import java.util.Map;
import com.book.user.config.AuthSettings;
import com.book.user.dto.SessionResponse;
import com.book.user.dto.SessionResponse.SessionUser;
import com.book.user.exception.AuthException;
import com.book.user.security.AuthCookies;
import com.book.user.security.UserPrincipal;
import com.book.user.service.MfaService;
import com.book.user.service.PasswordService;
import com.book.user.service.SessionService;
import com.book.user.service.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/auth")
public class AuthController {
	private final PasswordService passwords;
	private final MfaService mfa;
	private final SessionService sessions;
	private final AuthCookies cookies;
	private final AuthSettings settings;
	private final SecurityAuditService audit;

	public AuthController(PasswordService passwords, MfaService mfa, SessionService sessions, AuthCookies cookies,
			AuthSettings settings, SecurityAuditService audit) {
		this.passwords = passwords;
		this.mfa = mfa;
		this.sessions = sessions;
		this.cookies = cookies;
		this.settings = settings;
		this.audit = audit;
	}

	@GetMapping("/features")
	public Map<String, Boolean> features() {
		return Map.of("googleEnabled", settings.googleEnabled(), "ssoEnabled", settings.ssoEnabled());
	}

	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-store");
		return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
	}

	@PostMapping("/login")
	public SessionResponse login(@RequestBody Login input, HttpServletResponse response) {
		try {
			var identity = mfa.complete(passwords.authenticate(input.email(), input.password()), input.mfaCode());
			var issued = sessions.issue(identity);
			cookies.write(response, issued.refreshToken(), issued.response().refreshExpiresAt());
			return issued.response();
		} catch (RuntimeException failure) {
			audit.record(null, null, "LOGIN_FAILURE", "FAILURE", null);
			throw failure;
		}
	}

	@PostMapping("/refresh")
	public SessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
		try {
			var issued = sessions.refresh(cookies.read(request));
			cookies.write(response, issued.refreshToken(), issued.response().refreshExpiresAt());
			return issued.response();
		} catch (AuthException failure) {
			cookies.clear(response);
			throw failure;
		}
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		try {
			sessions.logout(cookies.read(request));
		} finally {
			cookies.clear(response);
		}
	}

	@GetMapping("/me")
	public SessionUser me(@AuthenticationPrincipal UserPrincipal principal) {
		if (principal == null)
			throw AuthException.unauthorized();
		return sessions.user(principal);
	}

	public record Login(String email, String password, String mfaCode) {
		@Override
		public String toString() {
			return "Login[REDACTED]";
		}
	}
}
