package com.gemini.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gemini.chatservice.AIChatService;

@RestController
@RequestMapping("/api")
public class AIChatController {
	
	private final AIChatService aiChatService;
	public AIChatController(AIChatService aiChatService) {
		this.aiChatService=aiChatService;
	}
	@GetMapping("/chat")
	public ResponseEntity<String> chatWithGemini(@RequestParam String question){
			
		return new ResponseEntity<>(aiChatService.chatWithGemini(question), HttpStatus.OK);
	}

}
