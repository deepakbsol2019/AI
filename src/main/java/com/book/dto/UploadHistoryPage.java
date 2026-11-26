package com.book.dto;

import java.util.List;
import com.book.entity.BookUploadStatus;
import org.springframework.data.domain.Page;

/** Stable JSON contract; framework Pageable internals are not part of the upload-history API. */
public record UploadHistoryPage(List<BookUploadStatus> content,long totalElements,int totalPages,int number,int size) {
    public static UploadHistoryPage from(Page<BookUploadStatus> page) {
        return new UploadHistoryPage(page.getContent().stream().map(BookUploadStatus::responseCopy).toList(),
                page.getTotalElements(),page.getTotalPages(),page.getNumber(),page.getSize());
    }
}
