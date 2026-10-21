package com.helpdesk.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
public interface TicketRepository extends JpaRepository<Ticket, Long> {

	Optional<List<Ticket>> findByUserName(String userName);

	Optional<List<Ticket>> findTicketByStatus(TicketStatus ticketStatus);

	List<Ticket> findTicketByTicketPriority(TicketPriority ticketPriority);

	List<Ticket> findByCreatedOnGreaterThanEqualAndCreatedOnLessThan(
            LocalDateTime startDate,
            LocalDateTime endDate
    );

}
