package com.helpdesk.controller;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpdesk.email.HelpdeskSendEmailService;
import com.helpdesk.enitity.DepartmentNames;
import com.helpdesk.enitity.HelpdeskUsers;
import com.helpdesk.enitity.IssueTypes;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.pipeline.AiModelPipeLine;

@RestController
@RequestMapping("/api")
public class TicketController {

	private final AiModelPipeLine aiModelPipeLine;
	private final HelpdeskSendEmailService helpdeskSendEmailService;
	public TicketController(AiModelPipeLine aiModelPipeLine,HelpdeskSendEmailService helpdeskSendEmailService) {
		this.aiModelPipeLine=aiModelPipeLine;
		this.helpdeskSendEmailService=helpdeskSendEmailService;
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
	
	@GetMapping("/test")
	public String sendMail() {
		HelpdeskUsers create=new HelpdeskUsers("demo",DepartmentNames.DEVELOPMENT);
		HelpdeskUsers assigned=new HelpdeskUsers("support",DepartmentNames.SUPPORT);
		
		Ticket ticket = new Ticket("test ticket",IssueTypes.ACCESS_ISSUE,TicketPriority.HIGH,TicketStatus.INPROGRESS,create,assigned);
		ticket.setTicketId(1L);
		CompletableFuture<Void> createrMail = CompletableFuture.runAsync(()->{
			helpdeskSendEmailService.sendMailToCreatorTicketCreated("kumar.dpk03@gmail.com", ticket);
		});
		CompletableFuture<Void> assignedTo = CompletableFuture.runAsync(()->{
			helpdeskSendEmailService.sendMailTicketAssignedTo("kumar.dpk03@gmail.com", ticket);
		});
		createrMail.join();
		assignedTo.join();
		return "sent successfully";
	}
}
