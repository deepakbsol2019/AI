package com.classroom.response;

import java.util.List;

public class DigramImage {

	private String title;
	private String type;
	private String style;
	private String description;
	private List<String> objects;
	private List<String> labels;
	private String formula;
	private String imageUrl;
	private String imageBase64;
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getStyle() {
		return style;
	}
	public void setStyle(String style) {
		this.style = style;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public List<String> getObjects() {
		return objects;
	}
	public void setObjects(List<String> objects) {
		this.objects = objects;
	}
	public List<String> getLabels() {
		return labels;
	}
	public void setLabels(List<String> labels) {
		this.labels = labels;
	}
	public String getFormula() {
		return formula;
	}
	public void setFormula(String formula) {
		this.formula = formula;
	}
	public String getImageUrl() {
		return imageUrl;
	}
	public void setImageUrl(String imageUrl) {
		this.imageUrl = imageUrl;
	}
	public String getImageBase64() {
		return imageBase64;
	}
	public void setImageBase64(String imageBase64) {
		this.imageBase64 = imageBase64;
	}
	public DigramImage(String title, String type, String style, String description, List<String> objects,
			List<String> labels, String formula, String imageUrl, String imageBase64) {
		super();
		this.title = title;
		this.type = type;
		this.style = style;
		this.description = description;
		this.objects = objects;
		this.labels = labels;
		this.formula = formula;
		this.imageUrl = imageUrl;
		this.imageBase64 = imageBase64;
	}
	public DigramImage() {
		super();
		// TODO Auto-generated constructor stub
	}
	
}
