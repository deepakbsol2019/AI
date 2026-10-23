package com.helpdesk.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketPriority;
import com.helpdesk.enitity.TicketStatus;
public interface TicketRepository extends JpaRepository<Ticket, Long> {

	List<Ticket> findByCreatedBy_Username(String username);
	List<Ticket> findByAssignedTo_Username(String username);

	Optional<List<Ticket>> findTicketByStatus(TicketStatus ticketStatus);

	List<Ticket> findTicketByTicketPriority(TicketPriority ticketPriority);

	List<Ticket> findByCreatedOnGreaterThanEqualAndCreatedOnLessThan(
            LocalDateTime startDate,
            LocalDateTime endDate
    );
	@EntityGraph(attributePaths = {
	        "createdBy",
	        "assignedTo"
	})
	@Query("""
	    SELECT t
	    FROM Ticket t
	    WHERE t.status IN :statuses
	    """)
	List<Ticket> findTicketsForReminder(
	        @Param("statuses") List<TicketStatus> statuses
	);

}
