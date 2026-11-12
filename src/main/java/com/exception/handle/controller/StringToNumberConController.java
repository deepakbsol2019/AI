package com.exception.handle.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exception.handle.service.StringToNumberConveter;
@RestController
@RequestMapping("/api")
public class StringToNumberConController {

private final StringToNumberConveter stringToNumberConveter;
	
	public StringToNumberConController(StringToNumberConveter stringToNumberConveter) {
		this.stringToNumberConveter=stringToNumberConveter;
	}
	@GetMapping("/convert-int")
	public Integer convertToInt(@RequestParam("input") String inputString) {
		return stringToNumberConveter.convert(inputString);
		
	}
}
