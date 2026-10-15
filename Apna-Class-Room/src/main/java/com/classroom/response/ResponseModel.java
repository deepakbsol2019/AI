package com.classroom.response;

import java.util.List;

public class ResponseModel {
	String title;
    String directAnswer;
    String explanation;
    List<String> keyPoints;
    DigramImage digramImage;
    List<String> examples;
    String examTip;
    String summary;
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getDirectAnswer() {
		return directAnswer;
	}
	public void setDirectAnswer(String directAnswer) {
		this.directAnswer = directAnswer;
	}
	public String getExplanation() {
		return explanation;
	}
	public void setExplanation(String explanation) {
		this.explanation = explanation;
	}
	public List<String> getKeyPoints() {
		return keyPoints;
	}
	public void setKeyPoints(List<String> keyPoints) {
		this.keyPoints = keyPoints;
	}
	
	public List<String> getExamples() {
		return examples;
	}
	public void setExamples(List<String> examples) {
		this.examples = examples;
	}
	public String getExamTip() {
		return examTip;
	}
	public void setExamTip(String examTip) {
		this.examTip = examTip;
	}
	public String getSummary() {
		return summary;
	}
	public void setSummary(String summary) {
		this.summary = summary;
	}
	
	public DigramImage getDigramImage() {
		return digramImage;
	}
	public void setDigramImage(DigramImage digramImage) {
		this.digramImage = digramImage;
	}
	public ResponseModel() {
		super();
		// TODO Auto-generated constructor stub
	}
	public ResponseModel(String title, String directAnswer, String explanation, List<String> keyPoints,
			DigramImage digramImage, List<String> examples, String examTip, String summary) {
		super();
		this.title = title;
		this.directAnswer = directAnswer;
		this.explanation = explanation;
		this.keyPoints = keyPoints;
		this.digramImage = digramImage;
		this.examples = examples;
		this.examTip = examTip;
		this.summary = summary;
	}
    
    

}
