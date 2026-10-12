package com.gemini.exceptionalHandler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gemini.custexception.CustException;
@RestControllerAdvice
public class GeminiExceptionHandler {
	
	@ExceptionHandler(exception = CustException.class)
	public ResponseEntity<String> custException(CustException custException){
		return new ResponseEntity<String>(custException.getMessage(),HttpStatus.INTERNAL_SERVER_ERROR);
	}

}
