package com.helpdesk.tools.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.helpdesk.custException.CustomException;
import com.helpdesk.enitity.HelpdeskUsers;
import com.helpdesk.enitity.IssueTypes;
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
	public Ticket createTicket(String ticketSummary, IssueTypes issueType, TicketPriority ticketPriority,String userName) {
		Assert.hasText(ticketSummary, "Ticket summary is required");
		Assert.isTrue(ticketSummary.length() <= 4000, "Ticket summary must not exceed 4000 characters");
		Assert.notNull(issueType, "Issue type is required");
		Assert.notNull(ticketPriority, "Ticket priority is required");
		Assert.hasText(userName, "Username is required");

		Ticket ticket=new Ticket();
		ticket.setTicketSummary(ticketSummary);
		ticket.setIssueType(issueType);
		ticket.setTicketPriority(ticketPriority);
		ticket.setStatus(TicketStatus.OPEN);
		ticket.setCreatedBy(findHelpdeskUserByUserName(userName));
		ticket.setAssignedTo(findTicketAssignedTo());
		return ticketService.createTicket(ticket);
	}
	
	
	
	@Override
	@jakarta.transaction.Transactional(rollbackOn = Exception.class)
	public Ticket reopenTicket(Long ticketId) {
		Assert.isTrue(ticketId != null && ticketId > 0, "A positive ticket ID is required");
		Ticket ticket = ticketService.findTicketById(ticketId);
		Assert.state(ticket.getStatus() == TicketStatus.CLOSED, "Only CLOSED tickets can be reopened");
		ticket.setStatus(TicketStatus.OPEN);
		return ticketService.updateTicket(ticket);
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
	public List<Ticket> findTicketsBySummary(String userName, String summary) {
		return ticketService.findTicketsBySummary(userName, summary);
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
