package com.gemini.controller;


import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gemini.chatservice.ProgrammingLangService;
import com.gemini.model.ProgrammingLagModel;

@RestController
@RequestMapping("/api/program")
public class ProgrammingLangController {
	
	private final ProgrammingLangService programmingLangService;
	public ProgrammingLangController(ProgrammingLangService programmingLangService) {
		this.programmingLangService=programmingLangService;
	}
	@GetMapping("/lang-dtls")
	public ResponseEntity<ProgrammingLagModel> getDetails(@RequestParam String userQuestion){
		ProgrammingLagModel dtls = programmingLangService.getDtls(userQuestion);
		return new ResponseEntity<>(dtls,HttpStatus.OK);
	}
	
	@GetMapping("/lang-dtls-list")
	public ResponseEntity<List<ProgrammingLagModel>> getDetailsList(@RequestParam String userQuestion){
		List<ProgrammingLagModel> dtls = programmingLangService.getDtlsList(userQuestion);
		return new ResponseEntity<>(dtls,HttpStatus.OK);
	}

}
