package com.book.spreadsheet.parse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class SpreadsheetParserTests {
    @TempDir Path temporary;
    private final SpreadsheetParser parser=new SpreadsheetParser();

    @ParameterizedTest
    @CsvSource({"0,0", "1,1", "499,1", "500,1", "501,2", "1200,3", "10001,21"})
    void fixedFiveHundredDataRowBatchesKeepRemainderAndImmutableSourcePositions(int rows,int batches) throws Exception {
        Path source=temporary.resolve("data.csv"); var csv=new StringBuilder("ID,Name\n");
        for(int index=0;index<rows;index++) csv.append(String.format(java.util.Locale.ROOT,"%05d",index)).append(",Name ").append(index).append('\n');
        Files.writeString(source,csv,StandardCharsets.UTF_8);
        try(var input=parser.open(source,SpreadsheetFormat.CSV)) {
            assertThat(input.sheets().getFirst().dataRowCount()).isEqualTo(rows);
            var result=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,result::add);
            assertThat(result).hasSize(batches);
            assertThat(result.stream().mapToInt(batch->batch.rows().size()).sum()).isEqualTo(rows);
            if(rows>0) {
                assertThat(result.getFirst().rows().getFirst()).containsEntry("column_1","00000");
                assertThat(result.getLast().rowNumbers().getLast()).isEqualTo(rows+1L);
                assertThatThrownBy(()->result.getFirst().rows().getFirst().put("column_1","changed")).isInstanceOf(UnsupportedOperationException.class);
                assertThat(result.stream().limit(Math.max(0,batches-1)).allMatch(batch->batch.rows().size()==500)).isTrue();
                assertThat(result.getLast().rows()).hasSize(rows%500==0?500:rows%500);
            }
        }
    }

    @Test void csvBomQuotesBlankDuplicateHeadersMultilineAndWiderDataRetainLogicalSourceRows() throws Exception {
        Path source=temporary.resolve("quoted.csv");
        Files.writeString(source,"\uFEFFName,Name,\r\n0012,\"Deepak, Kumar\",\"first\nsecond\",extra\r\n,,,\r\n0013,Ravi\r\n",StandardCharsets.UTF_8);
        try(var input=parser.open(source,SpreadsheetFormat.CSV)) {
            var sheet=input.sheets().getFirst(); assertThat(sheet.sheetName()).isEqualTo("Sheet1");
            assertThat(sheet.columnCount()).isEqualTo(4); assertThat(sheet.dataRowCount()).isEqualTo(2);
            assertThat(sheet.header().entrySet()).containsExactly(Map.entry("column_1","Name"),Map.entry("column_2","Name"),Map.entry("column_3",""),Map.entry("column_4",""));
            var batches=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,batches::add);
            assertThat(batches.getFirst().rowNumbers()).containsExactly(2L,4L);
            assertThat(batches.getFirst().rows().getFirst()).containsEntry("column_1","0012").containsEntry("column_2","Deepak, Kumar").containsEntry("column_3","first\nsecond").containsEntry("column_4","extra");
            assertThat(batches.getFirst().rows().getLast()).containsEntry("column_3","").containsEntry("column_4","");
        }
    }

    @Test void hiddenHeaderOnlyAndEmptyExcelSheetsRemainInOrderAndEmptySheetHasAValidArtifact() throws Exception {
        Path source=temporary.resolve("sheets.xlsx");
        try(var workbook=new XSSFWorkbook()) {
            var first=workbook.createSheet("Employees"); var header=first.createRow(0);
            header.createCell(0).setCellValue("Employee ID"); header.createCell(1).setCellValue("Employee ID");
            var data=first.createRow(1); data.createCell(0).setCellValue("0012"); data.createCell(3).setCellValue("extra");
            first.createRow(2); first.createRow(4).createCell(0).setCellValue("0013");
            workbook.createSheet("Header only").createRow(0).createCell(0).setCellValue("Header"); workbook.createSheet("Empty"); workbook.setSheetHidden(1,true); save(workbook,source);
        }
        try(var input=parser.open(source,SpreadsheetFormat.XLSX)) {
            assertThat(input.sheets()).extracting(SheetDescriptor::sheetName).containsExactly("Employees","Header only","Empty");
            assertThat(input.sheets()).extracting(SheetDescriptor::dataRowCount).containsExactly(2L,0L,0L);
            assertThat(input.sheets().getFirst().header()).containsEntry("column_4",""); assertThat(input.sheets().getLast().header()).isEmpty();
            assertThat(input.sheets().getLast().columnCount()).isZero();
            var rows=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,rows::add); assertThat(rows.getFirst().rowNumbers()).containsExactly(2L,5L);
            input.forEachBatch(2,ignored->fail("Header-only sheet must not submit a task.")); input.forEachBatch(3,ignored->fail("Empty sheet must not submit a task."));
            Path empty=temporary.resolve("Empty.xlsx"); assertThat(input.extractSheet(3,empty)).isEqualTo(SpreadsheetFormat.XLSX);
            try(var artifact=WorkbookFactory.create(empty.toFile())) { assertThat(artifact.getNumberOfSheets()).isEqualTo(1); assertThat(artifact.getSheetAt(0).getPhysicalNumberOfRows()).isZero(); }
        }
    }

    @Test void cachedExcelTextAndStandaloneFormulaPolicyDoNotEvaluateExternalReferences() throws Exception {
        Path source=temporary.resolve("formulas.xlsx");
        try(var workbook=new XSSFWorkbook()) {
            var first=workbook.createSheet("Data"); var other=workbook.createSheet("Other"); first.createRow(0).createCell(0).setCellValue("Values"); other.createRow(0).createCell(0).setCellValue(12);
            var row=first.createRow(1); var id=row.createCell(0); id.setCellValue(12); var idStyle=workbook.createCellStyle(); idStyle.setDataFormat(workbook.createDataFormat().getFormat("00000")); id.setCellStyle(idStyle);
            row.createCell(1).setCellValue(true); var date=row.createCell(2); date.setCellValue(LocalDate.of(2026,11,26)); var dateStyle=workbook.createCellStyle(); dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd")); date.setCellStyle(dateStyle);
            row.createCell(3).setCellFormula("A2*2"); row.getCell(3).setCellValue(24); row.createCell(4).setCellFormula("Other!A1"); row.getCell(4).setCellValue(12);
            var rate=workbook.createName(); rate.setNameName("Rate"); rate.setRefersToFormula("Other!$A$1"); row.createCell(5).setCellFormula("Rate*2"); row.getCell(5).setCellValue(24); save(workbook,source);
        }
        try(var input=parser.open(source,SpreadsheetFormat.XLSX)) {
            var rows=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,rows::add);
            assertThat(rows.getFirst().rows().getFirst()).containsEntry("column_1","00012").containsEntry("column_2","TRUE").containsEntry("column_3","2026-11-26").containsEntry("column_4","24").containsEntry("column_5","12").containsEntry("column_6","24");
            Path output=temporary.resolve("Data.xlsx"); input.extractSheet(1,output);
            try(var workbook=WorkbookFactory.create(output.toFile())) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1); var row=workbook.getSheetAt(0).getRow(1);
                assertThat(row.getCell(3).getCellType()).isEqualTo(CellType.FORMULA); assertThat(row.getCell(3).getCellFormula()).isEqualTo("A2*2"); assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(24);
                assertThat(row.getCell(4).getCellType()).isEqualTo(CellType.NUMERIC); assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(12);
                assertThat(row.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC); assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(24);
            }
        }
    }

    @Test void xlsNormalizesToXlsxPreservingCellTypesStylesWidthsAndMergedRegions() throws Exception {
        Path source=temporary.resolve("legacy.xls");
        try(var workbook=new HSSFWorkbook()) {
            var sheet=workbook.createSheet("Legacy"); sheet.createRow(0).createCell(0).setCellValue("Title"); sheet.addMergedRegion(new CellRangeAddress(0,0,0,1)); sheet.setColumnWidth(0,8000);
            var row=sheet.createRow(1); row.setHeightInPoints(28); var cell=row.createCell(0); cell.setCellValue(12); var style=workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("00000"));
            style.setFillForegroundColor(IndexedColors.YELLOW.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setBorderBottom(BorderStyle.THIN); var font=workbook.createFont(); font.setBold(true); font.setFontName("Arial"); style.setFont(font); cell.setCellStyle(style); save(workbook,source);
        }
        try(var input=parser.open(source,SpreadsheetFormat.XLS)) {
            var rows=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,rows::add); assertThat(rows.getFirst().rows().getFirst()).containsEntry("column_1","00012");
            Path output=temporary.resolve("Legacy.xlsx"); assertThat(input.extractSheet(1,output)).isEqualTo(SpreadsheetFormat.XLSX);
            try(var workbook=WorkbookFactory.create(output.toFile())) {
                assertThat(workbook).isInstanceOf(XSSFWorkbook.class); var sheet=workbook.getSheetAt(0); var cell=sheet.getRow(1).getCell(0);
                assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC); assertThat(cell.getNumericCellValue()).isEqualTo(12); assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("00000");
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndex()).getBold()).isTrue(); assertThat(cell.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND); assertThat(cell.getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
                assertThat(sheet.getColumnWidth(0)).isEqualTo(8000); assertThat(sheet.getMergedRegions()).containsExactly(new CellRangeAddress(0,0,0,1)); assertThat(sheet.getRow(1).getHeightInPoints()).isEqualTo(28);
            }
        }
    }

    @Test void emptyMalformedUtf8AndWrongWorkbookFormatAreRejectedBeforeArtifacts() throws Exception {
        Path empty=temporary.resolve("empty.csv"); Files.writeString(empty,""); Path malformed=temporary.resolve("malformed.csv"); Files.writeString(malformed,"header\n\"unterminated");
        Path utf8=temporary.resolve("invalid.csv"); Files.write(utf8,new byte[]{(byte)0xff,10}); Path binary=temporary.resolve("binary.csv"); Files.write(binary,new byte[]{65,0,66}); Path wrong=temporary.resolve("wrong.xls"); try(var workbook=new XSSFWorkbook()) { workbook.createSheet("One"); save(workbook,wrong); }
        for(Path source:List.of(empty,malformed,utf8,binary)) assertThatThrownBy(()->parser.open(source,SpreadsheetFormat.CSV)).isInstanceOfAny(ResponseStatusException.class,java.io.IOException.class);
        assertThatThrownBy(()->parser.open(wrong,SpreadsheetFormat.XLS)).isInstanceOf(ResponseStatusException.class); assertThatThrownBy(()->SpreadsheetFormat.fromFilename("unsupported.zip")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void missingFormulaCacheIsRejectedInsteadOfInventingOrFetchingAResult() throws Exception {
        Path source=temporary.resolve("uncalculated.xlsx");
        try(var workbook=new XSSFWorkbook()) { var cell=workbook.createSheet("Data").createRow(0).createCell(0); cell.setCellFormula("1+1"); if(cell.getCTCell().isSetV()) cell.getCTCell().unsetV(); save(workbook,source); }
        assertThatThrownBy(()->parser.open(source,SpreadsheetFormat.XLSX)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("no saved formula result");
    }
    @Test void standaloneExtractionPreservesThe1904DateSystemInsteadOfShiftingDatesFourYears() throws Exception {
        Path source=temporary.resolve("date1904.xlsx");
        try(var workbook=new XSSFWorkbook()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var sheet=workbook.createSheet("Dates"); sheet.createRow(0).createCell(0).setCellValue("Date");
            var date=sheet.createRow(1).createCell(0); date.setCellValue(LocalDate.of(2026,11,26));
            var style=workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd")); date.setCellStyle(style); save(workbook,source);
        }
        try(var input=parser.open(source,SpreadsheetFormat.XLSX)) {
            var batches=new ArrayList<SpreadsheetRowBatch>(); input.forEachBatch(1,batches::add);
            assertThat(batches.getFirst().rows().getFirst()).containsEntry("column_1","2026-11-26");
            Path output=temporary.resolve("Dates.xlsx"); input.extractSheet(1,output);
            try(var workbook=new XSSFWorkbook(output.toFile())) {
                assertThat(workbook.isDate1904()).isTrue();
                assertThat(new DataFormatter(java.util.Locale.US).formatCellValue(workbook.getSheetAt(0).getRow(1).getCell(0))).isEqualTo("2026-11-26");
            }
        }
    }
    private static void save(Workbook workbook,Path output) throws Exception { try(var stream=Files.newOutputStream(output)) { workbook.write(stream); } }
}
