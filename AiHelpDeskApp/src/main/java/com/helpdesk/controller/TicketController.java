package com.helpdesk.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpdesk.pipeline.AiModelPipeLine;

@RestController
@RequestMapping("/api")
public class TicketController {

	private final AiModelPipeLine aiModelPipeLine;
	public TicketController(AiModelPipeLine aiModelPipeLine) {
		this.aiModelPipeLine=aiModelPipeLine;
	}
/*	@PostMapping(value = "/create-ticket",consumes = MediaType.TEXT_PLAIN_VALUE,
	        produces = MediaType.APPLICATION_JSON_VALUE )
	public Ticket createTicket(@RequestBody(required = true) String ticketDescription) {
		System.out.println("ticketDescription ::"+ticketDescription);
		return aiModelPipeLine.createUpdatePipeLine(ticketDescription);
	}
	@GetMapping(value = "/find-ticket")
	public List<Ticket> findTicketBy(@RequestParam("filter") String filter){
		return aiModelPipeLine.getTicketPipeLine(filter);
	}
	
	*/
	
	@PostMapping(value = "/connect-helpdesk",consumes = MediaType.TEXT_PLAIN_VALUE,
	        produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> connectWithHelpdesk(@RequestBody String question){
		return new ResponseEntity<>(aiModelPipeLine.connectWithHelpDesk(question),HttpStatus.OK);
	}
}
