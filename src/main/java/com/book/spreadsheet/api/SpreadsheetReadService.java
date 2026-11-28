package com.book.spreadsheet.api;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.book.spreadsheet.parse.SpreadsheetFormat;
import com.book.spreadsheet.persistence.SpreadsheetRepository;
import com.book.spreadsheet.persistence.SpreadsheetRepository.*;
import com.book.storage.DocumentFileStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SpreadsheetReadService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.book.tools.ServiceToolAccess> toolAccess;

    @org.springframework.ai.tool.annotation.Tool(name = "listSpreadsheets", description = "List completed CSV and Excel uploads visible in the authenticated tenant. Preserves endpoint authorization and existing pagination.")
    public Page listSpreadsheets(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Zero-based page number.") int page,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Page size from 1 to 100.") int size,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Optional filename search, at most 255 characters.", required=false) String search) {
        var actor = toolAccess.getObject().require("GET", "/api/spreadsheets");
        return list(actor.tenantId(), page, size, search);
    }

    @org.springframework.ai.tool.annotation.Tool(name = "inspectSpreadsheet", description = "Read one completed spreadsheet's worksheet headers, row counts and upload metadata by unique file ID in the authenticated tenant. Returns no storage locators.")
    public FileDetails inspectSpreadsheet(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Positive spreadsheet file ID from an authorized listing.") Long fileId) {
        var actor = toolAccess.getObject().require("GET", "/api/spreadsheets/" + fileId);
        return details(actor.tenantId(), fileId);
    }

    @org.springframework.ai.tool.annotation.Tool(name = "readSpreadsheetRows", description = "Read a bounded page of imported worksheet rows, preserving empty-column positions. Requires the current tenant's spreadsheet-read permission and matching file/sheet IDs.")
    public RowsPage readSpreadsheetRows(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Positive spreadsheet file ID.") Long fileId,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Positive sheet ID belonging to that file.") Long sheetId,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Zero-based row page.") int page,
            @org.springframework.ai.tool.annotation.ToolParam(description = "Rows per page from 1 to 500.") int size) {
        var actor = toolAccess.getObject().require("GET", "/api/spreadsheets/" + fileId + "/sheets/" + sheetId + "/rows");
        return rows(actor.tenantId(), fileId, sheetId, page, size);
    }

    private final SpreadsheetRepository repository;
    private final DocumentFileStore storage;
    private final JsonMapper json=JsonMapper.builder().build();
    public SpreadsheetReadService(SpreadsheetRepository repository,DocumentFileStore storage){this.repository=repository;this.storage=storage;}
    public record FileView(Long fileId,Long uploadId,String fileName,int sheetCount,Instant uploadedAt,long createdBy,String createdByName,String fileType){}
    public record SheetView(Long sheetId,Long fileId,int sheetIndex,String sheetName,int columnCount,long dataRowCount,Map<String,String> header,Instant uploadedAt,long createdBy){}
    public record FileDetails(Long fileId,Long uploadId,String fileName,int sheetCount,Instant uploadedAt,long createdBy,String createdByName,String fileType,List<SheetView> sheets){}
    public record Page(List<FileView> content,long totalElements,int number,int size,int totalPages){}
    public record RowsPage(List<Map<String,String>> rows,List<Long> rowNumbers,int number,int size,long totalElements,int totalPages){}
    public record Binary(byte[] bytes,String filename,String contentType){}

    public Page list(long tenant,int page,int size,String search){
        if(search!=null&&search.length()>255)throw invalid("Filename search is limited to 255 characters.");
        FilePage result=repository.listCompleted(tenant,page,size,search);
        return new Page(result.content().stream().map(this::view).toList(),result.totalElements(),result.number(),result.size(),result.totalPages());
    }
    public FileDetails details(long tenant,Long fileId){
        var file=repository.requireCompleted(tenant,fileId);
        return new FileDetails(file.fileId(),file.uploadId(),file.fileName(),file.sheetCount(),file.uploadedAt(),file.createdBy(),file.createdByName(),file.format(),repository.sheets(tenant,file.fileId()).stream().map(this::view).toList());
    }
    public RowsPage rows(long tenant,Long fileId,Long sheetId,int page,int size){
        var file=repository.requireCompleted(tenant,fileId);
        var sheet=sheet(tenant,file.fileId(),sheetId);
        if(page<0||size<1||size>500||(long)page*size>Integer.MAX_VALUE)throw invalid("Choose a valid row page and size (1–500).");
        var data=repository.rowsPage(tenant,file.fileId(),sheet.sheetId(),page,size);
        return new RowsPage(data.stream().map(RowRecord::payload).toList(),data.stream().map(RowRecord::rowNumber).toList(),page,size,sheet.dataRowCount(),(int)Math.ceil((double)sheet.dataRowCount()/size));
    }
    public Binary download(long tenant,Long fileId,Long sheetId){
        var file=repository.requireCompleted(tenant,fileId);
        SpreadsheetFormat format=SpreadsheetFormat.valueOf(file.format());
        String locator=file.originalUploadPath(),name=file.fileName();
        if(sheetId!=null){
            var sheet=sheet(tenant,file.fileId(),sheetId);
            locator=sheet.uploadPath();format=format.extractedFormat();
            name=base(file.fileName())+"_"+sheet.sheetName()+"."+format.extension();
        }
        if(locator==null||locator.isBlank())throw new ResponseStatusException(HttpStatus.CONFLICT,"This legacy upload has no preserved original or sheet object.");
        try{return new Binary(storage.read(tenant,locator),DocumentFileStore.safeFilename(name),format.contentType());}
        catch(IOException failure){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The spreadsheet file is unavailable.");}
    }
    /** Validate before starting the response. The asynchronous callback captures the trusted tenant explicitly. */
    public StreamingResponseBody data(long tenant,Long fileId,Long sheetId){
        var file=repository.requireCompleted(tenant,fileId);
        var sheet=sheet(tenant,file.fileId(),sheetId);
        return output->{
            var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("fileId",file.fileId());metadata.put("fileName",file.fileName());metadata.put("sheetCount",file.sheetCount());
            metadata.put("sheetId",sheet.sheetId());metadata.put("sheetIndex",sheet.sheetIndex());metadata.put("sheetName",sheet.sheetName());
            metadata.put("columnCount",sheet.columnCount());metadata.put("dataRowCount",sheet.dataRowCount());
            metadata.put("uploadedAt",file.uploadedAt().toString());metadata.put("createdBy",file.createdBy());metadata.put("header",sheet.header());
            String prefix=json.writeValueAsString(metadata);
            writer.write(prefix.substring(0,prefix.length()-1));writer.write(",\"rows\":[");
            long cursor=1,read=0;boolean first=true;
            while(true){
                var batch=repository.rows(tenant,file.fileId(),sheet.sheetId(),cursor,500);
                if(batch.isEmpty())break;
                for(var row:batch){
                    if(!first)writer.write(',');first=false;
                    writer.write(json.writeValueAsString(row));cursor=row.rowNumber();read++;
                }
                writer.flush();
            }
            if(read!=sheet.dataRowCount())throw new IOException("Spreadsheet rows changed during the export; retry the request.");
            writer.write("]}");writer.flush();
        };
    }
    private SheetRecord sheet(long tenant,long file,Long id){
        if(id==null||id<=0)throw invalid("A positive sheet ID is required.");
        return repository.sheets(tenant,file).stream().filter(sheet->sheet.sheetId().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"The worksheet is unavailable."));
    }
    private FileView view(FileRecord file){return new FileView(file.fileId(),file.uploadId(),file.fileName(),file.sheetCount(),file.uploadedAt(),file.createdBy(),file.createdByName(),file.format());}
    private SheetView view(SheetRecord sheet){return new SheetView(sheet.sheetId(),sheet.fileId(),sheet.sheetIndex(),sheet.sheetName(),sheet.columnCount(),sheet.dataRowCount(),sheet.header(),sheet.uploadedAt(),sheet.createdBy());}
    private static String base(String name){int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot):name;}
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
