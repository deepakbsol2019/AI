package com.book.spreadsheet.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached cell maps and parallel immutable source positions; no POI/parser/request objects cross threads. */
public record SpreadsheetRowBatch(List<Map<String,String>> rows, List<Long> rowNumbers) {
    public SpreadsheetRowBatch {
        if(rows==null || rowNumbers==null || rows.size()!=rowNumbers.size() || rows.isEmpty() || rows.size()>500)
            throw new IllegalArgumentException("A spreadsheet batch contains 1 to 500 aligned data rows.");
        var copy=new ArrayList<Map<String,String>>(rows.size());
        for(var row:rows) {
            if(row==null || row.entrySet().stream().anyMatch(e->e.getKey()==null || e.getValue()==null))
                throw new IllegalArgumentException("Spreadsheet cell maps contain only non-null String keys and values.");
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        rows=List.copyOf(copy); rowNumbers=List.copyOf(rowNumbers);
        if(rowNumbers.stream().anyMatch(number->number==null || number<2))
            throw new IllegalArgumentException("Data source rows start at 2.");
    }
}
