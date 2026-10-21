package com.helpdesk.tools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;

public interface TicketTools {


	@Tool(description = "Save given Ticket into Ticket_Dtls table and return the created object in response.",returnDirect = true)
	public Ticket createTicket(@ToolParam(description ="Description of the problem. Must contain 1 to 4000 characters.",required = true) String ticketSummary,
    @ToolParam(description ="Ticket priority. Use a value from the allowed enum.",required = true) TicketPriority ticketPriority,
    @ToolParam(description ="Replacement status. use status OPEN.", required = false) TicketStatus status,
    @ToolParam(description = "userName: this ticked tis created by given user name",required = true) String userName);
	
	
	@Tool(description = """
	        Find tickets by id.
	        Valid id is long type.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true)
	public Ticket findTicketById(@ToolParam(description = "This is input parameter of type Long, this is primary key in table") Long ticketId);
	
	@Tool(description = """
	        Find tickets by userName.
	        Valid userName is String type.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true
			)
	public List<Ticket> findTicketByUserName(@ToolParam(description = "userName is input data for this function of type String.") String userName);
	
	@Tool(description = """
	        Find tickets by status.
	        Valid status are OPEN,INPROGRESS,CLOSED.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true)
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus);
	
	@Tool(description = "update the ticket infornation in table for existing ticket id.")
	public Ticket updateTicket(
			@ToolParam(description =  "ID of the existing ticket to update. Must be positive.",required = true) Long ticketId,

		    @ToolParam( description ="Replacement summary. Omit or use null to keep the existing summary.", required = false) String ticketSummary,

		    @ToolParam(description = "Replacement priority. Omit or use null to keep the existing priority.", required = false) TicketPriority ticketPriority,

		    @ToolParam( description =  "Replacement status. Omit or use null to keep the existing status.", required = false) TicketStatus status
	);
	
	@Tool(description = """
	        Find tickets by priority.
	        Valid priorities are LOW, MEDIUM, HIGH.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true)
	public List<Ticket> findTicketByTicketPriority(@ToolParam(description = "ticket priority is used as a input parameter. this is enum type. keep default value HIGH") TicketPriority ticketPriority);
	
	@Tool(description = """
	        Find tickets by CreatedOn.
	        Valid createdOn is of type LocalDateTime type.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true
			
			)
	public List<Ticket> findTicketByCreatedOn(@ToolParam(description = "createdOn is the input parameter of type local date time. use this paramert to filter the ticket data from table.",required = true) LocalDate createdOn);
		
}
