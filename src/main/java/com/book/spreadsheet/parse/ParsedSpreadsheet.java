package com.book.spreadsheet.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** One thread owns the reader. Batches are detached before invoking downstream persistence. */
public interface ParsedSpreadsheet extends AutoCloseable {
    List<SheetDescriptor> sheets();
    /** Relinquish this reader after validation, before dispatch to its single background owner. */
    void handoff();
    void forEachBatch(int sheetIndex, Consumer<SpreadsheetRowBatch> consumer) throws IOException;
    SpreadsheetFormat extractSheet(int sheetIndex, Path output) throws IOException;
    @Override void close() throws IOException;
}
