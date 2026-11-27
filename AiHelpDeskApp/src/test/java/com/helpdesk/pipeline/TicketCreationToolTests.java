package com.helpdesk.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.helpdesk.enitity.*;
import com.helpdesk.repo.HelpdeskUserRepo;
import com.helpdesk.service.TicketService;
import com.helpdesk.tools.impl.TicketToolsImpl;

class TicketCreationToolTests {
    @Test
    void creationPreservesClassificationAndSetsOpenAndOwnership() {
        var service = mock(TicketService.class);
        var users = mock(HelpdeskUserRepo.class);
        var creator = new HelpdeskUsers();
        var support = new HelpdeskUsers();
        when(users.findByUsername("admin")).thenReturn(Optional.of(creator));
        when(users.findBestSupportUser()).thenReturn(Optional.of(support));
        when(service.createTicket(any())).thenAnswer(call -> call.getArgument(0));

        var tools = new TicketToolsImpl(service, users);
        var saved = tools.createTicket("Password reset fails", IssueTypes.ACCESS_ISSUE,
                TicketPriority.MEDIUM, "admin");

        assertEquals(IssueTypes.ACCESS_ISSUE, saved.getIssueType());
        assertEquals(TicketStatus.OPEN, saved.getStatus());
        assertSame(creator, saved.getCreatedBy());
        assertSame(support, saved.getAssignedTo());
        verify(service).createTicket(saved);
    }

    @Test
    void missingClassificationCannotCreateTicket() {
        var service = mock(TicketService.class);
        var users = mock(HelpdeskUserRepo.class);
        var tools = new TicketToolsImpl(service, users);
        assertThrows(IllegalArgumentException.class, () -> tools.createTicket(
                "Password reset fails", null, TicketPriority.MEDIUM, "admin"));
        verifyNoInteractions(service, users);
    }
}
