package com.book.spreadsheet.parse;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A file-backed, single-owner reader; only detached buffers of at most 500 rows cross to workers.
 * Validation reads every source record once and spills sparse positional values to temporary row files.
 * Reading those validated buffers for persistence does not reparse the source document.
 * Original uploads stay on disk. Excel standalone outputs use an SXSSF 100-row window,
 * while POI's input workbook model is bounded by upload/expanded ZIP limits.
 */
@Service
public class SpreadsheetParser {
    public static final int DATA_BATCH_SIZE=500;
    private static final long MAX_UPLOAD_BYTES=100L*1024*1024;
    private static final long MAX_EXPANDED_BYTES=256L*1024*1024;
    private static final long MAX_ENTRY_BYTES=64L*1024*1024;
    private static final int MAX_COLUMNS=16_384;
    private static final int MAX_SHEETS=1_024;

    public ParsedSpreadsheet open(Path source, SpreadsheetFormat format) throws IOException {
        if(source==null || format==null || !Files.isRegularFile(source) || Files.size(source)==0
                || Files.size(source)>MAX_UPLOAD_BYTES) throw invalid("Upload a readable, non-empty spreadsheet of at most 100 MiB.");
        try {
            if(format==SpreadsheetFormat.CSV) return new CsvReader(source);
            if(format==SpreadsheetFormat.XLSX) validateZip(source);
            Workbook workbook=WorkbookFactory.create(source.toFile(),null,true);
            boolean expected=format==SpreadsheetFormat.XLS ? workbook instanceof HSSFWorkbook : workbook instanceof XSSFWorkbook;
            if(!expected) { workbook.close(); throw invalid("The spreadsheet contents do not match the selected filename extension."); }
            try { return new ExcelReader(workbook); }
            catch(Throwable failure) { workbook.close(); throw failure; }
        } catch(ResponseStatusException failure) { throw failure; }
        catch(RuntimeException | IOException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The spreadsheet is malformed, encrypted, unsupported or unreadable.",failure);
        }
    }

    private static void validateZip(Path source) throws IOException {
        long total=0; int count=0; var names=new HashSet<String>();
        try(var zip=new ZipFile(source.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();
                if(++count>10_000 || !names.add(entry.getName()) || entry.getSize()<0
                        || entry.getSize()>MAX_ENTRY_BYTES || (total+=entry.getSize())>MAX_EXPANDED_BYTES)
                    throw invalid("The workbook exceeds the supported expanded size or ZIP-entry limits.");
            }
        }
        // Retain POI's compression-ratio protection and also bound individual expanded parts.
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
    }

