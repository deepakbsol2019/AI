package com.exception.handle.repair.service.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.exception.handle.ai.model.RepairData.ApplyReceipt;
import com.exception.handle.ai.model.RepairData.PatchProposal;
import com.exception.handle.ai.model.RepairData.RepairInput;
import com.exception.handle.ai.model.RepairData.RepairResult;
import com.exception.handle.ai.model.RepairData.SourceSnapshot;
import com.exception.handle.ai.pipeline.RepairAiClient;

public final class RepairWorkerService {

	private static final System.Logger log =
			System.getLogger(RepairWorkerService.class.getName());
	private static final int TRACE_LIMIT = 24_000;
	private final SourceFileAccess files;
	private final RepairAiClient ai;
	private final int maxAttemptsPerError;
	private final int maxDistinctErrors;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final Map<String, Integer> attempts = new HashMap<>();
	// Accessed only while busy is held; a new process starts a new repair session.
	private boolean needsReload;

	public RepairWorkerService(SourceFileAccess files, RepairAiClient ai,
			int maxAttemptsPerError, int maxDistinctErrors) {
		this.files = Objects.requireNonNull(files);
		this.ai = Objects.requireNonNull(ai);
		if (maxAttemptsPerError < 1 || maxDistinctErrors < 1) {
			throw new IllegalArgumentException("Repair attempt limits must be positive");
		}
		this.maxAttemptsPerError = maxAttemptsPerError;
		this.maxDistinctErrors = maxDistinctErrors;
	}

	public RepairResult repair(Throwable failure) {
		if (!busy.compareAndSet(false, true)) {
			return RepairResult.of("BUSY", "Another repair attempt is running.");
		}
		try {
			if (needsReload) {
				return RepairResult.of("RELOAD_REQUIRED", "Source was already updated. Review, compile, test, and restart before another repair.");
			}
			if (failure == null) {
				return RepairResult.of("SKIPPED", "No exception was supplied.");
			}
			String stackTrace = trace(failure);
			if (stackTrace.length() > TRACE_LIMIT) {
				return RepairResult.of("SKIPPED", "The stack trace exceeds the 24000-character limit; no AI call was made.");
			}
			SourceSnapshot source = files.read(failure);
			if (source == null) {
				return RepairResult.of("SKIPPED",
						"No readable application source matched an allowed stack frame.");
			}
			Throwable cause = deepestCause(failure);
			String key = fingerprint(source, cause.getClass().getName());
			int count = attempts.getOrDefault(key, 0);
			if (count >= maxAttemptsPerError) {
				return RepairResult.of("RETRY_LIMIT", "The AI attempt limit for this error and source version was reached.");
			}
			if (count == 0 && attempts.size() >= maxDistinctErrors) {
				return RepairResult.of("LIMIT_REACHED",
						"The distinct-error limit for this process was reached.");
			}
			RepairInput input = new RepairInput(source.className(), source.method(),
					source.line(), cause.getClass().getName(), stackTrace,
					source.source(), files.context(failure, source));

			// Count only calls submitted to the AI, including provider failures.
			attempts.put(key, count + 1);
			PatchProposal proposal = ai.suggest(input);
			String invalid = invalidProposal(proposal);
			if (invalid != null) {
				return RepairResult.of("REJECTED", invalid);
			}
			if (!proposal.changeRequired()) {
				return RepairResult.of("NO_CHANGE", proposal.reason());
			}
			if (source.source().equals(proposal.correctedSource())) {
				return RepairResult.of("NO_CHANGE", "The proposed source is identical to the current source.");
			}
			
			ApplyReceipt receipt;
			try {
				receipt = files.apply(source, proposal.correctedSource());
			} catch (IllegalArgumentException rejected) {
				log.log(System.Logger.Level.WARNING, "Proposed repair failed local source validation", rejected);
						return RepairResult.of("REJECTED", "Proposed source failed local validation. Inspect the logs.");
			}
			// Metadata failures still mean source changed, so also close the reload gate.
			needsReload = true;
			String message = proposal.reason()
					+ " Source updated; review, compile, test, and restart before retrying the request.";
					if (!receipt.indexed()) {
						message += " History metadata is incomplete: "
								+ Objects.toString(receipt.warning(), "inspect the backup directory and logs");
					}
			return new RepairResult(receipt.indexed() ? "SOURCE_UPDATED" :
				"SOURCE_UPDATED_METADATA_WARNING",
				message, path(receipt.backup()), path(receipt.record()));
		} catch (Exception repairFailure) {
			log.log(System.Logger.Level.ERROR,

					"Automatic repair did not complete; inspect the source and backup",
					repairFailure);
			return RepairResult.of("FAILED",
					"Repair did not complete. Inspect application logs, current source, and backups before retrying.");
		} finally {
			busy.set(false);
		}
	}

	private static String invalidProposal(PatchProposal proposal) {
		if (proposal == null || proposal.changeRequired() == null) {
			return "The AI response must include a boolean changeRequired field.";
		}
		if (proposal.reason() == null || proposal.reason().isBlank()) {
			return "The AI response must explain its decision in a nonblank reason field.";
		}
		String corrected = proposal.correctedSource();
		if (proposal.changeRequired() && (corrected == null || corrected.isBlank())) {
			return "A proposed change must contain the complete Java source.";
		}
		if (!proposal.changeRequired() && corrected != null && !corrected.isBlank()) {
			return "A no-change decision must leave correctedSource empty or null.";
		}
		if (corrected != null && corrected.stripLeading().startsWith("```")) {
			return "The replacement must contain Java source without Markdown fences.";
		}
		return null;
	}

	private static Throwable deepestCause(Throwable failure) {
		var seen = new IdentityHashMap<Throwable, Boolean>();
		Throwable current = failure;
		seen.put(current, Boolean.TRUE);
		while (current.getCause() != null && !seen.containsKey(current.getCause())) {
			current = current.getCause();
			seen.put(current, Boolean.TRUE);
		}
		return current;
	}

	private static String fingerprint(SourceSnapshot source, String causeType) throws
	Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (String part : new String[]{source.source(), source.className(), source.method(),
				Integer.toString(source.line()), causeType}) {
			digest.update(part.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String trace(Throwable failure) {
		StringWriter writer = new StringWriter();

				failure.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	private static String path(Path path) {
		return path == null ? null : path.toString();
	}
}

