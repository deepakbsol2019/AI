package com.book.upload.controller;

import java.util.*;
import com.book.tools.ServiceToolAccess;
import com.book.upload.service.FileDeletionPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Batched display hints only. DELETE repeats authorization against current persisted ownership. */
@RestController
public class FileDeletionCapabilitiesController {
    private final JdbcTemplate jdbc;
    private final ServiceToolAccess access;
    private final FileDeletionPolicy policy;
    public FileDeletionCapabilitiesController(JdbcTemplate jdbc,ServiceToolAccess access,FileDeletionPolicy policy) {
        this.jdbc=jdbc;this.access=access;this.policy=policy;
    }
    @GetMapping("/api/files/delete-capabilities")
    public Map<Long,Boolean> capabilities(@RequestParam String kind,@RequestParam List<Long> ids) {
        var scope=policy.scope(access.require("GET","/api/files/delete-capabilities"));
        if(ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(id->id==null || id<=0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Supply 1 to 100 positive file IDs.");
        var unique=ids.stream().distinct().toList();
        var result=new LinkedHashMap<Long,Boolean>();unique.forEach(id->result.put(id,false));
        String columns="u.tenant_id,u.uploader_user_id,u.status,u.processing_state";
        String query=switch(kind) {
            case "books" -> "SELECT b.book_id AS id,"+columns+",u.uploader_user_id AS creator FROM pdf_file_details b JOIN pdf_file_status u ON u.book_upload_id=b.book_upload_id AND u.tenant_id=b.tenant_id WHERE b.book_id";
            case "spreadsheets" -> "SELECT f.file_id AS id,"+columns+",f.created_by AS creator FROM spreadsheet_file_details f JOIN pdf_file_status u ON u.book_upload_id=f.upload_id AND u.tenant_id=f.tenant_id WHERE f.file_id";
            case "uploads" -> "SELECT u.book_upload_id AS id,"+columns+",u.uploader_user_id AS creator FROM pdf_file_status u WHERE u.book_upload_id";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose books, spreadsheets or uploads.");
        };
        var args=new ArrayList<Object>(unique);
        query+=" IN ("+String.join(",",Collections.nCopies(unique.size(),"?"))+")";
        if(!scope.global()){query+=" AND u.tenant_id=?";args.add(scope.tenantId());}
        for(var row:jdbc.queryForList(query,args.toArray())) {
            if(!(row.get("tenant_id") instanceof Number))continue;
            long tenant=((Number)row.get("tenant_id")).longValue();
            boolean active="inprogress".equals(row.get("status")) || Set.of("PENDING","PROCESSING").contains(Objects.toString(row.get("processing_state"),""));
            result.put(((Number)row.get("id")).longValue(),!active
                    &&scope.allows(tenant,row.get("uploader_user_id") instanceof Number uploader?uploader.longValue():-1)
                    &&scope.allows(tenant,row.get("creator") instanceof Number creator?creator.longValue():-1));
        }
        return result;
    }
    public record ManagedPage(List<Map<String,Object>> content,long totalElements,long totalPages,int number,int size) { }
    @GetMapping("/api/files/manage")
    public ManagedPage manage(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var scope=managementScope("/api/files/manage");
        if(page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid page and size (1 to 100).");
        var args=new ArrayList<Object>();String where=managedWhere(scope,args);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status u"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var content=jdbc.queryForList("SELECT u.* FROM pdf_file_status u"+where+" ORDER BY u.created_at DESC,u.book_upload_id DESC LIMIT ? OFFSET ?",args.toArray())
                .stream().map(FileDeletionCapabilitiesController::receipt).toList();
        return new ManagedPage(content,total,total==0?0:1+(total-1)/size,page,size);
    }
    @GetMapping("/api/files/manage/{uploadId}")
    public Map<String,Object> managedStatus(@PathVariable long uploadId) {
        var scope=managementScope("/api/files/manage/"+uploadId);
        var args=new ArrayList<Object>();String where=managedWhere(scope,args);args.add(uploadId);
        var rows=jdbc.queryForList("SELECT u.* FROM pdf_file_status u"+where+" AND u.book_upload_id=?",args.toArray());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The upload was not found in your permitted scope.");
        return receipt(rows.getFirst());
    }
    private FileDeletionPolicy.Scope managementScope(String path) {
        var scope=policy.scope(access.require("GET",path));
        if(!scope.permitted())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"File delete permission is required to manage uploads.");
        return scope;
    }
    private static String managedWhere(FileDeletionPolicy.Scope scope,List<Object> args) {
        String where=" WHERE u.tenant_id IS NOT NULL";
        if(!scope.global()) {where+=" AND u.tenant_id=?";args.add(scope.tenantId());}
        if(!scope.global()&&!scope.admin()){where+=" AND u.uploader_user_id=?";args.add(scope.userId());}
        return where;
    }
    private static Map<String,Object> receipt(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        result.put("book_upload_id",row.get("book_upload_id"));result.put("tenantId",row.get("tenant_id"));
        result.put("uploadedBy",row.get("uploader_user_id"));result.put("fileName",row.get("file_name"));result.put("bookName",row.get("book_name"));
        result.put("status",row.get("status"));result.put("message",row.get("message"));result.put("state",row.get("processing_state"));
        result.put("failureReason","failed".equals(row.get("status"))?row.get("message"):null);
        for(String field:List.of("created","updated")) {
            Object timestamp=row.get(field+"_at");
            result.put(field+"At",timestamp instanceof java.sql.Timestamp value?value.toInstant():timestamp);
        }
        return result;
    }
}
