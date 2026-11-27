package com.helpdesk.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.helpdesk.custException.CustomException;
import com.helpdesk.enitity.*;
import com.helpdesk.repo.HelpdeskUserRepo;
import com.helpdesk.service.TicketService;
import com.helpdesk.tools.impl.TicketToolsImpl;

class TicketReopenToolTests {
    private final TicketService service = mock(TicketService.class);
    private final HelpdeskUserRepo users = mock(HelpdeskUserRepo.class);
    private final TicketToolsImpl tools = new TicketToolsImpl(service, users);

    @Test
    void updateLifecycleRefreshesTimestampAndPreservesCreationTime() throws Exception {
        var ticket = new Ticket();
        ticket.onCreate();
        var createdOn = ticket.getCreatedOn();
        var field = Ticket.class.getDeclaredField("updatedOn");
        field.setAccessible(true);
        field.set(ticket, LocalDateTime.of(2000, 1, 1, 0, 0));
        ticket.setStatus(TicketStatus.OPEN);
        var before = LocalDateTime.now();
        ticket.onUpdate();
        var after = LocalDateTime.now();
        assertFalse(ticket.getUpdatedOn().isBefore(before));
        assertFalse(ticket.getUpdatedOn().isAfter(after));
        assertEquals(createdOn, ticket.getCreatedOn());
    }

    @Test
    void reopensClosedTicketWithoutChangingOtherDetails() {
        var creator = new HelpdeskUsers("creator", DepartmentNames.DEVELOPMENT);
        var assignee = new HelpdeskUsers("support", DepartmentNames.SUPPORT);
        var ticket = new Ticket("VPN disconnects", IssueTypes.NETWORK_ISSUE,
                TicketPriority.HIGH, TicketStatus.CLOSED, creator, assignee);
        ticket.setTicketId(42L);
        ticket.onCreate();
        var createdOn = ticket.getCreatedOn();
        when(service.findTicketById(42L)).thenReturn(ticket);
        when(service.updateTicket(ticket)).thenReturn(ticket);

        assertSame(ticket, tools.reopenTicket(42L));
        assertEquals(TicketStatus.OPEN, ticket.getStatus());
        assertEquals(42L, ticket.getTicketId());
        assertEquals("VPN disconnects", ticket.getTicketSummary());
        assertEquals(IssueTypes.NETWORK_ISSUE, ticket.getIssueType());
        assertEquals(TicketPriority.HIGH, ticket.getTicketPriority());
        assertSame(creator, ticket.getCreatedBy());
        assertSame(assignee, ticket.getAssignedTo());
        assertEquals(createdOn, ticket.getCreatedOn());
        verify(service).updateTicket(ticket);
        verifyNoInteractions(users);
    }

    @Test
    void invalidIdsDoNotAccessStorage() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> tools.reopenTicket(id));
        }
        verifyNoInteractions(service, users);
    }

    @Test
    void missingTicketIsNotSaved() {
        when(service.findTicketById(42L)).thenThrow(new CustomException("Ticket not found"));
        assertThrows(CustomException.class, () -> tools.reopenTicket(42L));
        verify(service, never()).updateTicket(any());
    }

    @Test
    void activeOrUnknownStatusCannotBeReopened() {
        for (TicketStatus status : new TicketStatus[] {TicketStatus.OPEN, TicketStatus.INPROGRESS, null}) {
            var ticket = new Ticket();
            ticket.setStatus(status);
            when(service.findTicketById(42L)).thenReturn(ticket);
            assertThrows(IllegalStateException.class, () -> tools.reopenTicket(42L));
            assertEquals(status, ticket.getStatus());
        }
        verify(service, never()).updateTicket(any());
        verifyNoInteractions(users);
    }
}
