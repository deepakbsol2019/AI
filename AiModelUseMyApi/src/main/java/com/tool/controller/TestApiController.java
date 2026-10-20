package com.tool.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tool.service.TestService;

@RestController
@RequestMapping("/api")
public class TestApiController {

	private final TestService testService;
	public TestApiController(TestService testService) {
		this.testService=testService;
	}
	@GetMapping("/test")
	public String setAlarm(@RequestParam String userInput) {
		return testService.setMyAlarm(userInput);
	}
}
