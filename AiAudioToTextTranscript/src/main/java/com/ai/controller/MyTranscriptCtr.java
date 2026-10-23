package com.ai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ai.service.MyTranscriptService;

@RestController
@RequestMapping("/api")
public class MyTranscriptCtr {

	private final MyTranscriptService myTranscriptService;
	public MyTranscriptCtr(MyTranscriptService myTranscriptService) {
		this.myTranscriptService=myTranscriptService;
	}
	@GetMapping("/audio-text")
	public ResponseEntity<?> convertAudioIntoText(){
		return new ResponseEntity<String>(myTranscriptService.converAudioToText(),HttpStatus.OK);
	}
}
