package com.classroom.cbsc.controller;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.classroom.cbsc.service.TenthClassService;
import com.classroom.response.ResponseModel;

@RestController
@RequestMapping("/api")
public class TenthClassController {
	
	private TenthClassService tenthClassService;
	public TenthClassController(TenthClassService tenthClassService) {
		this.tenthClassService=tenthClassService;
	}
	@GetMapping("/{id}/{subject}")
	public ResponseEntity<ResponseModel> getAiResponse(@PathVariable Long id,@PathVariable String subject,@RequestParam String question){
			ResponseModel response=tenthClassService.getAiResponse(id,subject,question);
			return new ResponseEntity<>(response,HttpStatus.OK);
		
	}
	

}
