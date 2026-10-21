package com.helpdesk.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.pipeline.AiModelPipeLine;

@RestController
@RequestMapping("/api")
public class TicketController {

	private final AiModelPipeLine aiModelPipeLine;
	public TicketController(AiModelPipeLine aiModelPipeLine) {
		this.aiModelPipeLine=aiModelPipeLine;
	}
	@PostMapping(value = "/create-ticket",consumes = MediaType.TEXT_PLAIN_VALUE,
	        produces = MediaType.APPLICATION_JSON_VALUE )
	public Ticket createTicket(@RequestBody(required = true) String ticketDescription) {
		System.out.println("ticketDescription ::"+ticketDescription);
		return aiModelPipeLine.createUpdatePipeLine(ticketDescription);
	}
	@GetMapping(value = "/find-ticket")
	public List<Ticket> findTicketBy(@RequestParam("filter") String filter){
		return aiModelPipeLine.getTicketPipeLine(filter);
	}
}
