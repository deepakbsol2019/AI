package com.book.spreadsheet.upload;

import com.book.spreadsheet.parse.SpreadsheetRowBatch;
import com.book.spreadsheet.persistence.SpreadsheetRepository;
import com.book.user.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxied bean: no request transaction, parser, stream or EntityManager crosses threads. */
@Service
public class SpreadsheetBatchWorker {
    private static final Logger log=LoggerFactory.getLogger(SpreadsheetBatchWorker.class);
    private final SpreadsheetRepository repository;
    public SpreadsheetBatchWorker(SpreadsheetRepository repository) { this.repository=repository; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int persist(long tenant,long user,long file,long sheet,SpreadsheetRowBatch batch) {
        try (var ignored=TenantContext.use(tenant,user,false)) {
            try {
                int inserted=repository.insertRows(tenant,user,file,sheet,batch.rows(),batch.rowNumbers());
                log.debug("Spreadsheet batch persisted fileId={} sheetId={} firstSourceRow={} lastSourceRow={} rows={}",file,sheet,batch.rowNumbers().getFirst(),batch.rowNumbers().getLast(),inserted);
                return inserted;
            } catch(RuntimeException failure) {
                log.error("Spreadsheet batch failed tenantId={} fileId={} sheetId={} firstSourceRow={} lastSourceRow={} rows={}",tenant,file,sheet,batch.rowNumbers().getFirst(),batch.rowNumbers().getLast(),batch.rows().size(),failure);
                throw failure;
            }
        }
    }
}
