package com.helpdesk.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.helpdesk.custException.CustomException;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.repo.TicketRepository;
import com.helpdesk.service.TicketService;
@Service
public class TicketServiceImpl implements TicketService {

	private final TicketRepository ticketRepository;
	public TicketServiceImpl(TicketRepository ticketRepository) {
		this.ticketRepository=ticketRepository;
	}
	
	public Ticket createTicket(Ticket ticket) {
		Ticket save = ticketRepository.save(ticket);
		System.out.println("Saved Ticket ::"+save);
		return save;
	}
	
	public Ticket findTicketById(Long ticketId) {
		Optional<Ticket> ticket = ticketRepository.findById(ticketId);
		System.out.println("find ticket by id ::"+ticket.isPresent());
		return ticket.orElseThrow(()->new CustomException("Ticket not found for ticket id :: "+ticketId));
	}
	
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus){
		Optional<List<Ticket>> tickets=ticketRepository.findTicketByStatus(ticketStatus);
		return tickets.orElseThrow(()->new CustomException("Ticket not found with ticket status ::"+ticketStatus));
	}
	
	public Ticket updateTicket(Ticket ticket) {
		return ticketRepository.save(ticket);
	}

	@Override
	public List<Ticket> findTicketByTicketPriority(TicketPriority ticketPriority) {
		return ticketRepository.findTicketByTicketPriority(ticketPriority);
	}

	@Override
	public List<Ticket> findTicketByCreatedOn(LocalDate createdOn) {
		LocalDateTime startDate = createdOn.atStartOfDay();
	    LocalDateTime endDate = createdOn.plusDays(1).atStartOfDay();
	    System.out.println("startDate :: "+startDate+" endDate :: "+endDate);
		return ticketRepository.findByCreatedOnGreaterThanEqualAndCreatedOnLessThan(startDate,endDate);
	}

	@Override
	public List<Ticket> findTicketCreatedByUserName(String userName) {
		return ticketRepository.findByCreatedBy_Username(userName);
	}

	@Override
	public List<Ticket> findTicketAssignedToUserName(String userName) {
		return ticketRepository.findByAssignedTo_Username(userName);
	}
}
