package com.classroom.response;

import java.util.List;

public class ResponseModel {
	private String title;
	private String studentClass;
	private String subject;
	private String directAnswer;
	private String explanation;
	private List<String> keyPoints;
	private List<String> formulas;
	private List<String> siUnits;
	private List<String> constants;
	private DigramImage diagram;
	private List<String> examples;
	private String examTip;
	private String summary;
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getStudentClass() {
		return studentClass;
	}
	public void setStudentClass(String studentClass) {
		this.studentClass = studentClass;
	}
	public String getSubject() {
		return subject;
	}
	public void setSubject(String subject) {
		this.subject = subject;
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
	public List<String> getFormulas() {
		return formulas;
	}
	public void setFormulas(List<String> formulas) {
		this.formulas = formulas;
	}
	public List<String> getSiUnits() {
		return siUnits;
	}
	public void setSiUnits(List<String> siUnits) {
		this.siUnits = siUnits;
	}
	public List<String> getConstants() {
		return constants;
	}
	public void setConstants(List<String> constants) {
		this.constants = constants;
	}
	public DigramImage getDiagram() {
		return diagram;
	}
	public void setDiagram(DigramImage diagram) {
		this.diagram = diagram;
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
	public ResponseModel(String title, String studentClass, String subject, String directAnswer, String explanation,
			List<String> keyPoints, List<String> formulas, List<String> siUnits, List<String> constants,
			DigramImage diagram, List<String> examples, String examTip, String summary) {
		super();
		this.title = title;
		this.studentClass = studentClass;
		this.subject = subject;
		this.directAnswer = directAnswer;
		this.explanation = explanation;
		this.keyPoints = keyPoints;
		this.formulas = formulas;
		this.siUnits = siUnits;
		this.constants = constants;
		this.diagram = diagram;
		this.examples = examples;
		this.examTip = examTip;
		this.summary = summary;
	}
	public ResponseModel() {
		super();
		// TODO Auto-generated constructor stub
	}
	
}
