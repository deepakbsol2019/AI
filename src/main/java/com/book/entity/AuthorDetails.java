package com.book.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "author_details")
public class AuthorDetails extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // Retain the original database column and generator for existing author rows.
    @Column(name = "aouthor_id")
    private Long authorId;
    @Column(length = 200)
    private String authorName;

    public AuthorDetails() { }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
}
