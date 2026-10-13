package com.gemini.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gemini.chatservice.MathService;
import com.gemini.model.MathModel;

@RestController
@RequestMapping("/api/math")
public class MathExpertController {

	private final MathService mathService;
	public MathExpertController(MathService mathService) {
		this.mathService=mathService;
	}
	@GetMapping("/singleQuestion")
	public ResponseEntity<MathModel> getProblemSolution(@RequestParam String question){
		System.out.println("Math Question ::"+question);
		MathModel res=mathService.singleQuentionAns(question);
		return new ResponseEntity<MathModel>(res,HttpStatus.OK);
	}
}