    private abstract static class OwnedReader implements ParsedSpreadsheet {
        private Thread owner=Thread.currentThread();
        private boolean closed;
        private final Map<Integer,Path> rowSpools=new LinkedHashMap<>();
        List<SheetDescriptor> descriptors=List.of();
        protected synchronized void check() {
            if(owner==null) owner=Thread.currentThread();
            if(closed || Thread.currentThread()!=owner) throw new IllegalStateException("A spreadsheet reader is used only by its owning coordinator thread.");
        }
        @Override public synchronized void handoff() { check(); owner=null; }
        @Override public List<SheetDescriptor> sheets() { check(); return descriptors; }
        protected SheetDescriptor descriptor(int index) {
            check(); return descriptors.stream().filter(sheet->sheet.sheetIndex()==index).findFirst()
                    .orElseThrow(()->invalid("Choose a worksheet belonging to this workbook."));
        }
        protected DataOutputStream rows(int index) throws IOException {
            Path spool=Files.createTempFile("spreadsheet-rows-",".bin");rowSpools.put(index,spool);
            return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)));
        }
        @Override public void forEachBatch(int index,Consumer<SpreadsheetRowBatch> consumer) throws IOException {
            var sheet=descriptor(index);var batch=new BatchSink(consumer);
            try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(rowSpools.get(index))))) {
                for(long row=0;row<sheet.dataRowCount();row++) {
                    long number=input.readLong();var values=new LinkedHashMap<String,String>();
                    for(int column=0;column<sheet.columnCount();column++)values.put("column_"+(column+1),null);
                    int populated=input.readInt();
                    for(int cell=0;cell<populated;cell++) {
                        int column=input.readInt(),length=input.readInt();
                        byte[] bytes=input.readNBytes(length);
                        if(bytes.length!=length)throw new java.io.EOFException("The temporary row buffer is incomplete.");
                        values.put("column_"+(column+1),new String(bytes,StandardCharsets.UTF_8));
                    }
                    batch.add(values,number);
                }
            }
            batch.finish();
        }
        protected void closing() throws IOException { check(); closed=true; cleanupSpools(); }
        protected void cleanupSpools() throws IOException {
            IOException failure=null;
            for(Path spool:rowSpools.values())try {Files.deleteIfExists(spool);}
            catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            if(failure!=null)throw failure;
        }
    }

    private static final class CsvReader extends OwnedReader {
        private final Path source;
        CsvReader(Path source) throws IOException {
            this.source=source;
            List<String> header=List.of(); int width=0; long retained=0;
            try(var csv=csv(source);var output=rows(1)) {
                for(CSVRecord record:csv) {
                    var values=record.toList();
                    validateCsvText(values);
                    if(record.getRecordNumber()==1) {header=new ArrayList<>(values);width=header.size();validateHeader(header,"CSV");}
                    else {
                        if(record.size()>width)throw invalid("CSV row "+record.getRecordNumber()+": expected at most "+width+" columns but found "+record.size()+" parsed fields.");
                        writeRow(output,values,width,record.getRecordNumber());retained++;
                    }
                }
                if(header.isEmpty())throw invalid("The CSV document is empty or its first row has no valid headers.");
            } catch(Throwable failure) {try {cleanupSpools();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            descriptors=List.of(new SheetDescriptor(1,"Sheet1",width,positions(header,width),retained));
        }
        @Override public SpreadsheetFormat extractSheet(int index,Path output) throws IOException {
            descriptor(index); Files.copy(source,output,StandardCopyOption.REPLACE_EXISTING); return SpreadsheetFormat.CSV;
        }
        @Override public void close() throws IOException { closing(); }
    }

    private static CSVParser csv(Path source) throws IOException {
        var reader=csvTextReader(source);
        try { return CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(reader); }
        catch(Throwable failure) { reader.close(); throw failure; }
    }

    private static PushbackReader csvTextReader(Path source) throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader=new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(source),decoder)),1);
        try {
            int first=reader.read(); if(first!=-1 && first!='\uFEFF') reader.unread(first);
            return reader;
        } catch(Throwable failure) { reader.close(); throw failure; }
    }

    private static final class ExcelReader extends OwnedReader {
        private final Workbook workbook;
        private final DataFormatter formatter=new DataFormatter(Locale.US,true);
        ExcelReader(Workbook workbook) throws IOException {
            this.workbook=workbook; formatter.setUseCachedValuesForFormulaCells(true);
            int count=workbook.getNumberOfSheets();
            if(count<1 || count>MAX_SHEETS) throw invalid("The workbook must contain 1 to 1024 worksheets.");
            var result=new ArrayList<SheetDescriptor>(count);
            try {
                for(int index=0;index<count;index++) {
                    Sheet sheet=workbook.getSheetAt(index);int width=0;long retained=0;Row first=sheet.getRow(0);
                    if(first!=null)for(Cell cell:first)if(hasValue(cell))width=Math.max(width,cell.getColumnIndex()+1);
                    var header=new ArrayList<String>(width);
                    for(int column=0;column<width;column++)header.add(text(first.getCell(column)));
                    validateHeader(header,"Worksheet '"+sheet.getSheetName()+"'");
                    try(var output=rows(index+1)) {
                        for(int rowIndex=1;rowIndex<=sheet.getLastRowNum();rowIndex++) {
                            Row row=sheet.getRow(rowIndex);
                            // Test actual column indexes before formatting or inspecting formula caches.
                            if(row!=null)for(Cell cell:row)if(cell.getColumnIndex()>=width && hasValue(cell))
                                throw invalid("Worksheet '"+sheet.getSheetName()+"' row "+(row.getRowNum()+1)+": expected at most "+width+" columns but actual data reaches column "+(cell.getColumnIndex()+1)+".");
                            var values=new ArrayList<String>(width);
                            for(int column=0;column<width;column++)values.add(text(row==null?null:row.getCell(column)));
                            writeRow(output,values,width,(long)rowIndex+1);retained++;
                        }
                    }
                    result.add(new SheetDescriptor(index+1,sheet.getSheetName(),width,positions(header,width),retained));
                }
            }catch(Throwable failure){try{cleanupSpools();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
            descriptors=List.copyOf(result);
        }
        private String text(Cell cell) {
            if(cell==null) return "";
            requireCachedFormula(cell);
            try { return formatter.formatCellValue(cell); }
            catch(RuntimeException failure) { throw invalid("A cell in worksheet '"+cell.getSheet().getSheetName()+"' at "+cell.getAddress()+" cannot be converted using its saved result/format."); }
        }
        @Override public SpreadsheetFormat extractSheet(int index,Path output) throws IOException {
            descriptor(index);
            Sheet source=workbook.getSheetAt(index-1);
            try(var target=new SXSSFWorkbook(100)) {
                target.setCompressTempFiles(true);
                boolean date1904=workbook instanceof XSSFWorkbook xssf ? xssf.isDate1904()
                        : ((HSSFWorkbook)workbook).getInternalWorkbook().isUsing1904DateWindowing();
                var root=target.getXSSFWorkbook().getCTWorkbook();
                (root.isSetWorkbookPr()?root.getWorkbookPr():root.addNewWorkbookPr()).setDate1904(date1904);
                Sheet sheet=target.createSheet(source.getSheetName());
                copySheet(workbook,source,target,sheet);
                try(var stream=Files.newOutputStream(output)) { target.write(stream); }
            }
            return SpreadsheetFormat.XLSX;
        }
        @Override public void close() throws IOException { try {closing();} finally {workbook.close();} }
    }

    private static void requireCachedFormula(Cell cell) {
        if(cell.getCellType()==CellType.FORMULA && cell instanceof XSSFCell xssf && !xssf.getCTCell().isSetV())
            throw invalid("Worksheet '"+cell.getSheet().getSheetName()+"' cell "+cell.getAddress()+" has no saved formula result. Calculate and save the workbook before uploading.");
    }

    private static void copySheet(Workbook sourceBook,Sheet source,Workbook targetBook,Sheet target) {
        var styles=new HashMap<Integer,CellStyle>();
        int width=0;
        for(Row row:source) {
            Row copy=target.createRow(row.getRowNum()); copy.setHeight(row.getHeight()); copy.setZeroHeight(row.getZeroHeight());
            if(row.getRowStyle()!=null) copy.setRowStyle(style(sourceBook,row.getRowStyle(),targetBook,styles));
            width=Math.max(width,Math.max(0,row.getLastCellNum()));
            for(Cell cell:row) {
                Cell out=copy.createCell(cell.getColumnIndex()); out.setCellStyle(style(sourceBook,cell.getCellStyle(),targetBook,styles));
                boolean formula=cell.getCellType()==CellType.FORMULA;
                if(formula && safeLocalFormula(cell.getCellFormula(),sourceBook)) {
                    try { out.setCellFormula(cell.getCellFormula()); }
                    catch(RuntimeException unsupported) { /* Saved result below is the explicit standalone fallback. */ }
                }
                copyValue(cell,out,formula?cell.getCachedFormulaResultType():cell.getCellType());
            }
        }
        for(int column=0;column<width;column++) {
            target.setColumnWidth(column,source.getColumnWidth(column)); target.setColumnHidden(column,source.isColumnHidden(column));
        }
        for(CellRangeAddress merged:source.getMergedRegions()) target.addMergedRegion(merged.copy());
        target.setDefaultColumnWidth(source.getDefaultColumnWidth()); target.setDefaultRowHeight(source.getDefaultRowHeight());
        target.setDisplayGridlines(source.isDisplayGridlines()); target.setPrintGridlines(source.isPrintGridlines());
        target.getPrintSetup().setLandscape(source.getPrintSetup().getLandscape());
        target.getPrintSetup().setPaperSize(source.getPrintSetup().getPaperSize());
        var pane=source.getPaneInformation();
        if(pane!=null && pane.isFreezePane()) target.createFreezePane(pane.getVerticalSplitPosition(),pane.getHorizontalSplitPosition());
        // Independently usable artifacts are visible even when the source worksheet was hidden.
        // Whole-file delivery retains the exact original visibility, drawings, macros and metadata.
    }

    private static boolean safeLocalFormula(String formula,Workbook source) {
        if(formula.indexOf('!')>=0 || formula.indexOf('[')>=0) return false;
        String lower=formula.toLowerCase(Locale.ROOT);
        for(var name:source.getAllNames()) {
            String key=name.getNameName().toLowerCase(Locale.ROOT);
            if(lower.matches("(?s).*?(?<![a-z0-9_.])"+java.util.regex.Pattern.quote(key)+"(?![a-z0-9_.]).*")) return false;
        }
        return true;
    }

    private static void copyValue(Cell source,Cell target,CellType type) {
        switch(type) {
            case STRING -> target.setCellValue(source.getStringCellValue());
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case ERROR -> target.setCellErrorValue(source.getErrorCellValue());
            case BLANK, _NONE -> target.setBlank();
            case FORMULA -> throw invalid("A formula result cannot itself be a formula.");
        }
    }

    private static CellStyle style(Workbook sourceBook,CellStyle source,Workbook targetBook,Map<Integer,CellStyle> cache) {
        return cache.computeIfAbsent((int)source.getIndex(),ignored->{
            CellStyle target=targetBook.createCellStyle();
            if(source instanceof XSSFCellStyle) { target.cloneStyleFrom(source); return target; }
            target.setAlignment(source.getAlignment()); target.setVerticalAlignment(source.getVerticalAlignment());
            target.setBorderBottom(source.getBorderBottom()); target.setBorderTop(source.getBorderTop());
            target.setBorderLeft(source.getBorderLeft()); target.setBorderRight(source.getBorderRight());
            target.setWrapText(source.getWrapText()); target.setHidden(source.getHidden()); target.setLocked(source.getLocked());
            target.setRotation(source.getRotation()); target.setIndention(source.getIndention()); target.setShrinkToFit(source.getShrinkToFit());
            target.setFillPattern(source.getFillPattern());
            target.setDataFormat(targetBook.createDataFormat().getFormat(source.getDataFormatString()));
            var xssf=(XSSFCellStyle)target; var palette=((HSSFWorkbook)sourceBook).getCustomPalette();
            xssf.setFillForegroundColor(color(palette,source.getFillForegroundColor())); xssf.setFillBackgroundColor(color(palette,source.getFillBackgroundColor()));
            xssf.setBottomBorderColor(color(palette,source.getBottomBorderColor())); xssf.setTopBorderColor(color(palette,source.getTopBorderColor()));
            xssf.setLeftBorderColor(color(palette,source.getLeftBorderColor())); xssf.setRightBorderColor(color(palette,source.getRightBorderColor()));
            Font from=sourceBook.getFontAt(source.getFontIndex()); XSSFFont to=(XSSFFont)targetBook.createFont();
            to.setFontName(from.getFontName()); to.setFontHeight(from.getFontHeight()); to.setBold(from.getBold()); to.setItalic(from.getItalic());
            to.setStrikeout(from.getStrikeout()); to.setUnderline(from.getUnderline()); to.setTypeOffset(from.getTypeOffset()); to.setCharSet(from.getCharSet());
            to.setColor(color(palette,from.getColor())); target.setFont(to); return target;
        });
    }

    private static XSSFColor color(HSSFPalette palette,short index) {
        var color=palette.getColor(index);
        if(color==null) return new XSSFColor(new byte[]{0,0,0},new DefaultIndexedColorMap());
        short[] rgb=color.getTriplet(); return new XSSFColor(new byte[]{(byte)rgb[0],(byte)rgb[1],(byte)rgb[2]},new DefaultIndexedColorMap());
    }

    private static LinkedHashMap<String,String> positions(List<String> values,int width) {
        var result=new LinkedHashMap<String,String>();
        for(int column=0;column<width;column++) result.put("column_"+(column+1),column<values.size()&&!values.get(column).isEmpty()?values.get(column):null);
        return result;
    }
    private static boolean hasValue(Cell cell) {
        return switch(cell.getCellType()) {
            case BLANK,_NONE -> false;
            case STRING -> !cell.getStringCellValue().isEmpty();
            default -> true; // Formulas are data, even with an empty saved result.
        };
    }
    private static void validateHeader(List<String> header,String source) {
        checkWidth(header.size());
        if(header.isEmpty())throw invalid(source+" row 1: missing header. Supply a non-empty label for each column.");
        for(int column=0;column<header.size();column++)if(header.get(column).isBlank())
            throw invalid(source+" row 1: invalid blank header at column "+(column+1)+". Supply a non-empty label for each column.");
    }
    private static void writeRow(DataOutputStream output,List<String> values,int width,long row) throws IOException {
        output.writeLong(row);
        int populated=0;
        for(int column=0;column<Math.min(width,values.size());column++)if(values.get(column)!=null&&!values.get(column).isEmpty())populated++;
        output.writeInt(populated);
        for(int column=0;column<Math.min(width,values.size());column++) {
            String value=values.get(column);
            if(value!=null && !value.isEmpty()) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);output.writeInt(column);output.writeInt(bytes.length);output.write(bytes);
            }
        }
    }
    private static void checkWidth(int width) { if(width>MAX_COLUMNS) throw invalid("A worksheet supports at most 16384 positional columns."); }
    private static void validateCsvText(List<String> values) {
        if(values.stream().anyMatch(value->value.chars().anyMatch(character->character<32 && character!='\t' && character!='\r' && character!='\n')))
            throw invalid("The CSV document contains unsupported binary/control characters. Use UTF-8 text CSV.");
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }

    private static final class BatchSink {
        private final Consumer<SpreadsheetRowBatch> consumer;
        private final ArrayList<Map<String,String>> rows=new ArrayList<>(DATA_BATCH_SIZE);
        private final ArrayList<Long> numbers=new ArrayList<>(DATA_BATCH_SIZE);
        BatchSink(Consumer<SpreadsheetRowBatch> consumer) { this.consumer=java.util.Objects.requireNonNull(consumer); }
        void add(Map<String,String> row,long number) {
            rows.add(row);numbers.add(number);
            // Wide sheets need smaller batches so the pending-worker queue stays bounded in cells too.
            if(rows.size()==DATA_BATCH_SIZE || (long)rows.size()*row.size()>=65_536)finish();
        }
        void finish() { if(rows.isEmpty()) return; consumer.accept(new SpreadsheetRowBatch(rows,numbers)); rows.clear(); numbers.clear(); }
    }
}
