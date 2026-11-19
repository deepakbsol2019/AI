package com.exception.handle.exchandler;

import java.util.IdentityHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.exception.handle.ai.model.ExceptionAnalysis;
import com.exception.handle.ai.model.RepairData.RepairResult;
import com.exception.handle.ai.pipeline.AiStepByStepExecution;
import com.exception.handle.custexception.InvalidNumberException;
import com.exception.handle.repair.service.impl.RepairWorkerService;
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ConveterExceptionHandler {

	private static final Logger log =
			LoggerFactory.getLogger(ConveterExceptionHandler.class);
	public record ErrorBody(String message, RepairResult repair) {}
	private final TaskExecutor taskExecutor;
	private final AiStepByStepExecution aiStepByStepExecution;
	private final ObjectProvider<RepairWorkerService> workers;
	public ConveterExceptionHandler(
			AiStepByStepExecution aiStepByStepExecution,
			@Qualifier("taskExecutor")
			TaskExecutor taskExecutor,
			ObjectProvider<RepairWorkerService> workers) {

		this.aiStepByStepExecution = aiStepByStepExecution;
		this.taskExecutor = taskExecutor;
		this.workers=workers;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorBody> unexpected(Exception failure) {
		ResponseEntity<ErrorBody> clientError = knownClientError(failure);
		if (clientError != null) {
			return clientError;
		}
		log.error("Unhandled request failure", failure);
		RepairWorkerService worker = workers.getIfAvailable();
		RepairResult repair = worker == null
				? RepairResult.of("DISABLED", "Automatic source repair is disabled for this runtime.")
						: worker.repair(failure);
				return ResponseEntity.internalServerError()
						.body(new ErrorBody("An unexpected error occurred.", repair));
	}

	private static ResponseEntity<ErrorBody> knownClientError(Throwable failure) {
		var seen = new IdentityHashMap<Throwable, Boolean>();
		for (Throwable current = failure; current != null && !seen.containsKey(current);
				current = current.getCause()) {
			seen.put(current, Boolean.TRUE);
			if (current instanceof InvalidNumberException domain) {
				return domainError(domain);
			}
			RepairResult skipped = RepairResult.of("SKIPPED",
					"An explicit HTTP client error was returned; no repair was attempted.");
			if (current instanceof ErrorResponse response) {
				if (!response.getStatusCode().is4xxClientError()) return null;
				String detail = response.getBody().getDetail();
				return
						ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
						.body(new ErrorBody(detail == null ? "The request was rejected." :
							detail, skipped));
			}
			ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(
					current.getClass(), ResponseStatus.class);
			if (status != null) {
				if (!status.code().is4xxClientError()) return null;
				String detail = status.reason().isBlank() ? "The request was rejected." :
					status.reason();
				return ResponseEntity.status(status.code()).body(new ErrorBody(detail,
						skipped));
			}
		}
		return null;
	}

	private static ResponseEntity<ErrorBody> domainError(InvalidNumberException failure) {
		ErrorBody errorBody = new ErrorBody(failure.getMessage(), 
				RepairResult.of("SKIPPED", "Domain validation rejected the request; no repair was attempted."));
		return ResponseEntity.badRequest().body(errorBody);
	}



	private ExceptionAnalysis getAiExceptionResponse(Exception exc) {
		 ExceptionAnalysis aiExceptionFix = aiStepByStepExecution.getAiExceptionFix(exc);
		 System.out.println("Ai Response ::"+aiExceptionFix);
		 return aiExceptionFix;
	}



}
