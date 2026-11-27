package com.book.spreadsheet.persistence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.book.spreadsheet.parse.SpreadsheetRowBatch;
import com.book.spreadsheet.upload.SpreadsheetBatchWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class SpreadsheetRepositoryTests {
    JdbcTemplate jdbc; SpreadsheetRepository repository; SpreadsheetBatchWorker worker;
    @BeforeEach void setup() throws Exception {
        jdbc=SpreadsheetTestDatabase.create();repository=SpreadsheetTestDatabase.transactional(new SpreadsheetRepository(jdbc),jdbc);
        worker=SpreadsheetTestDatabase.transactional(new SpreadsheetBatchWorker(repository),jdbc);
    }
    @ParameterizedTest @ValueSource(ints={0,1,499,500,501,1200})
    void fixedBatchesPersistEverySourceRowAsOrderedStringJsonWithoutPadding(int total) {
        var file=repository.reserve(1,11,"Batch"+total+".csv","Batch"+total,"CSV").file();
        repository.original(1,11,file.fileId(),"original.csv",1);
        var header=new LinkedHashMap<String,String>();header.put("column_1","Repeated");header.put("column_2","Repeated");header.put("column_3","");
        var sheet=repository.createSheet(1,11,file.fileId(),1,"Sheet1",3,header);
        repository.sheetArtifact(1,11,file.fileId(),sheet.sheetId(),"original.csv");
        List<Integer> sizes=new ArrayList<>();
        for(int offset=0;offset<total;offset+=500) {
            var rows=new ArrayList<Map<String,String>>();var numbers=new ArrayList<Long>();
            for(int i=offset;i<Math.min(offset+500,total);i++) {
                var row=new LinkedHashMap<String,String>();row.put("column_1",String.format("%05d",i));row.put("column_2","false");row.put("column_3","");
                rows.add(row);numbers.add(2L+i*2); // skipped blank source rows keep their original positions.
            }
            sizes.add(worker.persist(1,11,file.fileId(),sheet.sheetId(),new SpreadsheetRowBatch(rows,numbers)));
        }
        assertThat(sizes).containsExactlyElementsOf(total==0?List.of():total<=500?List.of(total):total==501?List.of(500,1):List.of(500,500,200));
        assertThat(repository.listCompleted(1,0,20).content()).isEmpty();
        repository.complete(1,11,file.fileId(),Map.of(sheet.sheetId(),(long)total));
        var read=new ArrayList<SpreadsheetRepository.RowRecord>();long cursor=0;
        while(true){var page=repository.rows(1,file.fileId(),sheet.sheetId(),cursor,500);if(page.isEmpty())break;read.addAll(page);cursor=page.getLast().rowNumber();}
        assertThat(read).hasSize(total);
        for(int i=0;i<total;i++) {
            assertThat(read.get(i).rowNumber()).isEqualTo(2L+i*2);
            assertThat(read.get(i).payload()).containsEntry("column_1",String.format("%05d",i)).containsEntry("column_2","false").containsEntry("column_3","");
        }
        assertThat(repository.sheets(1,file.fileId()).getFirst().dataRowCount()).isEqualTo(total);
        assertThat(repository.sheets(1,file.fileId()).getFirst().header()).containsExactlyEntriesOf(header);
        if(total>0)assertThat(jdbc.queryForObject("SELECT MIN(payload_id) FROM spreadsheet_sheet_rows",Long.class)).isEqualTo(1L);
        var raw=jdbc.queryForObject("SELECT uploaded_at FROM spreadsheet_file_details WHERE file_id=?",(r,n)->r.getObject(1,LocalDateTime.class),file.fileId());
        assertThat(Duration.between(raw.toInstant(ZoneOffset.UTC),file.uploadedAt()).abs()).isLessThan(Duration.ofMillis(1));
    }
    @Test void duplicatesUseTenantNameRulesAndConcurrentLoserCreatesNoReceiptOrArtifactMetadata() throws Exception {
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{repository.reserve(1,11,"Report.xlsx","Report","XLSX");return true;}catch(ResponseStatusException duplicate){assertThat(duplicate.getStatusCode().value()).isEqualTo(409);return false;}}));
            gate.countDown();int accepted=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally {pool.shutdownNow();}
        assertThatThrownBy(()->repository.reserve(1,11,"report.csv"," REPORT ","CSV")).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_details",Long.class)).isZero();
        assertThat(repository.reserve(2,22,"Report.xlsx","Report","XLSX").file().tenantId()).isEqualTo(2);
        jdbc.update("INSERT INTO pdf_file_details(tenant_id,book_name) VALUES(1,'Existing Book')");
        assertThatThrownBy(()->repository.reserve(1,11,"Existing Book.csv"," existing book ","CSV")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void incompleteForeignAndInvalidSelectionsCannotExposeRowsAndCompletedSearchIsScoped() {
        var file=repository.reserve(1,11,"100%_Report.csv","100%_Report","CSV").file();
        var sheet=repository.createSheet(1,11,file.fileId(),1,"Sheet1",1,Map.of("column_1","ID"));
        worker.persist(1,11,file.fileId(),sheet.sheetId(),new SpreadsheetRowBatch(List.of(Map.of("column_1","0012")),List.of(2L)));
        assertThat(repository.rows(1,file.fileId(),sheet.sheetId(),0,500)).isEmpty();
        assertThatThrownBy(()->repository.complete(1,11,file.fileId(),Map.of(sheet.sheetId(),1L))).isInstanceOf(IllegalStateException.class);
        repository.original(1,11,file.fileId(),"original.csv",1);repository.sheetArtifact(1,11,file.fileId(),sheet.sheetId(),"original.csv");
        repository.complete(1,11,file.fileId(),Map.of(sheet.sheetId(),1L));
        assertThat(repository.listCompleted(1,0,20,"%_").content()).hasSize(1);
        assertThat(repository.listCompleted(2,0,20,"Report").content()).isEmpty();
        assertThat(repository.rows(2,file.fileId(),sheet.sheetId(),0,500)).isEmpty();
        assertThatThrownBy(()->repository.requireCompleted(2,file.fileId())).isInstanceOf(ResponseStatusException.class);
        assertThat(repository.rowsPage(1,file.fileId(),sheet.sheetId(),0,1)).hasSize(1);
        assertThat(repository.rowsPage(1,file.fileId(),sheet.sheetId(),1,1)).isEmpty();
        assertThatThrownBy(()->repository.rowsPage(1,file.fileId(),sheet.sheetId(),-1,20)).isInstanceOf(ResponseStatusException.class);
        assertThat(repository.requireCompleted(1,file.fileId()).createdByName()).isEqualTo("First Uploader");
        jdbc.update("DELETE FROM users WHERE user_id=11");
        assertThat(repository.requireCompleted(1,file.fileId()).createdByName()).isEqualTo("Former user");
    }
    @Test void compositeParentAndSourceRowConstraintsAndBatchRollbackPreventCorruption() {
        var one=repository.reserve(1,11,"One.csv","One","CSV").file();
        var two=repository.reserve(1,11,"Two.csv","Two","CSV").file();
        var sheet=repository.createSheet(1,11,one.fileId(),1,"Sheet1",1,Map.of("column_1","ID"));
        assertThatThrownBy(()->jdbc.update("INSERT INTO spreadsheet_sheet_rows(file_id,sheet_id,`row_number`,payload) VALUES(?,?,2,'{}' FORMAT JSON)",two.fileId(),sheet.sheetId())).isInstanceOf(DataIntegrityViolationException.class);
        var duplicated=new SpreadsheetRowBatch(List.of(Map.of("column_1","a"),Map.of("column_1","b")),List.of(2L,2L));
        assertThatThrownBy(()->worker.persist(1,11,one.fileId(),sheet.sheetId(),duplicated)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_rows",Long.class)).isZero();
        assertThatThrownBy(()->worker.persist(2,22,one.fileId(),sheet.sheetId(),new SpreadsheetRowBatch(List.of(Map.of("column_1","a")),List.of(2L)))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void onlyTheFailedAttemptIsRemovedAndItsReceiptSurvivesForHistory() {
        var one=repository.reserve(1,11,"One.csv","One","CSV");var two=repository.reserve(1,11,"Two.csv","Two","CSV");
        var sheet=repository.createSheet(1,11,one.file().fileId(),1,"Sheet1",1,Map.of("column_1","ID"));
        worker.persist(1,11,one.file().fileId(),sheet.sheetId(),new SpreadsheetRowBatch(List.of(Map.of("column_1","a")),List.of(2L)));
        repository.removeAttempt(2,22,one.file().fileId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isEqualTo(2);
        repository.removeAttempt(1,11,one.file().fileId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_rows",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details WHERE file_id=?",Long.class,two.file().fileId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status",Long.class)).isEqualTo(2);
        assertThat(repository.reserve(1,11,"One.csv","One","CSV").file().fileId()).isGreaterThan(one.file().fileId());
    }
    @Test void mysqlJsonPropertyReorderingCannotMoveColumnTenBeforeColumnTwo() {
        var file=repository.reserve(1,11,"Wide.csv","Wide","CSV").file();
        var scrambled=new LinkedHashMap<String,String>();
        for(int column:List.of(1,10,2,3,4,5,6,7,8,9))scrambled.put("column_"+column,"Value "+column);
        var sheet=repository.createSheet(1,11,file.fileId(),1,"Sheet1",10,scrambled);
        repository.original(1,11,file.fileId(),"original.csv",1);repository.sheetArtifact(1,11,file.fileId(),sheet.sheetId(),"original.csv");
        worker.persist(1,11,file.fileId(),sheet.sheetId(),new SpreadsheetRowBatch(List.of(scrambled),List.of(2L)));
        repository.complete(1,11,file.fileId(),Map.of(sheet.sheetId(),1L));
        var ordered=new ArrayList<String>();for(int column=1;column<=10;column++)ordered.add("column_"+column);
        assertThat(repository.sheets(1,file.fileId()).getFirst().header().keySet()).containsExactlyElementsOf(ordered);
        assertThat(repository.rows(1,file.fileId(),sheet.sheetId(),0,100).getFirst().payload().keySet()).containsExactlyElementsOf(ordered);
        assertThat(repository.rowsPage(1,file.fileId(),sheet.sheetId(),0,100).getFirst().payload()).containsEntry("column_10","Value 10");
    }
}
