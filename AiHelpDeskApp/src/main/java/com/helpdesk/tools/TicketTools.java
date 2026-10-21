package com.helpdesk.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;

public interface TicketTools {


	@Tool(description = "Save given Ticket into Ticket_Dtls table and return the created object")
	public Ticket createTicket(@ToolParam(description ="Description of the problem. Must contain 1 to 4000 characters.") String ticketSummary,
    @ToolParam(description ="Ticket priority. Use a value from the allowed enum.") TicketPriority ticketPriority,
    @ToolParam(description ="Replacement status. use status OPEN.", required = false) TicketStatus status);
	
	@Tool(description = "Search or find or get the Ticket object from Ticket_Dtls table by using input parameter ticketId(primary Key)")
	public Ticket findTicketById(@ToolParam(description = "This is input parameter of type Long, this is primary key in table") Long ticketId);
	
	@Tool(description = "Search or find or get the Ticket object from Ticket_Dtls table by using input parameter userName.you may get multiple object or zero object in result")
	public List<Ticket> findTicketByUserName(@ToolParam(description = "userName is input data for this function of type String.") String userName);
	
	@Tool(description = "Search or find or get the Ticket object from Ticket_Dtls table by using input parameter ticket status.you may get multiple object or zero object in result")
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus);
	
	public Ticket updateTicket(
			@ToolParam(description =  "ID of the existing ticket to update. Must be positive.") Long ticketId,

		    @ToolParam( description ="Replacement summary. Omit or use null to keep the existing summary.", required = false) String ticketSummary,

		    @ToolParam(description = "Replacement priority. Omit or use null to keep the existing priority.", required = false) TicketPriority ticketPriority,

		    @ToolParam( description =  "Replacement status. Omit or use null to keep the existing status.", required = false) TicketStatus status);
		
}
