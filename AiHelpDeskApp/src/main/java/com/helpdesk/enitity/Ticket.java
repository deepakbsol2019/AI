package com.helpdesk.enitity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "Ticket_Dtls")
public class Ticket {
	
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ticketId;

    @Lob
    @Column(name = "ticket_summary")
    private String ticketSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", length = 30)
    private IssueTypes issueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_priority", length = 20)
    private TicketPriority ticketPriority;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_status", length = 25)
    private TicketStatus status;

    // One user can create multiple tickets.
    // References an existing HelpdeskUsers record.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "created_by_user_id",
        referencedColumnName = "userId",
        nullable = false
    )
    private HelpdeskUsers createdBy;

    // One support user can be assigned multiple tickets.
    // Null is allowed before ticket assignment.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "assigned_to_user_id",
        referencedColumnName = "userId"
    )
    private HelpdeskUsers assignedTo;

    @Column(
        name = "created_on",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdOn;

    @Column(name = "updated_on", nullable = false)
    private LocalDateTime updatedOn;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdOn = now;
        updatedOn = now;
        
        issueType=issueType != null ? issueType : IssueTypes.UNKNOWN_ISSUE;
    }
    

    @PreUpdate
    public void onUpdate() {
        updatedOn = LocalDateTime.now();
    }

    public Ticket() {
    }

    public Ticket(
            String ticketSummary,
            IssueTypes issueType,
            TicketPriority ticketPriority,
            TicketStatus status,
            HelpdeskUsers createdBy,
            HelpdeskUsers assignedTo) {

        this.ticketSummary = ticketSummary;
        this.issueType = issueType;
        this.ticketPriority = ticketPriority;
        this.status = status;
        this.createdBy = createdBy;
        this.assignedTo = assignedTo;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getTicketSummary() {
        return ticketSummary;
    }

    public void setTicketSummary(String ticketSummary) {
        this.ticketSummary = ticketSummary;
    }

    public IssueTypes getIssueType() {
        return issueType;
    }

    public void setIssueType(IssueTypes issueType) {
        this.issueType = issueType;
    }

    public TicketPriority getTicketPriority() {
        return ticketPriority;
    }

    public void setTicketPriority(TicketPriority ticketPriority) {
        this.ticketPriority = ticketPriority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public HelpdeskUsers getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(HelpdeskUsers createdBy) {
        this.createdBy = createdBy;
    }

    public HelpdeskUsers getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(HelpdeskUsers assignedTo) {
        this.assignedTo = assignedTo;
    }

    public LocalDateTime getCreatedOn() {
        return createdOn;
    }

    public LocalDateTime getUpdatedOn() {
        return updatedOn;
    }	
}
