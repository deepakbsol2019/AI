package com.store.controller;

import org.springframework.web.bind.annotation.RestController;

import com.store.service.FindSimilarResult;
import com.store.service.ReadCSVService;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequestMapping("/api")
public class UploadCsvDataInVectorDB {
	
	private final ReadCSVService readCSVService;
	public final FindSimilarResult findSimirResult;
	public UploadCsvDataInVectorDB(ReadCSVService readCSVService,FindSimilarResult findSimirResult) {
		this.readCSVService=readCSVService;
		this.findSimirResult=findSimirResult;
	}
	
	@PostMapping("/upload-csv")
	public List<String> uploadCsvFile(){
		return readCSVService.readDataFromCsv();
	}
	
	@GetMapping("/get-similar-res")
	public List<String> getSimilarResult(@RequestParam String question){
		return findSimirResult.findSimilarDataByQuestion(question);
	}
	
}
