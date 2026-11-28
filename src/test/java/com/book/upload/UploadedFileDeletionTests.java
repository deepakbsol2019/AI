package com.book.upload;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import com.book.spreadsheet.persistence.SpreadsheetTestDatabase;
import com.book.spreadsheet.persistence.SpreadsheetRepository;
import com.book.storage.TenantStoragePaths;
import com.book.tools.ServiceToolAccess;
import com.book.upload.service.UploadedFileDeletionService;
import com.book.upload.service.FileDeletionPolicy;
import com.book.user.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadedFileDeletionTests {
    @TempDir Path root;
    JdbcTemplate jdbc;
    TenantStoragePaths storage;
    ServiceToolAccess access;
    FileDeletionPolicy policy;
    UploadedFileDeletionService service;
    @BeforeEach void setup()throws Exception {
        jdbc=SpreadsheetTestDatabase.create();
        jdbc.execute("ALTER TABLE pdf_file_details ADD COLUMN book_upload_id BIGINT");
        jdbc.execute("ALTER TABLE pdf_file_details ADD COLUMN author_id BIGINT");
        // Column names and parent/child references follow the current entity mappings.
        jdbc.execute("CREATE TABLE pdf_file_author_details(aouthor_id BIGINT PRIMARY KEY)");
        jdbc.execute("ALTER TABLE pdf_file_details ADD CONSTRAINT fk_delete_author FOREIGN KEY(author_id) REFERENCES pdf_file_author_details(aouthor_id)");
        jdbc.execute("ALTER TABLE pdf_file_details ADD CONSTRAINT fk_delete_upload FOREIGN KEY(book_upload_id) REFERENCES pdf_file_status(book_upload_id)");
        jdbc.execute("CREATE TABLE pdf_file_topic_details(topic_id BIGINT PRIMARY KEY,book_id BIGINT NOT NULL,pdf_path VARCHAR(2048),FOREIGN KEY(book_id) REFERENCES pdf_file_details(book_id))");
        storage=new TenantStoragePaths(root.toString());storage.provision(1);storage.provision(2);
        access=mock(ServiceToolAccess.class);
        policy=mock(FileDeletionPolicy.class);
        when(policy.scope(any())).thenAnswer(call->new FileDeletionPolicy.Scope(TenantContext.requireTenantId(),TenantContext.requireUserId(),false,false,true));
        service=new UploadedFileDeletionService(jdbc,storage,new DataSourceTransactionManager(jdbc.getDataSource()),access,policy);
    }
    Path pdf(long id,long tenant,long user)throws Exception {
        Path file=Files.writeString(storage.tenantPath(tenant).resolve("file-"+id+".pdf"),"pdf "+id);
        receipt(id,tenant,user,"Success","COMPLETED");
        jdbc.update("INSERT INTO pdf_file_author_details(aouthor_id) VALUES(?)",id);
        jdbc.update("UPDATE pdf_file_status SET file_name='identical.pdf' WHERE book_upload_id=?",id);
        jdbc.update("INSERT INTO pdf_file_details(book_id,tenant_id,book_name,book_upload_id,author_id) VALUES(?,?,?,?,?)",id,tenant,"Book "+id,id,id);
        jdbc.update("INSERT INTO pdf_file_topic_details VALUES(?,?,?)",id,id,file.toString());
        return file;
    }
    void receipt(long id,long tenant,long user,String status,String state){jdbc.update("INSERT INTO pdf_file_status(book_upload_id,tenant_id,uploader_user_id,status,processing_state,message) VALUES(?,?,?,?,?,'test')",id,tenant,user,status,state);}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}

    @Test void deletesOnlySelectedPdfIdWithIdenticalNames()throws Exception {
        Path first=pdf(1,1,11), second=pdf(2,1,11), foreign=pdf(3,2,22);
        try(var ignored=TenantContext.use(1,11L,false)){service.deletePdf(1);}
        assertThat(first).doesNotExist();assertThat(second).exists();assertThat(foreign).exists();
        assertThat(count("pdf_file_details")).isEqualTo(2);
        assertThat(count("pdf_file_topic_details")).isEqualTo(2);
        assertThat(count("pdf_file_author_details")).isEqualTo(2);
        assertThat(count("pdf_file_status")).isEqualTo(2);
        verify(access).require("DELETE","/api/books/1");
    }
    @Test void storageIsNotRolledBackButDatabaseFailureRetainsRetryLocators()throws Exception {
        Path first=pdf(1,1,11);
        jdbc.execute("CREATE TABLE deletion_blocker(upload_id BIGINT,FOREIGN KEY(upload_id) REFERENCES pdf_file_status(book_upload_id))");
        jdbc.update("INSERT INTO deletion_blocker VALUES(1)");
        try(var ignored=TenantContext.use(1,11L,false)){
            assertThatThrownBy(()->service.deletePdf(1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThat(first).doesNotExist();
            assertThat(count("pdf_file_details")).isEqualTo(1);
            assertThat(count("pdf_file_topic_details")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT pdf_path FROM pdf_file_topic_details WHERE topic_id=1",String.class)).isEqualTo(first.toString());
            jdbc.update("DELETE FROM deletion_blocker");
            service.deletePdf(1);
            assertThat(count("pdf_file_details")).isZero();assertThat(count("pdf_file_status")).isZero();
        }
    }
    @Test void rejectsNonOwnerForeignTenantAndActiveUploadsWithoutStorageChanges()throws Exception {
        Path first=pdf(1,1,11), foreign=pdf(2,2,11);
        try(var ignored=TenantContext.use(1,22L,false)){assertThatThrownBy(()->service.deletePdf(1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));}
        try(var ignored=TenantContext.use(1,11L,false)){
            assertThatThrownBy(()->service.deletePdf(2)).isInstanceOf(ResponseStatusException.class);
            jdbc.update("UPDATE pdf_file_status SET status='inprogress',processing_state='PROCESSING' WHERE book_upload_id=1");
            assertThatThrownBy(()->service.deletePdf(1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        }
        assertThat(first).exists();assertThat(foreign).exists();assertThat(count("pdf_file_details")).isEqualTo(2);
    }
    @Test void refusesSharedStorageKeysEvenForDifferentIds()throws Exception {
        Path first=pdf(1,1,11);pdf(2,1,11);
        jdbc.update("UPDATE pdf_file_topic_details SET pdf_path=? WHERE topic_id=2",first.toString());
        try(var ignored=TenantContext.use(1,11L,false)){
            assertThatThrownBy(()->service.deletePdf(1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        }
        assertThat(first).exists();assertThat(count("pdf_file_details")).isEqualTo(2);
    }
    @Test void storageFailureKeepsMetadataAndCanBeRetried()throws Exception {
        Path first=pdf(1,1,11);
        var failing=spy(storage);
        doThrow(new IOException("offline")).when(failing).requirePath(eq(1L),any(Path.class));
        var retryService=new UploadedFileDeletionService(jdbc,failing,new DataSourceTransactionManager(jdbc.getDataSource()),access,policy);
        try(var ignored=TenantContext.use(1,11L,false)){
            assertThatThrownBy(()->retryService.deletePdf(1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThat(first).exists();assertThat(count("pdf_file_details")).isEqualTo(1);
            service.deletePdf(1);assertThat(first).doesNotExist();
        }
    }
    @Test void deletesSpreadsheetOriginalSheetArtifactsAndRowsWithoutTouchingAnotherFile()throws Exception {
        var repository=SpreadsheetTestDatabase.transactional(new SpreadsheetRepository(jdbc),jdbc);
        var one=repository.reserve(1,11,"same.csv","first","CSV");
        var two=repository.reserve(1,11,"same.csv","second","CSV");
        Path original=Files.writeString(storage.tenantPath(1).resolve("original.csv"),"a\n1"), extracted=Files.writeString(storage.tenantPath(1).resolve("sheet.csv"),"a\n1"), other=Files.writeString(storage.tenantPath(1).resolve("other.csv"),"a\n2");
        repository.original(1,11,one.file().fileId(),original.toString(),1);
        var sheet=repository.createSheet(1,11,one.file().fileId(),1,"Sheet",1,Map.of("column_1","a"));
        repository.sheetArtifact(1,11,one.file().fileId(),sheet.sheetId(),extracted.toString());
        repository.insertRows(1,11,one.file().fileId(),sheet.sheetId(),java.util.List.of(Map.of("column_1","1")),java.util.List.of(2L));
        repository.complete(1,11,one.file().fileId(),Map.of(sheet.sheetId(),1L));
        repository.original(1,11,two.file().fileId(),other.toString(),1);
        try(var ignored=TenantContext.use(1,11L,false)){service.deleteSpreadsheet(one.file().fileId());}
        assertThat(original).doesNotExist();assertThat(extracted).doesNotExist();assertThat(other).exists();
        assertThat(count("spreadsheet_file_details")).isEqualTo(1);assertThat(count("spreadsheet_sheet_details")).isZero();assertThat(count("spreadsheet_sheet_rows")).isZero();assertThat(count("pdf_file_status")).isEqualTo(1);
    }
    @Test void deletesFailedReceiptAndEnforcesToolBoundary() {
        receipt(1,1,11,"failed","FAILED");
        try(var ignored=TenantContext.use(1,11L,false)){service.deleteUpload(1);}
        assertThat(count("pdf_file_status")).isZero();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(access).require(anyString(),anyString());
        try(var ignored=TenantContext.use(1,11L,false)){assertThatThrownBy(()->service.deletePdf(1)).isInstanceOf(ResponseStatusException.class);}
        assertThatThrownBy(()->service.deleteSpreadsheet(1)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void tenantAdminCanDeleteAnotherUploaderButOnlyGlobalSuperUserCrossesTenants()throws Exception {
        Path local=pdf(1,1,22), foreign=pdf(2,2,22);
        doReturn(new FileDeletionPolicy.Scope(1,11,false,true,true)).when(policy).scope(any());
        try(var ignored=TenantContext.use(1,11L,false)) {
            service.deletePdf(1);assertThat(local).doesNotExist();
            assertThatThrownBy(()->service.deletePdf(2)).isInstanceOf(ResponseStatusException.class);
            assertThat(foreign).exists();
            when(policy.scope(any())).thenReturn(new FileDeletionPolicy.Scope(1,11,true,false,true));
            service.deletePdf(2);assertThat(foreign).doesNotExist();
        }
        assertThat(count("pdf_file_details")).isZero();assertThat(count("pdf_file_status")).isZero();
    }
}
