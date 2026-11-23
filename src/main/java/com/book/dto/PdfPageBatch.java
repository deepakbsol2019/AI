package com.book.dto;

public record PdfPageBatch(int startPage, int endPage, int totalPages, String text) { }
