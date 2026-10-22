package com.helpdesk.tools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.helpdesk.enitity.HelpdeskUsers;
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
	        Find all tickets created by userName.
	        Valid userName is String type.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true
			)
	public List<Ticket> findTicketCreatedByUserName(@ToolParam(description = "userName is input data for this function of type String.") String userName);
	
	@Tool(description = """
	        Find all tickets assigned to userName.
	        Valid userName is String type.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true
			)
	public List<Ticket> findTicketAssignedToUserName(@ToolParam(description = "userName is input data for this function of type String.") String userName);
	
	@Tool(description = """
	        Find tickets by status.
	        Valid status are OPEN,INPROGRESS,CLOSED.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true)
	public List<Ticket> findTicketByStatus(TicketStatus ticketStatus);
	
	@Tool(
		    name = "updateTicket",
		    description = """
		        Update an existing helpdesk ticket.

		        RULES:
		        1. Use this tool only after explicit user confirmation.
		        2. ticketId is mandatory.
		        3. Update ONLY fields explicitly requested by the user.
		        4. omit those field which is not specified by user.
		        5. if user is clearly telling field name and value then only change it. otherwise omit it.
		        6. Never use the user's update instruction as ticketSummary.
		        7. Never invent values for unspecified fields.
		        8. Preserve all other existing ticket information.
		        9. If the ticket does not exist, report that it was not found.
		        """,
		    returnDirect = false
		)
		public Ticket updateTicket(

		    @ToolParam(
		        description = "Existing ticket ID. Must be positive.",
		        required = true
		    )
		    Long ticketId,

		    @ToolParam(
		        description = """
		            New ticket summary ONLY when the user explicitly
		            requests a summary change.
		            Otherwise omit this parameter or pass null.
		            """,
		        required = false
		    )
		    String ticketSummary,

		    @ToolParam(
		        description = """
		            New priority ONLY when the user explicitly
		            requests a priority change.
		            Allowed values: LOW, MEDIUM, HIGH.
		            Otherwise omit this parameter or pass null.
		            """,
		        required = false
		    )
		    TicketPriority ticketPriority
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
		
	@Tool(
		    name = "findHelpdeskUserByUserName",
		    description = """
		        Find an existing helpdesk user by exact username.
		        Returns user information if found.
		        Returns null if the user does not exist.
		        """,
		    returnDirect = false
		)
		public HelpdeskUsers findHelpdeskUserByUserName(
		    @ToolParam(description = "Exact username to search")
		    String username
		);
	
	@Tool(
		    name = "findTicketAssignedTo",
		    description = """
		        Select the best SUPPORT department user for assigning
		        a new helpdesk ticket.

		        Selection rules:
		        1. Fewest OPEN tickets.
		        2. If tied, fewest INPROGRESS tickets.
		        3. If tied, highest total assigned ticket count.
		        4. If still tied, select any user.

		        Returns the selected support user.
		        Use this tool when a new ticket needs assignment.
		        """,
		    returnDirect = false
		)
		public HelpdeskUsers findTicketAssignedTo();
}
