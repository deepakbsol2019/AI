package com.book.spreadsheet.upload;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.book.entity.BookUploadStatus;
import com.book.spreadsheet.parse.*;
import com.book.spreadsheet.persistence.SpreadsheetRepository;
import com.book.spreadsheet.persistence.SpreadsheetRepository.SheetRecord;
import com.book.storage.DocumentFileStore;
import com.book.upload.service.BookUploadStatusService;
import com.book.user.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** No outer transaction: committed sheet IDs precede independently committing row workers. */
@Service
public class SpreadsheetUploadService {
    private static final Logger log=LoggerFactory.getLogger(SpreadsheetUploadService.class);
    private static final long MAX_BYTES=100L*1024*1024;
    private static final int MAX_PENDING_BATCHES=6;
    private final SpreadsheetParser parser;
    private final SpreadsheetRepository repository;
    private final SpreadsheetBatchWorker worker;
    private final DocumentFileStore storage;
    private final BookUploadStatusService statuses;
    private final Executor uploads;
    private final Executor batches;
    public SpreadsheetUploadService(SpreadsheetParser parser,SpreadsheetRepository repository,SpreadsheetBatchWorker worker,
            DocumentFileStore storage,BookUploadStatusService statuses,@Qualifier("bookUploadExecutor") Executor uploads,
            @Qualifier("spreadsheetBatchExecutor") Executor batches) {
        this.parser=parser;this.repository=repository;this.worker=worker;this.storage=storage;
        this.statuses=statuses;this.uploads=uploads;this.batches=batches;
    }
    public record UploadReceipt(BookUploadStatus upload,Long fileId,int sheetCount,List<SheetRecord> sheets) { }

