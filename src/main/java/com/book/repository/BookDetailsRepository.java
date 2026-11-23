package com.book.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.book.entity.BookDetails;

public interface BookDetailsRepository extends JpaRepository<BookDetails, Long> { }
