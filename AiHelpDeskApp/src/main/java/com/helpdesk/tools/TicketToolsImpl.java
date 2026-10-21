package com.helpdesk.tools;

import java.util.List;

import org.springframework.stereotype.Component;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.service.TicketService;
@Component
public class TicketToolsImpl implements TicketTools {

	private final TicketService ticketService;
	
	public TicketToolsImpl(TicketService ticketService) {
		this.ticketService=ticketService;
	}
	@Override
	public Ticket createTicket(String ticketSummary, TicketPriority ticketPriority, TicketStatus status) {

		Ticket ticket=new Ticket();
		ticket.setTicketSummary(ticketSummary);
		ticket.setTicketPriority(ticketPriority);
		ticket.setStatus(status);
		ticket.setUserName("Deepak");
		return ticketService.createTicket(ticket);
	}

	@Override
	public Ticket findTicketById(Long ticketId) {
		return ticketService.findTicketById(ticketId);
	}

	@Override
	public List<Ticket> findTicketByUserName(String userName) {
		return ticketService.findTicketByUserName(userName);
	}

	@Override
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus) {
		return ticketService.findTicketByStatus(ticketStatus);
	}

	@Override
	public Ticket updateTicket(Long ticketId, String ticketSummary, TicketPriority ticketPriority,
			TicketStatus status) {
		Ticket ticket=new Ticket();
		ticket.setTicketId(ticketId);
		ticket.setTicketSummary(ticketSummary);
		ticket.setTicketPriority(ticketPriority);
		ticket.setStatus(status);
		
		return ticketService.updateTicket(ticket);
	}

}
