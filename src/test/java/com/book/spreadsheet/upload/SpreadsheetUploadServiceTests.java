package com.book.spreadsheet.upload;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.book.entity.BookUploadStatus;
import com.book.spreadsheet.parse.*;
import com.book.spreadsheet.persistence.*;
import com.book.storage.*;
import com.book.upload.service.BookUploadStatusService;
import com.book.user.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpreadsheetUploadServiceTests {
    @TempDir Path root;
    JdbcTemplate jdbc; SpreadsheetRepository repository; SpreadsheetBatchWorker worker;
    BookUploadStatusService statuses; DocumentFileStore storage; ThreadPoolTaskExecutor batches;
    List<Runnable> jobs;
    @BeforeEach void setup() throws Exception {
        jdbc=SpreadsheetTestDatabase.create();repository=SpreadsheetTestDatabase.transactional(new SpreadsheetRepository(jdbc),jdbc);
        worker=SpreadsheetTestDatabase.transactional(new SpreadsheetBatchWorker(repository),jdbc);
        storage=spy(new DocumentFileStore(new TenantStoragePaths(root.toString())));
        batches=new SpreadsheetUploadConfiguration().spreadsheetBatchExecutor(2,4);batches.initialize();jobs=new ArrayList<>();
        statuses=mock(BookUploadStatusService.class);
        doAnswer(call->{
            assertThat(TenantContext.requireTenantId()).isEqualTo(1);assertThat(TenantContext.requireUserId()).isEqualTo(11);
            jdbc.update("UPDATE pdf_file_status SET processing_state='PROCESSING' WHERE book_upload_id=?",call.<Long>getArgument(0));return null;
        }).when(statuses).processing(anyLong());
        doAnswer(call->{jdbc.update("UPDATE pdf_file_status SET processing_state='FAILED',status='failed',message='Processing failed' WHERE book_upload_id=?",call.<Long>getArgument(0));return null;}).when(statuses).fail(anyLong(),any());
    }
    @AfterEach void shutdown(){batches.shutdown();}
    SpreadsheetUploadService service(SpreadsheetBatchWorker selectedWorker,Executor dispatch) {
        return new SpreadsheetUploadService(new SpreadsheetParser(),repository,selectedWorker,storage,statuses,dispatch,batches);
    }
    SpreadsheetUploadService.UploadReceipt submit(SpreadsheetUploadService service,String name,String csv) throws Exception {
        try(var ignored=TenantContext.use(1,11L,false)) {
            return service.submit(new MockMultipartFile("file",name,"text/csv",csv.getBytes(StandardCharsets.UTF_8)),SpreadsheetFormat.CSV);
        }
    }
    String csv(int count) {
        StringBuilder value=new StringBuilder("ID,Name,Extra\r\n");
        for(int i=0;i<count;i++)value.append(String.format("%05d,Name %d,\r\n",i,i));
        return value.toString();
    }
    @Test void asynchronousReceiptCapturesActorAndWaitsForAllIndependentBatchesBeforePublication() throws Exception {
        var sizes=Collections.synchronizedList(new ArrayList<Integer>());var active=new AtomicInteger();var peak=new AtomicInteger();var entered=new CountDownLatch(2);
        SpreadsheetBatchWorker observed=mock(SpreadsheetBatchWorker.class);
        when(observed.persist(anyLong(),anyLong(),anyLong(),anyLong(),any())).thenAnswer(call->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try {
                var batch=call.<SpreadsheetRowBatch>getArgument(4);sizes.add(batch.rows().size());entered.countDown();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                return worker.persist(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),batch);
            } finally {active.decrementAndGet();}
        });
        String original=csv(1200);var receipt=submit(service(observed,jobs::add),"Batch.csv",original);
        assertThat(receipt.upload().getState()).isEqualTo("PENDING");assertThat(receipt.sheetCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_details",Long.class)).isZero();
        assertThat(repository.listCompleted(1,0,20).content()).isEmpty();
        try(var unrelated=TenantContext.use(99,99L,true)){jobs.getFirst().run();assertThat(TenantContext.currentTenantId()).isEqualTo(99);}
        assertThat(sizes).containsExactlyInAnyOrder(500,500,200);assertThat(peak.get()).isBetween(2,2);
        var file=repository.requireCompleted(1,receipt.fileId());var sheet=repository.sheets(1,file.fileId()).getFirst();
        assertThat(sheet.dataRowCount()).isEqualTo(1200);assertThat(file.createdBy()).isEqualTo(11);
        assertThat(storage.read(1,file.originalUploadPath())).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
        assertThat(sheet.uploadPath()).isEqualTo(file.originalUploadPath());
        verify(storage,times(1)).write(eq(1L),eq(11L),eq("CSV"),eq("Batch"),eq("Batch.csv"),any(Path.class));
        assertThat(Path.of(file.originalUploadPath())).isEqualTo(root.resolve("1/CSV/11/Batch/Batch.csv"));
        var last=repository.rows(1,file.fileId(),sheet.sheetId(),1200,500);
        assertThat(last).hasSize(1);assertThat(last.getFirst().rowNumber()).isEqualTo(1201);
        assertThat(last.getFirst().payload()).containsEntry("column_1","01199").containsEntry("column_3","");
        assertThat(receipt.upload().getState()).isEqualTo("PENDING"); // detached acceptance is not a completion claim.
        assertThat(TenantContext.currentTenantId()).isNull();
    }
    @Test void headerOnlySheetCreatesNoRowTaskAndWorkerWritesUseTheirOwnTransaction() throws Exception {
        var noRows=mock(SpreadsheetBatchWorker.class);var receipt=submit(service(noRows,jobs::add),"Header.csv","ID,Name\n");
        jobs.getFirst().run();verifyNoInteractions(noRows);
        assertThat(repository.sheets(1,receipt.fileId()).getFirst().dataRowCount()).isZero();
        assertThat(repository.requireCompleted(1,receipt.fileId()).sheetCount()).isEqualTo(1);
        var instrumented=spy(new SpreadsheetRepository(jdbc));
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TenantContext.requireTenantId()).isEqualTo(1);assertThat(TenantContext.requireUserId()).isEqualTo(11);return call.callRealMethod();})
                .when(instrumented).insertRows(anyLong(),anyLong(),anyLong(),anyLong(),anyList(),anyList());
        var transactionalWorker=SpreadsheetTestDatabase.transactional(new SpreadsheetBatchWorker(instrumented),jdbc);
        var second=submit(service(transactionalWorker,jobs::add),"Data.csv",csv(1));jobs.getLast().run();
        assertThat(repository.requireCompleted(1,second.fileId()).sheetCount()).isEqualTo(1);
    }
    @Test void failureWaitsForRunningWorkersThenRemovesOnlyItsRowsAndOriginalObject() throws Exception {
        var secondStarted=new CountDownLatch(1);var releaseSecond=new CountDownLatch(1);var secondFinished=new AtomicBoolean();
        var broken=mock(SpreadsheetBatchWorker.class);
        when(broken.persist(anyLong(),anyLong(),anyLong(),anyLong(),any())).thenAnswer(call->{
            var batch=call.<SpreadsheetRowBatch>getArgument(4);long first=batch.rowNumbers().getFirst();
            if(first==2){assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();throw new IllegalStateException("Controlled first-batch failure");}
            if(first==502){secondStarted.countDown();assertThat(releaseSecond.await(5,TimeUnit.SECONDS)).isTrue();}
            try{return worker.persist(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),batch);}
            finally{if(first==502)secondFinished.set(true);}
        });
        doAnswer(call->{assertThat(secondFinished.get()).isTrue();return call.callRealMethod();}).when(storage).delete(anyLong(),anyString());
        var receipt=submit(service(broken,jobs::add),"Failure.csv",csv(1200));
        var runner=Executors.newSingleThreadExecutor();Future<?> running=runner.submit(jobs.getFirst());
        try {
            assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(80);
            assertThat(running.isDone()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isEqualTo(1);
            releaseSecond.countDown();running.get(5,TimeUnit.SECONDS);
        } finally {releaseSecond.countDown();runner.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_rows",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM pdf_file_status WHERE book_upload_id=?",String.class,receipt.upload().getBookUploadId())).isEqualTo("failed");
        try(var paths=Files.walk(root)){assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();}
        verify(statuses).fail(eq(receipt.upload().getBookUploadId()),any());
    }
    @Test void duplicateCreatesNoMoreObjectsAndCompensatedFailureCanBeRetried() throws Exception {
        var first=submit(service(worker,jobs::add),"One.csv",csv(1));jobs.getFirst().run();
        assertThatThrownBy(()->submit(service(worker,jobs::add),"one.csv",csv(2))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jobs).hasSize(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status",Long.class)).isEqualTo(1);
        var failed=mock(SpreadsheetBatchWorker.class);when(failed.persist(anyLong(),anyLong(),anyLong(),anyLong(),any())).thenThrow(new IllegalStateException("Failed test batch"));
        var receipt=submit(service(failed,jobs::add),"Retry.csv",csv(1));jobs.getLast().run();
        var retry=submit(service(worker,jobs::add),"Retry.csv",csv(1));jobs.getLast().run();
        assertThat(repository.requireCompleted(1,retry.fileId()).fileName()).isEqualTo("Retry.csv");
        assertThat(repository.requireCompleted(1,first.fileId()).fileName()).isEqualTo("One.csv");
        assertThat(retry.fileId()).isNotEqualTo(receipt.fileId());
    }
    @Test void objectUploadFailureCannotPublishAnIncompleteSpreadsheet() throws Exception {
        doThrow(new IOException("Controlled storage failure")).when(storage).write(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(Path.class));
        var receipt=submit(service(worker,jobs::add),"Storage.csv",csv(1));jobs.getFirst().run();
        assertThat(repository.listCompleted(1,0,20).content()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isZero();
        verify(statuses).fail(eq(receipt.upload().getBookUploadId()),any());
    }
    @Test void invalidOrUnreadableInputIsRejectedBeforeAnyReceiptOrDispatch() throws Exception {
        assertThatThrownBy(()->submit(service(worker,jobs::add),"Empty.csv","")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->submit(service(worker,jobs::add),"../Bad.csv",csv(1))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status",Long.class)).isZero();assertThat(jobs).isEmpty();
    }
    @ParameterizedTest @EnumSource(value=SpreadsheetFormat.class,names={"XLS","XLSX"})
    void allSheetsIncludingHeaderOnlyAndEmptyHaveIndependentXlsxArtifacts(SpreadsheetFormat format) throws Exception {
        byte[] original=excel(format);
        SpreadsheetUploadService.UploadReceipt receipt;
        try(var ignored=TenantContext.use(1,11L,false)) {
            receipt=service(worker,jobs::add).submit(new MockMultipartFile("file","Multiple."+format.extension(),format.contentType(),original),format);
        }
        jobs.getFirst().run();var file=repository.requireCompleted(1,receipt.fileId());
        assertThat(file.sheetCount()).isEqualTo(3);assertThat(file.format()).isEqualTo(format.name());
        assertThat(storage.read(1,file.originalUploadPath())).isEqualTo(original);
        var sheets=repository.sheets(1,file.fileId());assertThat(sheets).extracting(SpreadsheetRepository.SheetRecord::sheetName).containsExactly("Data","HeaderOnly","Empty");
        assertThat(sheets).extracting(SpreadsheetRepository.SheetRecord::dataRowCount).containsExactly(1L,0L,0L);
        for(var sheet:sheets) {
            assertThat(sheet.uploadPath()).endsWith(".xlsx");assertThat(sheet.uploadPath()).isNotEqualTo(file.originalUploadPath());
            try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(storage.read(1,sheet.uploadPath())))) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1);assertThat(workbook.getSheetName(0)).isEqualTo(sheet.sheetName());
            }
        }
        assertThat(sheets.getLast().header()).isEmpty();assertThat(sheets.getLast().columnCount()).isZero();
    }
    @Test void failureAfterFirstExtractedSheetWaitsForRowsAndCleansOriginalAndStoredSheets() throws Exception {
        doAnswer(call->{
            String leaf=call.getArgument(4);
            if(leaf.contains("HeaderOnly"))throw new IOException("Controlled second-sheet failure");
            return call.callRealMethod();
        }).when(storage).write(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(Path.class));
        SpreadsheetUploadService.UploadReceipt receipt;
        try(var ignored=TenantContext.use(1,11L,false)) {
            receipt=service(worker,jobs::add).submit(new MockMultipartFile("file","Multiple.xlsx",SpreadsheetFormat.XLSX.contentType(),excel(SpreadsheetFormat.XLSX)),SpreadsheetFormat.XLSX);
        }
        jobs.getFirst().run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_file_details",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spreadsheet_sheet_rows",Long.class)).isZero();
        try(var paths=Files.walk(root)){assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();}
        verify(storage,times(2)).delete(anyLong(),anyString());verify(statuses).fail(eq(receipt.upload().getBookUploadId()),any());
    }
    private byte[] excel(SpreadsheetFormat format) throws Exception {
        try(Workbook workbook=format==SpreadsheetFormat.XLS?new HSSFWorkbook():new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var data=workbook.createSheet("Data");data.createRow(0).createCell(0).setCellValue("ID");data.createRow(1).createCell(0).setCellValue("0012");
            workbook.createSheet("HeaderOnly").createRow(0).createCell(0).setCellValue("Title");workbook.createSheet("Empty");
            workbook.setSheetHidden(1,true);workbook.write(output);return output.toByteArray();
        }
    }
}
