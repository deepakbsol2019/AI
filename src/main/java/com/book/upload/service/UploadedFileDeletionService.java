package com.book.upload.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.book.storage.TenantStoragePaths;
import com.book.tools.ServiceToolAccess;
import com.book.user.security.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Delete exact persisted locators before committing metadata removal. A failure retains the
 * metadata as a durable retry manifest; retrying the same ID tolerates already removed files.
 * Filesystem work is deliberately not described as transactional or reversible.
 */
@Service
public class UploadedFileDeletionService {
    private final JdbcTemplate jdbc;
    private final TenantStoragePaths storage;
    private final TransactionTemplate tx;
    private final ServiceToolAccess access;
    private final FileDeletionPolicy policy;
    public UploadedFileDeletionService(JdbcTemplate jdbc, TenantStoragePaths storage,
            PlatformTransactionManager manager, ServiceToolAccess access, FileDeletionPolicy policy) {
        this.jdbc=jdbc; this.storage=storage; this.tx=new TransactionTemplate(manager); this.access=access; this.policy=policy;
    }

    @Tool(description="Delete a completed PDF by its unique book ID, including topics and stored artifacts. SUPER_USER has global scope, reserved ADMIN has own-tenant scope, and other permitted roles require own uploader identity within their tenant. Retry the same ID if cleanup fails.")
    public void deletePdf(@ToolParam(description="Unique positive book ID from the library") long bookId) {
        var scope=policy.scope(access.require("DELETE", "/api/books/"+bookId));
        deleteFile("PDF",bookId,scope);
    }
    @Tool(description="Delete a completed CSV or Excel upload by unique file ID, including worksheets, imported rows and artifacts. SUPER_USER may delete globally, reserved ADMIN within its tenant, and other permitted roles only their own uploads in their tenant. Retry the same ID after cleanup failure.")
    public void deleteSpreadsheet(@ToolParam(description="Unique positive spreadsheet file ID") long fileId) {
        var scope=policy.scope(access.require("DELETE", "/api/spreadsheets/"+fileId));
        deleteFile("SHEET",fileId,scope);
    }
    @Tool(description="Delete a completed or failed upload by its unique receipt ID and clean associated data/artifacts. Enforces current global SUPER_USER, own-tenant reserved ADMIN, or permitted own-uploader scope. Retry the same ID after cleanup failure.")
    public void deleteUpload(@ToolParam(description="Unique positive upload receipt ID from My Uploads") long uploadId) {
        var scope=policy.scope(access.require("DELETE", "/api/uploads/"+uploadId));
        if(uploadId<=0)throw invalid();
        run(()->purge(scope,uploadId,null,0));
    }
    private void deleteFile(String kind,long id,FileDeletionPolicy.Scope scope) {
        if(id<=0)throw invalid();
        run(()->{
            String query=kind.equals("PDF")?"SELECT book_upload_id FROM pdf_file_details WHERE book_id=?":"SELECT upload_id FROM spreadsheet_file_details WHERE file_id=?";
            List<Long> ids=scope.global()?jdbc.queryForList(query,Long.class,id):jdbc.queryForList(query+" AND tenant_id=?",Long.class,id,scope.tenantId());
            if(ids.size()!=1 || ids.getFirst()==null)throw missing();
            purge(scope,ids.getFirst(),kind,id);
        });
    }
    private void run(Runnable operation) {
        try {tx.executeWithoutResult(status->operation.run());}
        catch(DataAccessException | TransactionException failure) {throw retryable();}
    }
    private void purge(FileDeletionPolicy.Scope scope,long upload,String expectedKind,long expectedId) {
        String receiptQuery="SELECT * FROM pdf_file_status WHERE book_upload_id=?";
        var receipts=scope.global()?jdbc.queryForList(receiptQuery+" FOR UPDATE",upload)
                :jdbc.queryForList(receiptQuery+" AND tenant_id=? FOR UPDATE",upload,scope.tenantId());
        if(receipts.size()!=1)throw missing();
        var receipt=receipts.getFirst();
        if(receipt.get("tenant_id")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This legacy upload has no assigned tenant storage scope; migrate its ownership before deletion.");
        long tenant=number(receipt,"tenant_id"),user=nullableNumber(receipt,"uploader_user_id");
        scope.require(tenant,user);
        storage.requireWritable(tenant);
        if("inprogress".equals(receipt.get("status")) || Set.of("PENDING","PROCESSING").contains(Objects.toString(receipt.get("processing_state"),"")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for this upload to finish before deleting it.");
        var books=jdbc.queryForList("SELECT book_id,author_id FROM pdf_file_details WHERE tenant_id=? AND book_upload_id=? FOR UPDATE",tenant,upload);
        var sheets=jdbc.queryForList("SELECT file_id,original_upload_path,created_by FROM spreadsheet_file_details WHERE tenant_id=? AND upload_id=? FOR UPDATE",tenant,upload);
        if(books.size()+sheets.size()>1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload ownership is ambiguous; deletion requires review.");
        Long book=books.isEmpty()?null:number(books.getFirst(),"book_id"), file=sheets.isEmpty()?null:number(sheets.getFirst(),"file_id");
        if(expectedKind!=null && !(expectedKind.equals("PDF")?Objects.equals(book,expectedId):Objects.equals(file,expectedId)))throw missing();
        if(!sheets.isEmpty())scope.require(tenant,nullableNumber(sheets.getFirst(),"created_by"));
        List<String> locators=new ArrayList<>();
        if(book!=null)locators.addAll(jdbc.queryForList("SELECT pdf_path FROM pdf_file_topic_details WHERE book_id=? AND pdf_path IS NOT NULL",String.class,book));
        if(file!=null){
            locators.add(Objects.toString(sheets.getFirst().get("original_upload_path"),""));
            locators.addAll(jdbc.queryForList("SELECT upload_path FROM spreadsheet_sheet_details WHERE file_id=?",String.class,file));
        }
        try {
            Set<Path> targets=new LinkedHashSet<>();
            for(String locator:locators)if(locator!=null&&!locator.isBlank())targets.add(ownedPath(tenant,locator));
            // Reject shared legacy locators before touching storage, even when spelling differs.
            List<String> other=new ArrayList<>(jdbc.queryForList("SELECT t.pdf_path FROM pdf_file_topic_details t JOIN pdf_file_details b ON b.book_id=t.book_id WHERE b.tenant_id=? AND b.book_id<>? AND t.pdf_path IS NOT NULL",String.class,tenant,book==null?-1:book));
            other.addAll(jdbc.queryForList("SELECT original_upload_path FROM spreadsheet_file_details WHERE tenant_id=? AND file_id<>?",String.class,tenant,file==null?-1:file));
            other.addAll(jdbc.queryForList("SELECT s.upload_path FROM spreadsheet_sheet_details s JOIN spreadsheet_file_details f ON f.file_id=s.file_id WHERE f.tenant_id=? AND f.file_id<>?",String.class,tenant,file==null?-1:file));
            for(String locator:other)if(locator!=null&&!locator.isBlank()&&targets.contains(normalize(tenant,locator)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"This upload shares a stored object with another file; deletion requires an ownership-reviewed migration.");
            for(Path target:targets)Files.deleteIfExists(target);
        } catch(IOException | SecurityException failure) {throw retryable();}
        if(book!=null){
            Object author=books.getFirst().get("author_id");
            jdbc.update("DELETE FROM pdf_file_topic_details WHERE book_id=?",book);
            jdbc.update("DELETE FROM pdf_file_details WHERE tenant_id=? AND book_id=?",tenant,book);
            // AuthorDetails retains the original aouthor_id primary-key spelling;
            // the book's foreign-key column is separately named author_id.
            if(author!=null)jdbc.update("DELETE FROM pdf_file_author_details WHERE aouthor_id=? AND NOT EXISTS(SELECT 1 FROM pdf_file_details WHERE author_id=?)",author,author);
        }
        if(file!=null){
            jdbc.update("DELETE FROM spreadsheet_sheet_rows WHERE file_id=?",file);
            jdbc.update("DELETE FROM spreadsheet_sheet_details WHERE file_id=?",file);
            jdbc.update("DELETE FROM spreadsheet_file_details WHERE tenant_id=? AND file_id=?",tenant,file);
        }
        if(jdbc.update("DELETE FROM pdf_file_status WHERE tenant_id=? AND book_upload_id=?",tenant,upload)!=1)throw missing();
    }
    private Path ownedPath(long tenant,String locator)throws IOException {
        Path path=normalize(tenant,locator);
        if(!path.startsWith(storage.tenantPath(tenant)))throw new IOException("Stored path escapes tenant storage.");
        Path existing=path;
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {existing=existing.getParent();if(existing==null)throw new IOException("Missing storage root.");}
        storage.requirePath(tenant,existing);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported storage object.");
        return path;
    }
    private Path normalize(long tenant,String locator)throws IOException {
        try {
            Path path=Path.of(locator);
            for(Path part:path)if(part.toString().equals(".."))throw new IOException("Traversal is forbidden.");
            return (path.isAbsolute()?path:storage.tenantPath(tenant).resolve(path)).toAbsolutePath().normalize();
        } catch(InvalidPathException failure){throw new IOException("Invalid stored path.",failure);}
    }
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static long nullableNumber(Map<String,Object> row,String name){return row.get(name) instanceof Number value?value.longValue():-1;}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"The upload was not found or is not owned by your account.");}
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"A positive file or upload ID is required.");}
    private static ResponseStatusException retryable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Deletion did not complete. Some stored artifacts may already have been removed; metadata is retained for cleanup. Retry Delete for the same file ID after the storage/database issue is corrected.");}
}
