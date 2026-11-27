package com.book.spreadsheet.api;

import java.nio.charset.StandardCharsets;
import com.book.user.security.TenantContext;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class SpreadsheetController {
    private final SpreadsheetReadService service;
    public SpreadsheetController(SpreadsheetReadService service){this.service=service;}
    @GetMapping("/api/spreadsheets")
    public SpreadsheetReadService.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search){return service.list(TenantContext.requireTenantId(),page,size,search);}
    @GetMapping("/api/spreadsheets/{fileId}")
    public SpreadsheetReadService.FileDetails details(@PathVariable Long fileId){return service.details(TenantContext.requireTenantId(),fileId);}
    @GetMapping("/api/spreadsheets/{fileId}/sheets/{sheetId}/rows")
    public SpreadsheetReadService.RowsPage rows(@PathVariable Long fileId,@PathVariable Long sheetId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.rows(TenantContext.requireTenantId(),fileId,sheetId,page,size);}
    @GetMapping("/api/spreadsheets/{fileId}/download")
    public ResponseEntity<byte[]> original(@PathVariable Long fileId){return attachment(service.download(TenantContext.requireTenantId(),fileId,null));}
    @GetMapping("/api/spreadsheets/{fileId}/sheets/{sheetId}/download")
    public ResponseEntity<byte[]> sheet(@PathVariable Long fileId,@PathVariable Long sheetId){return attachment(service.download(TenantContext.requireTenantId(),fileId,sheetId));}
    @GetMapping(value="/api/internal/spreadsheets/{fileId}/sheets/{sheetId}/data",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> data(@PathVariable Long fileId,@PathVariable Long sheetId){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").body(service.data(TenantContext.requireTenantId(),fileId,sheetId));}
    private ResponseEntity<byte[]> attachment(SpreadsheetReadService.Binary value){return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.filename(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").contentLength(value.bytes().length).body(value.bytes());}
}
