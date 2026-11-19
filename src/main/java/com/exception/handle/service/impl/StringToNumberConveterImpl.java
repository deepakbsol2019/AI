package com.exception.handle.service.impl;

import org.springframework.stereotype.Service;

import com.exception.handle.service.StringToNumberConveter;

@Service
public class StringToNumberConveterImpl implements StringToNumberConveter {

	
	public Integer convert(String inputString) {
			return Integer.parseInt(inputString);
	}

}