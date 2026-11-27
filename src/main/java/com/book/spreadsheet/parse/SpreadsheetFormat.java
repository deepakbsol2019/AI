package com.book.spreadsheet.parse;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validated formats, never unchecked client MIME/path fragments. Excel sheet artifacts are XLSX. */
public enum SpreadsheetFormat {
    CSV("csv", "text/csv"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    XLS("xls", "application/vnd.ms-excel");

    private final String extension;
    private final String contentType;
    SpreadsheetFormat(String extension, String contentType) { this.extension=extension; this.contentType=contentType; }
    public String extension() { return extension; }
    public String contentType() { return contentType; }
    public String typeFolder() { return name(); }
    public SpreadsheetFormat extractedFormat() { return this==CSV?CSV:XLSX; }
    public static SpreadsheetFormat fromFilename(String filename) {
        if(filename==null) throw invalid();
        int dot=filename.lastIndexOf('.');
        String extension=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        for(var value:values()) if(value.extension.equals(extension)) return value;
        throw invalid();
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Upload a PDF, CSV, XLSX or XLS document."); }
}
