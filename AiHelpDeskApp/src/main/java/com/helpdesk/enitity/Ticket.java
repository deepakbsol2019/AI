package com.helpdesk.enitity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "Ticket_Dtls")
public class Ticket {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long ticketId;
	
	@Lob
	private String ticketSummary;
	
	@Enumerated(EnumType.STRING)
	private TicketPriority ticketPriority;
	
	@Column(unique = true,length = 25)
	private String userName;
	
	private LocalDateTime createdOn;
	
	private LocalDateTime updatedOn;
	
	@Enumerated(EnumType.STRING)
	private TicketStatus status;

	public Long getTicketId() {
		return ticketId;
	}

	public void setTicketId(Long ticketId) {
		this.ticketId = ticketId;
	}

	public String getTicketSummary() {
		return ticketSummary;
	}

	public void setTicketSummary(String ticketSummary) {
		this.ticketSummary = ticketSummary;
	}

	public TicketPriority getTicketPriority() {
		return ticketPriority;
	}

	public void setTicketPriority(TicketPriority ticketPriority) {
		this.ticketPriority = ticketPriority;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public LocalDateTime getCreatedOn() {
		return createdOn;
	}

	public void setCreatedOn(LocalDateTime createdOn) {
		this.createdOn = createdOn;
	}

	public LocalDateTime getUpdatedOn() {
		return updatedOn;
	}

	public void setUpdatedOn(LocalDateTime updatedOn) {
		this.updatedOn = updatedOn;
	}

	public TicketStatus getStatus() {
		return status;
	}

	public void setStatus(TicketStatus status) {
		this.status = status;
	}

	public Ticket(Long ticketId, String ticketSummary, TicketPriority ticketPriority, String userName,
			LocalDateTime createdOn, LocalDateTime updatedOn, TicketStatus status) {
		super();
		this.ticketId = ticketId;
		this.ticketSummary = ticketSummary;
		this.ticketPriority = ticketPriority;
		this.userName = userName;
		this.createdOn = createdOn;
		this.updatedOn = updatedOn;
		this.status = status;
	}

	public Ticket() {
		super();
		// TODO Auto-generated constructor stub
	}

	@PrePersist
	public void setBothDate() {
		if(null==createdOn)
			createdOn=LocalDateTime.now();
		if(null==updatedOn)
			updatedOn=LocalDateTime.now();
	}
	
	@PreUpdate
	public void setDateUpdatedOn() {
		updatedOn=LocalDateTime.now();
	}
}
