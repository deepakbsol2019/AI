package com.book.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "pdf_file_topic_details")
public class TopicDetails extends AuditedEntity {
    public static final int MAX_TOPIC_NAME_LENGTH = 255;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long topicId;

    @Column(nullable = false, length = MAX_TOPIC_NAME_LENGTH)
    private String topicName;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String details;

    @Column(nullable = true)
    private Integer startPage;

    @Column(nullable = true)
    private Integer endPage;

    @Column(name = "printed_start_page", nullable = true)
    private Integer printedStartPage;

    @Column(name = "printed_end_page", nullable = true)
    private Integer printedEndPage;

    @Column(name = "pdf_path", nullable = true, length = 2048)
    private String pdfPath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    @JsonIgnore
    private BookDetails bookDetails;

    public TopicDetails() { }

    private TopicDetails(TopicDetails source) {
        super(source);
        topicId = source.topicId;
        topicName = source.topicName;
        details = source.details;
        startPage = source.startPage;
        endPage = source.endPage;
        printedStartPage = source.printedStartPage;
        printedEndPage = source.printedEndPage;
        pdfPath = source.pdfPath;
        // Ownership is read through its ID only; never duplicate or modify the book graph.
        bookDetails = source.bookDetails;
    }

    /** Independent response values remain accurate even if managed entities change or a transaction rolls back. */
    public TopicDetails responseCopy() {
        return new TopicDetails(this);
    }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public Integer getStartPage() { return startPage; }
    public void setStartPage(Integer startPage) { this.startPage = startPage; }
    public Integer getEndPage() { return endPage; }
    public void setEndPage(Integer endPage) { this.endPage = endPage; }
    public Integer getPrintedStartPage() { return printedStartPage; }
    public void setPrintedStartPage(Integer printedStartPage) { this.printedStartPage = printedStartPage; }
    public Integer getPrintedEndPage() { return printedEndPage; }
    public void setPrintedEndPage(Integer printedEndPage) { this.printedEndPage = printedEndPage; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public BookDetails getBookDetails() { return bookDetails; }
    public void setBookDetails(BookDetails bookDetails) { this.bookDetails = bookDetails; }
}
