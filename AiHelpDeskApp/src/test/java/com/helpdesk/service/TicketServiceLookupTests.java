package com.helpdesk.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.helpdesk.email.HelpdeskSendEmailService;
import com.helpdesk.enitity.Ticket;
import com.helpdesk.enitity.TicketStatus;
import com.helpdesk.repo.TicketRepository;
import com.helpdesk.service.impl.TicketServiceImpl;

class TicketServiceLookupTests {

    private final TicketRepository repository = mock(TicketRepository.class);
    private final HelpdeskSendEmailService emailService = mock(HelpdeskSendEmailService.class);
    private final TicketService service = new TicketServiceImpl(repository, emailService);

    @Test
    void noMatchingSavedTicketReturnsEmptyListFromUserScopedSearch() {
        when(repository.findByCreatedBy_UsernameAndTicketSummaryContainingIgnoreCase("deepak", "printer"))
                .thenReturn(List.of());

        assertEquals(List.of(), service.findTicketsBySummary("deepak", "printer"));

        verify(repository).findByCreatedBy_UsernameAndTicketSummaryContainingIgnoreCase("deepak", "printer");
        verifyNoInteractions(emailService);
    }

    @Test
    void searchReturnsOnlySavedMatchesAndTrimsTheInputs() {
        var savedTicket = new Ticket();
        savedTicket.setTicketId(42L);
        savedTicket.setTicketSummary("My printer is offline");
        List<Ticket> matches = List.of(savedTicket);
        when(repository.findByCreatedBy_UsernameAndTicketSummaryContainingIgnoreCase("deepak", "printer"))
                .thenReturn(matches);

        assertSame(matches, service.findTicketsBySummary(" deepak ", " printer "));
        verify(repository).findByCreatedBy_UsernameAndTicketSummaryContainingIgnoreCase("deepak", "printer");
    }

    @Test
    void missingUsernameDoesNotPerformAnUnscopedSearch() {
        assertThrows(IllegalArgumentException.class, () -> service.findTicketsBySummary(null, "printer"));
        assertThrows(IllegalArgumentException.class, () -> service.findTicketsBySummary(" \t", "printer"));

        verifyNoInteractions(repository, emailService);
    }

    @Test
    void missingSummaryDoesNotMatchAllUserTickets() {
        assertThrows(IllegalArgumentException.class, () -> service.findTicketsBySummary("deepak", null));
        assertThrows(IllegalArgumentException.class, () -> service.findTicketsBySummary("deepak", " \t"));

        verifyNoInteractions(repository, emailService);
    }

    @Test
    void databaseFailureIsNotReportedAsAnEmptySearchOrAnExistingTicket() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(repository.findByCreatedBy_UsernameAndTicketSummaryContainingIgnoreCase("deepak", "printer"))
                .thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.findTicketsBySummary("deepak", "printer")));
        verifyNoInteractions(emailService);
    }

    @Test
    void missingStatusResultsAreAnEmptyList() {
        when(repository.findTicketByStatus(TicketStatus.OPEN)).thenReturn(Optional.empty());

        assertEquals(List.of(), service.findTicketByStatus(TicketStatus.OPEN));
    }

    @Test
    void emptyStatusResultsAreAnEmptyList() {
        when(repository.findTicketByStatus(TicketStatus.OPEN)).thenReturn(Optional.of(List.of()));

        assertEquals(List.of(), service.findTicketByStatus(TicketStatus.OPEN));
    }
}
