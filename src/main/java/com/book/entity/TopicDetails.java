package com.book.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "topic_details")
public class TopicDetails extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long topicId;

    @Column(nullable = false, length = 100)
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
