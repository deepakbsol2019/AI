package com.helpdesk.globalexchandler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.helpdesk.custException.CustomException;

@ControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(exception = Exception.class)
	public ResponseEntity<?> handelException(Exception e){
		return new ResponseEntity<CustomException>(new CustomException(e.getMessage()),HttpStatus.INTERNAL_SERVER_ERROR);
	}

}
