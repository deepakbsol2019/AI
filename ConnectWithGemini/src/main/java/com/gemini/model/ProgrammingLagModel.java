package com.gemini.model;

import java.time.LocalDate;

public class ProgrammingLagModel {
	
	private String languageName;
	private LocalDate foundAt;
	private String founder;
	private String topReferenceBookName;
	private String description;
	public String getLanguageName() {
		return languageName;
	}
	public void setLanguageName(String languageName) {
		this.languageName = languageName;
	}
	public LocalDate getFoundAt() {
		return foundAt;
	}
	public void setFoundAt(LocalDate foundAt) {
		this.foundAt = foundAt;
	}
	public String getFounder() {
		return founder;
	}
	public void setFounder(String founder) {
		this.founder = founder;
	}
	public String getTopReferenceBookName() {
		return topReferenceBookName;
	}
	public void setTopReferenceBookName(String topReferenceBookName) {
		this.topReferenceBookName = topReferenceBookName;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public ProgrammingLagModel(String languageName, LocalDate foundAt, String founder, String topReferenceBookName,
			String description) {
		super();
		this.languageName = languageName;
		this.foundAt = foundAt;
		this.founder = founder;
		this.topReferenceBookName = topReferenceBookName;
		this.description = description;
	}

}
