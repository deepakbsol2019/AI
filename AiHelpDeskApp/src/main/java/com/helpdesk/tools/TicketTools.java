package com.helpdesk.tools;

import java.time.LocalDate;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.helpdesk.enitity.HelpdeskUsers;
import com.helpdesk.enitity.IssueTypes;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;

public interface TicketTools {

	@Tool(description = """
	        Reopen an existing CLOSED ticket after explicit user confirmation.
	        Retrieves the current saved ticket and changes only its status to OPEN.
	        Preserves summary, classification, priority, creator and assignee.
	        Fails if the ticket does not exist or is not CLOSED.
	        Use this tool rather than updateTicket to reopen a ticket.
	        """, returnDirect = false)
	public Ticket reopenTicket(
			@ToolParam(description = "Exact ID of the closed ticket the user confirmed reopening. Must be positive.", required = true)
			Long ticketId);


	@Tool(description = "Create a ticket after explicit confirmation. Sets status OPEN and assigns a support user automatically.",returnDirect = false)
	public Ticket createTicket(@ToolParam(description ="Description of the problem. Must contain 1 to 4000 characters.",required = true) String ticketSummary,
    @ToolParam(description ="Issue classification inferred from the problem.",required = true) IssueTypes issueType,
    @ToolParam(description ="Ticket priority. Use a value from the allowed enum.",required = true) TicketPriority ticketPriority,
    @ToolParam(description = "userName: this ticked tis created by given user name",required = true) String userName);
	
	
	@Tool(description = """
	        Find one saved ticket by its exact ID.
	        Returns a single ticket object if found.
	        Raises a not-found error if no ticket has this ID; this is not an existing ticket.
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
	        Search current saved tickets created by the exact userName whose summary
	        contains the supplied literal issue phrase, ignoring case.
	        Use a short, distinctive phrase from the user's actual issue, not an invented summary.
	        Returns candidate tickets as a JSON array; [] means no matches for this search.
	        A candidate is a duplicate only if its saved details describe the same issue.
	        Unrelated tickets or tickets found only by status or priority are not duplicates.
	        Both userName and summary must be nonblank. Never guess the username.
	        """, returnDirect = false)
	public List<Ticket> findTicketsBySummary(
			@ToolParam(description = "Exact username of the ticket creator", required = true) String userName,
			@ToolParam(description = "Nonblank literal phrase from the current issue", required = true) String summary);
	
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
	public List<Ticket> findTicketByTicketPriority(@ToolParam(description = "Explicitly requested ticket priority: LOW, MEDIUM or HIGH. Never invent a default.") TicketPriority ticketPriority);
	
	@Tool(description = """
	        Find tickets created on the exact calendar date.
	        createdOn is a LocalDate in yyyy-MM-dd format.
	        Returns a JSON array of tickets.
	        If no tickets exist, returns an empty array.
	        """,returnDirect = true
			
			)
	public List<Ticket> findTicketByCreatedOn(@ToolParam(description = "Creation date in yyyy-MM-dd format",required = true) LocalDate createdOn);
		
	@Tool(
		    name = "findHelpdeskUserByUserName",
		    description = """
		        Find an existing helpdesk user by exact username.
		        Returns user information if found.
		        Raises a not-found error if the user does not exist.
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
