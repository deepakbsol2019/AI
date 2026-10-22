package com.helpdesk.pipeline;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.helpdesk.enitity.Ticket;

public interface AiModelPipeLine {

	public Ticket createUpdatePipeLine(String question);
	public List<Ticket> getTicketPipeLine(String question);
	public String connectWithHelpDesk(String question);
}
