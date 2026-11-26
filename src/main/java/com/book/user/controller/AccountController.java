package com.book.user.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.service.PasswordService;
import com.book.user.service.PasswordService.Registration;

@RestController
@RequestMapping("/api/user/auth")
public class AccountController {
	private final PasswordService passwords;

	public AccountController(PasswordService passwords) {
		this.passwords = passwords;
	}

	@PostMapping("/register")
	public ResponseEntity<Map<String, String>> register(@RequestBody Registration request) {
		passwords.register(request);
		return ResponseEntity.accepted().body(Map.of("message",
				"Registration received. Password accounts receive a setup email; company SSO requires administrator provider setup."));
	}

	@PostMapping("/password/forgot")
	public ResponseEntity<Map<String, String>> forgot(@RequestBody Email request) {
		passwords.requestReset(request.email());
		return ResponseEntity.accepted()
				.body(Map.of("message", "If an eligible account exists, a password reset email will be sent."));
	}

	@PostMapping("/password/setup")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setup(@RequestBody Password request) {
		passwords.completeSetup(request.token(), request.password());
	}

	@PostMapping("/password/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reset(@RequestBody ResetPassword request) {
		passwords.completeReset(request.token(), request.password(), request.confirmation());
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ProblemDetail> failure(ResponseStatusException failure) {
		var problem = ProblemDetail.forStatusAndDetail(failure.getStatusCode(),
				failure.getReason() == null ? "This account request could not be completed." : failure.getReason());
		problem.setProperty("code",
				failure.getStatusCode().value() == 400 ? "INVALID_ACCOUNT_REQUEST" : "ACCOUNT_REQUEST_FAILED");
		return ResponseEntity.status(failure.getStatusCode()).header("Cache-Control", "no-store").body(problem);
	}

	public record Email(String email) {
	}

	public record ResetPassword(String token, String password, String confirmation) {
		@Override
		public String toString() {
			return "ResetPassword[REDACTED]";
		}
	}

	public record Password(String token, String password) {
		@Override
		public String toString() {
			return "Password[token=REDACTED,password=REDACTED]";
		}
	}
}