    public UploadReceipt submit(MultipartFile source,SpreadsheetFormat format) throws IOException {
        long tenant=TenantContext.requireTenantId(),user=TenantContext.requireUserId();
        String filename=source==null?null:source.getOriginalFilename();
        if(filename==null || filename.isBlank() || filename.length()>255 || filename.chars().anyMatch(c->c<32 || c==127 || c=='/' || c=='\\')
                || source.isEmpty() || source.getSize()>MAX_BYTES || format==null || SpreadsheetFormat.fromFilename(filename)!=format)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a named, readable CSV, XLSX or XLS file of at most 100 MiB.");
        String base=filename.substring(0,filename.lastIndexOf('.')).strip();
        if(base.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The document needs a filename before its extension.");
        Path spool=Files.createTempFile("spreadsheet-upload-","."+format.extension());
        boolean dispatched=false;
        ParsedSpreadsheet validated=null;
        try {
            try(var input=source.getInputStream();var output=Files.newOutputStream(spool)) {
                byte[] buffer=new byte[8192];long count=0;int length;
                while((length=input.read(buffer))!=-1) {
                    count+=length;if(count>MAX_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"The document exceeds 100 MiB.");
                    output.write(buffer,0,length);
                }
            }
            int sheetCount;
            // Validate parseable content before reserving any database record or persistent object.
            validated=parser.open(spool,format);
            sheetCount=validated.sheets().size();
            if(sheetCount==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The document has no readable worksheet.");
            var reservation=repository.reserve(tenant,user,filename,base,format.name());
            try {
                ParsedSpreadsheet document=validated;
                document.handoff();
                uploads.execute(()->process(spool,format,base,reservation,tenant,user,document));
                dispatched=true;
                return new UploadReceipt(reservation.upload(),reservation.file().fileId(),sheetCount,List.of());
            } catch(RejectedExecutionException rejected) {
                repository.removeAttempt(tenant,user,reservation.file().fileId());
                statuses.fail(reservation.upload().getBookUploadId(),rejected);
                return new UploadReceipt(statuses.get(reservation.upload().getBookUploadId()),reservation.file().fileId(),sheetCount,List.of());
            }
        } finally {
            if(!dispatched)try {if(validated!=null)validated.close();}finally {Files.deleteIfExists(spool);}
        }
    }

    private void process(Path source,SpreadsheetFormat format,String base,SpreadsheetRepository.Reservation reservation,long tenant,long user,ParsedSpreadsheet validated) {
        long file=reservation.file().fileId(),upload=reservation.upload().getBookUploadId();
        var objects=new LinkedHashSet<String>();
        var pending=new ArrayDeque<BatchTask>();
        var counts=new LinkedHashMap<Long,Long>();
        var expected=new LinkedHashMap<Long,Long>();
        boolean interrupted=false;
        try(var scope=TenantContext.use(tenant,user,false)) {
            try {
                try(ParsedSpreadsheet document=validated) {
                    statuses.processing(upload);
                    Path original=storage.writeTracked(tenant,user,format.name(),base,reservation.file().fileName(),source,locator->{
                        repository.original(tenant,user,file,locator.toString(),document.sheets().size());
                        objects.add(locator.toString());
                    });
                    for(SheetDescriptor descriptor:document.sheets()) {
                        SheetRecord sheet=repository.createSheet(tenant,user,file,descriptor.sheetIndex(),descriptor.sheetName(),descriptor.columnCount(),descriptor.header());
                        counts.put(sheet.sheetId(),0L);expected.put(sheet.sheetId(),descriptor.dataRowCount());
                        String artifact;
                        if(format==SpreadsheetFormat.CSV)artifact=original.toString();
                        else {
                            Path temporary=Files.createTempFile("spreadsheet-sheet-",".xlsx");
                            try {
                                SpreadsheetFormat extracted=document.extractSheet(descriptor.sheetIndex(),temporary);
                                if(extracted!=SpreadsheetFormat.XLSX)throw new IOException("Excel sheet artifacts must use XLSX format.");
                                Path stored=storage.writeTracked(tenant,user,extracted.name(),base,base+"_"+descriptor.sheetName()+"."+extracted.extension(),temporary,locator->{
                                    repository.sheetArtifact(tenant,user,file,sheet.sheetId(),locator.toString());
                                    objects.add(locator.toString());
                                });
                                artifact=stored.toString();
                            } finally {Files.deleteIfExists(temporary);}
                        }
                        if(format==SpreadsheetFormat.CSV)repository.sheetArtifact(tenant,user,file,sheet.sheetId(),artifact);
                        document.forEachBatch(descriptor.sheetIndex(),batch->{
                            if(pending.size()>=MAX_PENDING_BATCHES){acknowledge(pending.getFirst(),counts);pending.removeFirst();}
                            BatchTask task=new BatchTask(sheet.sheetId(),()->worker.persist(tenant,user,file,sheet.sheetId(),batch));
                            batches.execute(task);pending.addLast(task);
                        });
                    }
                }
                while(!pending.isEmpty()){acknowledge(pending.getFirst(),counts);pending.removeFirst();}
                if(!counts.equals(expected))throw new IllegalStateException("Persisted worksheet counts do not match the source.");
                repository.complete(tenant,user,file,counts);
                log.info("Spreadsheet upload completed uploadId={} fileId={} sheets={}",upload,file,counts.size());
            } catch(Throwable failure) {
                // Cancel only queued work. A running worker's future is never cancelled early.
                for(BatchTask task:pending)task.cancelQueued();
                interrupted=Thread.interrupted();
                while(!pending.isEmpty()) {
                    BatchTask task=pending.removeFirst();
                    boolean done=false;
                    while(!done)try {task.result.get();done=true;}
                    catch(InterruptedException ignored){interrupted=true;}
                    catch(ExecutionException|CancellationException ignored){done=true;}
                }
                try {repository.discardAttemptRows(tenant,user,file);}
                catch(RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                    log.error("Spreadsheet imported-row cleanup failed uploadId={} fileId={}; retained metadata supports retry",upload,file,cleanup);
                }
                boolean cleaned=true;
                for(String object:objects)try {storage.delete(tenant,object);}
                catch(IOException|RuntimeException cleanup) {
                    cleaned=false;failure.addSuppressed(cleanup);
                    log.error("Spreadsheet object cleanup failed uploadId={} fileId={} locator={}",upload,file,object,cleanup);
                }
                if(cleaned)try {repository.removeAttempt(tenant,user,file);}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
                try {statuses.fail(upload,failure);}catch(RuntimeException recording){failure.addSuppressed(recording);}
                log.error("Spreadsheet upload failed uploadId={} fileId={}; queued work stopped and running batches quiesced",upload,file,failure);
            }
        } finally {
            try {Files.deleteIfExists(source);}catch(IOException failure){log.warn("Spreadsheet temporary input cleanup failed uploadId={} fileId={}",upload,file,failure);}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    private static void acknowledge(BatchTask task,Map<Long,Long> counts) {
        try {counts.merge(task.sheet,task.result.get().longValue(),Long::sum);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Spreadsheet processing was interrupted.",failure);}
        catch(ExecutionException failure){throw new CompletionException(failure.getCause());}
    }
    /** CAS ensures queued cancellation and actual worker entry cannot race past cleanup. */
    private static final class BatchTask implements Runnable {
        final long sheet;final Callable<Integer> work;final AtomicInteger state=new AtomicInteger();
        final CompletableFuture<Integer> result=new CompletableFuture<>();
        BatchTask(long sheet,Callable<Integer> work){this.sheet=sheet;this.work=work;}
        @Override public void run(){if(!state.compareAndSet(0,1))return;try{result.complete(work.call());}catch(Throwable failure){result.completeExceptionally(failure);}finally{state.set(3);}}
        void cancelQueued(){if(state.compareAndSet(0,2))result.cancel(false);}
    }
}
