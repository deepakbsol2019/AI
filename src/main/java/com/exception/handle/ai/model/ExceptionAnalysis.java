package com.exception.handle.ai.model;

public class ExceptionAnalysis {

    private Category category;
    private String probableCause;
    private String suggestedFix;
    private Boolean needsSourceCode;
	public Category getCategory() {
		return category;
	}
	public void setCategory(Category category) {
		this.category = category;
	}
	public String getProbableCause() {
		return probableCause;
	}
	public void setProbableCause(String probableCause) {
		this.probableCause = probableCause;
	}
	public String getSuggestedFix() {
		return suggestedFix;
	}
	public void setSuggestedFix(String suggestedFix) {
		this.suggestedFix = suggestedFix;
	}
	public Boolean getNeedsSourceCode() {
		return needsSourceCode;
	}
	public void setNeedsSourceCode(Boolean needsSourceCode) {
		this.needsSourceCode = needsSourceCode;
	}
	public ExceptionAnalysis(Category category, String probableCause, String suggestedFix, Boolean needsSourceCode) {
		super();
		this.category = category;
		this.probableCause = probableCause;
		this.suggestedFix = suggestedFix;
		this.needsSourceCode = needsSourceCode;
	}
	public ExceptionAnalysis() {
		super();
		// TODO Auto-generated constructor stub
	}
	@Override
	public String toString() {
		return "ExceptionAnalysis\n [category=" + category + ",\n probableCause=" + probableCause + ",\n suggestedFix="
				+ suggestedFix + ",\n needsSourceCode=" + needsSourceCode + "]";
	}
    
}
