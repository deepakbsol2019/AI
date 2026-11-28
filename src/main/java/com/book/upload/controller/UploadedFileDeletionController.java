package com.book.upload.controller;

import com.book.upload.service.UploadedFileDeletionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UploadedFileDeletionController {
    private final UploadedFileDeletionService deletion;
    public UploadedFileDeletionController(UploadedFileDeletionService deletion){this.deletion=deletion;}
    @DeleteMapping("/api/books/{bookId}")
    public ResponseEntity<Void> pdf(@PathVariable long bookId){deletion.deletePdf(bookId);return ResponseEntity.noContent().build();}
    @DeleteMapping("/api/spreadsheets/{fileId}")
    public ResponseEntity<Void> spreadsheet(@PathVariable long fileId){deletion.deleteSpreadsheet(fileId);return ResponseEntity.noContent().build();}
    @DeleteMapping("/api/uploads/{uploadId}")
    public ResponseEntity<Void> upload(@PathVariable long uploadId){deletion.deleteUpload(uploadId);return ResponseEntity.noContent().build();}
}
