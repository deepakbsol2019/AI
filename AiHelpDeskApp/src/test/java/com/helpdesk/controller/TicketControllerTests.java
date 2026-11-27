package com.helpdesk.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;

import com.helpdesk.email.HelpdeskSendEmailService;
import com.helpdesk.pipeline.AiModelPipeLine;

class TicketControllerTests {

    @Test
    void forwardsTheStableHttpSessionIdAndKeepsOtherSessionsSeparate() {
        var pipeline = mock(AiModelPipeLine.class);
        var emailService = mock(HelpdeskSendEmailService.class);
        when(pipeline.connectWithHelpDesk(anyString(), anyString())).thenReturn("Please describe your issue.");
        var controller = new TicketController(pipeline, emailService);
        var firstSession = new MockHttpSession();
        var secondSession = new MockHttpSession();

        var response = controller.connectWithHelpdesk("My VPN fails", firstSession);
        controller.connectWithHelpdesk("It is still failing", firstSession);
        controller.connectWithHelpdesk("My laptop will not start", secondSession);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Please describe your issue.", response.getBody());
        verify(pipeline).connectWithHelpDesk("My VPN fails", firstSession.getId());
        verify(pipeline).connectWithHelpDesk("It is still failing", firstSession.getId());
        verify(pipeline).connectWithHelpDesk("My laptop will not start", secondSession.getId());
        verify(pipeline, never()).connectWithHelpDesk(anyString());
        verifyNoInteractions(emailService);
    }
}
