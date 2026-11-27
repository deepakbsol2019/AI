package com.book.spreadsheet.parse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SheetDescriptor(int sheetIndex, String sheetName, int columnCount,
        Map<String,String> header, long dataRowCount) {
    public SheetDescriptor {
        header=Collections.unmodifiableMap(new LinkedHashMap<>(header));
    }
}
