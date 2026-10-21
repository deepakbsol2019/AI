package com.helpdesk.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;

public interface TicketService {

	public Ticket createTicket(Ticket ticket);
	public Ticket findTicketById(Long ticketId);
	public List<Ticket> findTicketByUserName(String userName);
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus);
	public Ticket updateTicket(Ticket ticket);
	public List<Ticket> findTicketByTicketPriority(TicketPriority ticketPriority);
	public List<Ticket> findTicketByCreatedOn(LocalDate createdOn);
}
