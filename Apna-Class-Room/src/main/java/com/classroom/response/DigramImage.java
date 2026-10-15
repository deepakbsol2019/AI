package com.classroom.response;

public class DigramImage {
	String type;
    String format;
    String url;
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getFormat() {
		return format;
	}
	public void setFormat(String format) {
		this.format = format;
	}
	public String getUrl() {
		return url;
	}
	public void setUrl(String url) {
		this.url = url;
	}
	public DigramImage(String type, String format, String url) {
		super();
		this.type = type;
		this.format = format;
		this.url = url;
	}
	public DigramImage() {
		super();
		// TODO Auto-generated constructor stub
	}
    
    

}
