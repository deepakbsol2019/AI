package com.book.spreadsheet.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.security.AuthErrors;

@RestControllerAdvice(basePackages={"com.book.spreadsheet","com.book.upload.controller"})
public class SpreadsheetExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> failure(ResponseStatusException failure){int status=failure.getStatusCode().value();return ResponseEntity.status(status).header("Cache-Control","no-store").body(AuthErrors.body(status,"SPREADSHEET_REQUEST_FAILED",failure.getReason()==null?"The spreadsheet request could not be completed.":failure.getReason()));}
}
