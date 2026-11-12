package com.exception.handle.exchandler;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ConveterExceptionHandler {

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
		
	}
}
