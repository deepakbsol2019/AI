package com.gemini.model;

import java.util.List;

public class MathModel {

	public String givenQuestion;
	public List<String> possibleStepByStepSolution;
	public String finalAnswer;
	public String referenceBookName;
	public String modelNameUsedForSolution;
	public Long inputTokenSize;
	public Long outputTokenSize;
	public Long totalToken;
	public String getGivenQuestion() {
		return givenQuestion;
	}
	public void setGivenQuestion(String givenQuestion) {
		this.givenQuestion = givenQuestion;
	}
	
	public String getReferenceBookName() {
		return referenceBookName;
	}
	public void setReferenceBookName(String referenceBookName) {
		this.referenceBookName = referenceBookName;
	}
	public String getModelNameUsedForSolution() {
		return modelNameUsedForSolution;
	}
	public void setModelNameUsedForSolution(String modelNameUsedForSolution) {
		this.modelNameUsedForSolution = modelNameUsedForSolution;
	}
	public Long getInputTokenSize() {
		return inputTokenSize;
	}
	public void setInputTokenSize(Long inputTokenSize) {
		this.inputTokenSize = inputTokenSize;
	}
	public Long getOutputTokenSize() {
		return outputTokenSize;
	}
	public void setOutputTokenSize(Long outputTokenSize) {
		this.outputTokenSize = outputTokenSize;
	}
	public Long getTotalToken() {
		return totalToken;
	}
	public void setTotalToken(Long totalToken) {
		this.totalToken = totalToken;
	}
	public List<String> getPossibleStepByStepSolution() {
		return possibleStepByStepSolution;
	}
	public void setPossibleStepByStepSolution(List<String> possibleStepByStepSolution) {
		this.possibleStepByStepSolution = possibleStepByStepSolution;
	}
	public String getFinalAnswer() {
		return finalAnswer;
	}
	public void setFinalAnswer(String finalAnswer) {
		this.finalAnswer = finalAnswer;
	}
	public MathModel(String givenQuestion, List<String> possibleStepByStepSolution, String finalAnswer,
			String referenceBookName, String modelNameUsedForSolution, Long inputTokenSize, Long outputTokenSize,
			Long totalToken) {
		super();
		this.givenQuestion = givenQuestion;
		this.possibleStepByStepSolution = possibleStepByStepSolution;
		this.finalAnswer = finalAnswer;
		this.referenceBookName = referenceBookName;
		this.modelNameUsedForSolution = modelNameUsedForSolution;
		this.inputTokenSize = inputTokenSize;
		this.outputTokenSize = outputTokenSize;
		this.totalToken = totalToken;
	}
	
	
}
