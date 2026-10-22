package com.helpdesk.email;

import com.helpdesk.enitity.Ticket;

public interface HelpdeskSendEmailService {
	
	public void sendMailToCreatorTicketCreated(String recipientEmail, Ticket ticket);
	public void sendMailTicketAssignedTo(String recipientEmail, Ticket ticket);

}
