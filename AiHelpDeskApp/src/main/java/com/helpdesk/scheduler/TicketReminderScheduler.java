package com.helpdesk.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.helpdesk.email.impl.HelpdeskSendEmailServiceImpl;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.repo.TicketRepository;

@Component
public class TicketReminderScheduler {

	private final TicketRepository ticketRepo;
    private final HelpdeskSendEmailServiceImpl emailService;
    private final String sendTo="kumar.dpk03@gmail.com";
    public TicketReminderScheduler(
    		TicketRepository ticketRepo,
            HelpdeskSendEmailServiceImpl emailService) {

        this.ticketRepo = ticketRepo;
        this.emailService = emailService;
    }

    // Check every hour
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    public void checkTicketReminders() {

        LocalDateTime now = LocalDateTime.now();

        List<Ticket> tickets = ticketRepo.findTicketsForReminder(List.of(TicketStatus.OPEN,TicketStatus.INPROGRESS));

        for (Ticket ticket : tickets) {

            if (ticket.getStatus() == null
                    || ticket.getStatus().name().equals("CLOSED")
                    || ticket.getStatus().name().equals("RESOLVED")) {
                continue;
            }

            if (ticket.getCreatedOn() == null
                    || ticket.getTicketPriority() == null
                    || ticket.getAssignedTo() == null) {
                continue;
            }

            long hours = Duration.between(
            		ticket.getCreatedOn(),
                    now
            ).toHours();

            long reminderAfter = switch (
                    ticket.getTicketPriority()) {

                case HIGH -> 24;
                case MEDIUM -> 48;
                case LOW -> 72;
            };

            if (hours >= reminderAfter) {

                
                emailService.sendMailTicketAssignedTo(sendTo, ticket);
            }
        }
    }
}
