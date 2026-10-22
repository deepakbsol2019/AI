package com.helpdesk.tools.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.helpdesk.custException.CustomException;
import com.helpdesk.enitity.DepartmentNames;
import com.helpdesk.enitity.HelpdeskUsers;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.repo.HelpdeskUserRepo;
import com.helpdesk.service.TicketService;
import com.helpdesk.tools.TicketTools;
@Component
public class TicketToolsImpl implements TicketTools {

	private final TicketService ticketService;
	private final HelpdeskUserRepo helpdeskUserRepo;
	public TicketToolsImpl(TicketService ticketService,HelpdeskUserRepo helpdeskUserRepo) {
		this.ticketService=ticketService;
		this.helpdeskUserRepo=helpdeskUserRepo;
	}
	@Override
	public Ticket createTicket(String ticketSummary, TicketPriority ticketPriority, TicketStatus status,String userName) {

		Ticket ticket=new Ticket();
		ticket.setTicketSummary(ticketSummary);
		ticket.setTicketPriority(ticketPriority);
		ticket.setStatus(status);
		ticket.setCreatedBy(findHelpdeskUserByUserName(userName));
		ticket.setAssignedTo(findTicketAssignedTo());
		return ticketService.createTicket(ticket);
	}
	
	
	
	@Override
	public Ticket findTicketById(Long ticketId) {
		return ticketService.findTicketById(ticketId);
	}

	@Override
	public List<Ticket> findTicketCreatedByUserName(String userName) {
		return ticketService.findTicketCreatedByUserName(userName);
	}
	@Override
	public List<Ticket> findTicketAssignedToUserName(String userName) {
		return ticketService.findTicketAssignedToUserName(userName);
	}

	@Override
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus) {
		return ticketService.findTicketByStatus(ticketStatus);
	}

	@Override
	public Ticket updateTicket(Long ticketId, String ticketSummary, TicketPriority ticketPriority) {
		
		Ticket ticket=findTicketById(ticketId);
		ticket.setTicketId(ticketId);
		ticket.setTicketSummary(ticketSummary);
		ticket.setTicketPriority(ticketPriority);
		if(null==ticket.getAssignedTo()) {
			ticket.setAssignedTo(findTicketAssignedTo());
		}
		return ticketService.updateTicket(ticket);
	}
	@Override
	public List<Ticket> findTicketByTicketPriority(TicketPriority ticketPriority) {
		return ticketService.findTicketByTicketPriority(ticketPriority);
	}
	@Override
	public List<Ticket> findTicketByCreatedOn(LocalDate createdOn) {
		System.out.println("tool date ::"+createdOn);
		return ticketService.findTicketByCreatedOn(createdOn);
	}
	@Override
	public HelpdeskUsers findHelpdeskUserByUserName(String username) {
		 Optional<HelpdeskUsers> byUsername = helpdeskUserRepo.findByUsername(username);
		 return byUsername.orElseThrow(()->new CustomException("exception whicle fetching data by username "+username));
	}

	@Override
	public HelpdeskUsers findTicketAssignedTo() {
		
		 return helpdeskUserRepo.findBestSupportUser().orElseThrow(() -> new IllegalStateException("No support user found"));
	}
	
	
}
