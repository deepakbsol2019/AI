package com.exception.handle.exchandler;

import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.exception.handle.ai.model.ExceptionAnalysis;
import com.exception.handle.ai.pipeline.AiStepByStepExecution;

@RestControllerAdvice
public class ConveterExceptionHandler {
	
	private final TaskExecutor taskExecutor;
	private final AiStepByStepExecution aiStepByStepExecution;
	
    public ConveterExceptionHandler(
    		AiStepByStepExecution aiStepByStepExecution,
            @Qualifier("taskExecutor")
            TaskExecutor taskExecutor) {

        this.aiStepByStepExecution = aiStepByStepExecution;
        this.taskExecutor = taskExecutor;
    }

	@ExceptionHandler(exception = Exception.class)
	public void handleException(Exception exc) {
		
		System.out.println("Global exception handler: " + exc);
	    System.out.println("Message: " + exc.getMessage());

	    StackTraceElement[] stackTrace = exc.getStackTrace();

	    if (stackTrace.length > 0) {
	        StackTraceElement origin = stackTrace[0];

	        System.out.println("File name   : " + origin.getFileName());
	        System.out.println("Line number : " + origin.getLineNumber());
	        System.out.println("Class name  : " + origin.getClassName());
	        System.out.println("Method name : " + origin.getMethodName());
	    } else {
	        System.out.println("Source location is unavailable.");
	    }
		CompletableFuture<ExceptionAnalysis> response = CompletableFuture.supplyAsync(()->getAiExceptionResponse(exc));
		ExceptionAnalysis output = response.join();
		System.out.println("output::: "+output);
	}

	private ExceptionAnalysis getAiExceptionResponse(Exception exc) {
		return aiStepByStepExecution.getAiExceptionFix(exc);
	}
	
	
	
}
