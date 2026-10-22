package com.helpdesk.email.impl;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.helpdesk.email.HelpdeskSendEmailService;
import com.helpdesk.enitity.Ticket;
@Service
public class HelpdeskSendEmailServiceImpl implements HelpdeskSendEmailService{
	private final JavaMailSender mailSender;
    private final String fromAddress;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    public HelpdeskSendEmailServiceImpl(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress) {

        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    // ==========================================
    // 1. EMAIL TO TICKET CREATOR
    // ==========================================

    public void sendMailToCreatorTicketCreated(
            String recipientEmail,
            Ticket ticket) {

        sendTicketEmail(
                recipientEmail,
                ticket,
                "Ticket Created Successfully",
                "Your helpdesk ticket has been created successfully.",
                "Please keep your Ticket ID for future reference."
        );
    }

    // ==========================================
    // 2. EMAIL TO ASSIGNED USER
    // ==========================================

    public void sendMailTicketAssignedTo(
            String recipientEmail,
            Ticket ticket) {

        sendTicketEmail(
                recipientEmail,
                ticket,
                "New Ticket Assigned",
                "A helpdesk ticket has been assigned to you.",
                "Please review the ticket and take the necessary action."
        );
    }

    // ==========================================
    // 3. COMMON EMAIL SENDING METHOD
    // ==========================================

    private void sendTicketEmail(
            String recipientEmail,
            Ticket ticket,
            String emailTitle,
            String message,
            String footerMessage) {

        validateInput(recipientEmail, ticket);

        String subject = "Helpdesk Ticket #"
                + ticket.getTicketId()
                + " - " + emailTitle;

        // Plain-text alternative
        String plainText = """
                HELPDESK NOTIFICATION

                %s

                Ticket ID: %s
                Ticket Summary: %s
                Priority: %s
                Created By: %s
                Created On: %s
                Updated On: %s
                Status: %s

                %s

                Regards,
                Helpdesk Support Team
                """.formatted(
                    message,
                    ticket.getTicketId(),
                    safeText(ticket.getTicketSummary()),
                    safeText(ticket.getTicketPriority()),
                    getCreatorName(ticket),
                    formatDate(ticket.getCreatedOn()),
                    formatDate(ticket.getUpdatedOn()),
                    safeText(ticket.getStatus()),
                    footerMessage
                );

        // HTML email
        String htmlText = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                </head>

                <body style="
                    margin:0;
                    padding:24px;
                    background-color:#f4f6f9;
                    font-family:Arial,Helvetica,sans-serif;">

                    <table role="presentation"
                           width="100%"
                           cellpadding="0"
                           cellspacing="0"
                           border="0"
                           style="
                               max-width:650px;
                               margin:auto;
                               background-color:#ffffff;
                               border-collapse:collapse;">

                        <!-- Header -->
                        <tr>
                            <td style="
                                background-color:#153f68;
                                color:#ffffff;
                                padding:25px;
                                text-align:center;">

                                <h2 style="margin:0;">
                                    {{EMAIL_TITLE}}
                                </h2>

                                <p style="margin:10px 0 0;">
                                    Helpdesk Support System
                                </p>
                            </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                            <td style="padding:25px;">

                                <p>Hello,</p>

                                <p>{{MESSAGE}}</p>

                                <table width="100%"
                                       cellpadding="10"
                                       cellspacing="0"
                                       border="0"
                                       style="
                                           border-collapse:collapse;
                                           font-size:14px;
                                           color:#333333;">

                                    <tr>
                                        <td colspan="2"
                                            style="
                                                background-color:#e8f0fe;
                                                font-weight:bold;
                                                border:1px solid #dddddd;">
                                            Ticket Information
                                        </td>
                                    </tr>

                                    {{TICKET_ROWS}}

                                </table>

                                <p style="margin-top:25px;">
                                    {{FOOTER_MESSAGE}}
                                </p>

                                <p>
                                    Regards,<br>
                                    <strong>Helpdesk Support Team</strong>
                                </p>

                            </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                            <td style="
                                background-color:#f1f5f9;
                                padding:15px;
                                text-align:center;
                                color:#64748b;
                                font-size:12px;">

                                This is an automated notification.
                                Please do not reply.

                            </td>
                        </tr>

                    </table>
                </body>
                </html>
                """
                .replace("{{EMAIL_TITLE}}", escapeHtml(emailTitle))
                .replace("{{MESSAGE}}", escapeHtml(message))
                .replace("{{TICKET_ROWS}}", buildTicketRows(ticket))
                .replace("{{FOOTER_MESSAGE}}",
                        escapeHtml(footerMessage));

        // Send using Gmail SMTP
        mailSender.send(mimeMessage -> {

            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    true,
                    StandardCharsets.UTF_8.name()
            );

            helper.setValidateAddresses(true);
            helper.setFrom(fromAddress);
            helper.setTo(recipientEmail.trim());
            helper.setSubject(subject);

            // Plain text + HTML
            helper.setText(plainText, htmlText);
        });
    }

    // ==========================================
    // 4. VALIDATE INPUT
    // ==========================================

    private void validateInput(
            String recipientEmail,
            Ticket ticket) {

        if (recipientEmail == null
                || recipientEmail.isBlank()
                || recipientEmail.contains("\r")
                || recipientEmail.contains("\n")) {

            throw new IllegalArgumentException(
                    "Invalid recipient email");
        }

        if (ticket == null || ticket.getTicketId() == null) {
            throw new IllegalArgumentException(
                    "A saved ticket with ID is required");
        }
    }

    // ==========================================
    // 5. GENERATE TICKET TABLE ROWS
    // ==========================================

    private String buildTicketRows(Ticket ticket) {

        return row("Ticket ID",
                    "#" + ticket.getTicketId())

                + row("Ticket Summary",
                    safeText(ticket.getTicketSummary()))

                + row("Priority",
                    safeText(ticket.getTicketPriority()))

                + row("Created By",
                    getCreatorName(ticket))

                + row("Created On",
                    formatDate(ticket.getCreatedOn()))

                + row("Updated On",
                    formatDate(ticket.getUpdatedOn()))

                + row("Status",
                    safeText(ticket.getStatus()));
    }

    // ==========================================
    // 6. HTML TABLE ROW
    // ==========================================

    private String row(String label, String value) {

        return """
                <tr>
                    <td style="
                        width:35%;
                        padding:12px;
                        background-color:#f8fafc;
                        border:1px solid #dddddd;
                        font-weight:bold;
                        vertical-align:top;">
                        {{LABEL}}
                    </td>

                    <td style="
                        padding:12px;
                        border:1px solid #dddddd;
                        vertical-align:top;
                        overflow-wrap:anywhere;">
                        {{VALUE}}
                    </td>
                </tr>
                """
                .replace("{{LABEL}}", escapeHtml(label))
                .replace("{{VALUE}}", escapeHtml(value));
    }

    // ==========================================
    // 7. GET TICKET CREATOR NAME
    // ==========================================

    private String getCreatorName(Ticket ticket) {

        if (ticket.getCreatedBy() == null) {
            return "Not available";
        }

        return safeText(
                ticket.getCreatedBy().getUsername()
        );
    }

    // ==========================================
    // 8. FORMAT DATE
    // ==========================================

    private String formatDate(LocalDateTime date) {

        return date == null
                ? "Not available"
                : date.format(DATE_FORMAT);
    }

    // ==========================================
    // 9. NULL-SAFE TEXT
    // ==========================================

    private String safeText(Object value) {

        return value == null
                ? "Not available"
                : value.toString();
    }

    // ==========================================
    // 10. ESCAPE HTML
    // ==========================================

    private String escapeHtml(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}