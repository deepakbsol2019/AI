package com.classroom.student.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "student_dtls")
public class Student {

	@Id
	@Column(name = "id")
	private Long id;
	@Column(name = "name",nullable = false,length = 100)
	private String name;
	@Column(name = "class",nullable = false,length = 100)
	private String Sclass;
	@Column(name = "bord",nullable = false,length = 100)
	private String bordName;
	public Long getId() {
		return id;
	}
	public void setId(Long id) {
		this.id = id;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getSclass() {
		return Sclass;
	}
	public void setSclass(String sclass) {
		Sclass = sclass;
	}
	public String getBordName() {
		return bordName;
	}
	public void setBordName(String bordName) {
		this.bordName = bordName;
	}
	public Student(Long id, String name, String sclass, String bordName) {
		super();
		this.id = id;
		this.name = name;
		Sclass = sclass;
		this.bordName = bordName;
	}
	public Student() {
		super();
		// TODO Auto-generated constructor stub
	}
	
	
}
