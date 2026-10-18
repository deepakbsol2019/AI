package com.store.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.store.service.FindAnswerFromAI;

@RestController
@RequestMapping("/api")
public class FindAnswerFromAICtr {

	private final FindAnswerFromAI findAnswerFromAI;
	public FindAnswerFromAICtr(FindAnswerFromAI findAnswerFromAI) {
		this.findAnswerFromAI=findAnswerFromAI;
	}
	@GetMapping("/find-answer")
	public String findAnswer(@RequestParam String question) {
		return findAnswerFromAI.findUserQuestionAnswer(question);
	}
}
