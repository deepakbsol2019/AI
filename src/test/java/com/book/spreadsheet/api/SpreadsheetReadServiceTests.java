package com.book.spreadsheet.api;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.book.spreadsheet.persistence.*;
import com.book.spreadsheet.persistence.SpreadsheetRepository.*;
import com.book.storage.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SpreadsheetReadServiceTests {
    @TempDir Path temporary;
    private JdbcTemplate jdbc;
    private SpreadsheetRepository repository;
    private DocumentFileStore storage;
    private SpreadsheetReadService service;
    @BeforeEach void setup()throws Exception {
        jdbc=SpreadsheetTestDatabase.create();repository=SpreadsheetTestDatabase.transactional(new SpreadsheetRepository(jdbc),jdbc);
        storage=new DocumentFileStore(new TenantStoragePaths(temporary.resolve("root").toString()));service=new SpreadsheetReadService(repository,storage);
    }
    @Test void exactOriginalAndIndividualArtifactUsePersistedPathsAndTenantAssociations()throws Exception {
        Path original=temporary.resolve("original.xlsx"),extracted=temporary.resolve("single.xlsx");
        try(var workbook=new XSSFWorkbook()){
            workbook.createSheet("January").createRow(0).createCell(0).setCellValue("Employee");workbook.createSheet("February");
            try(var output=Files.newOutputStream(original)){workbook.write(output);}
        }
        try(var workbook=new XSSFWorkbook()){
            workbook.createSheet("January").createRow(0).createCell(0).setCellValue("Employee");
            try(var output=Files.newOutputStream(extracted)){workbook.write(output);}
        }
        var reservation=repository.reserve(7,11,"Report.xlsx","Report","XLSX");long file=reservation.file().fileId();
        Path storedOriginal=storage.write(7,11,"XLSX","Report","Report.xlsx",original);repository.original(7,11,file,storedOriginal.toString(),2);
        var first=repository.createSheet(7,11,file,1,"January",1,Map.of("column_1","Employee"));
        var second=repository.createSheet(7,11,file,2,"February",0,Map.of());
        Path storedSheet=storage.write(7,11,"XLSX","Report","Report_January.xlsx",extracted);
        repository.sheetArtifact(7,11,file,first.sheetId(),storedSheet.toString());repository.sheetArtifact(7,11,file,second.sheetId(),storedSheet.toString());
        repository.complete(7,11,file,Map.of(first.sheetId(),0L,second.sheetId(),0L));
        assertThat(service.download(7,file,null).bytes()).isEqualTo(Files.readAllBytes(original));
        assertThat(service.download(7,file,null).filename()).isEqualTo("Report.xlsx");
        Files.delete(storedOriginal); // Individual delivery must never re-extract the original.
        var download=service.download(7,file,first.sheetId());
        assertThat(download.bytes()).isEqualTo(Files.readAllBytes(extracted));assertThat(download.filename()).isEqualTo("Report_January.xlsx");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(download.bytes()))){assertThat(workbook.getNumberOfSheets()).isEqualTo(1);assertThat(workbook.getSheetName(0)).isEqualTo("January");}
        assertThatThrownBy(()->service.download(8,file,first.sheetId())).isInstanceOf(ResponseStatusException.class);
        var other=repository.reserve(7,11,"Other.csv","Other","CSV");
        var foreign=repository.createSheet(7,11,other.file().fileId(),1,"Sheet1",1,Map.of("column_1","Other"));
        assertThatThrownBy(()->service.download(7,file,foreign.sheetId())).isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(7,0,20,"report").content()).hasSize(1);assertThat(service.list(8,0,20,"").content()).isEmpty();
        assertThat(service.details(7,file).createdByName()).isEqualTo("First Uploader");
    }
    @Test void serviceJsonReadsAllOrderedDatabaseRowsWithoutTouchingStoredFiles()throws Exception {
        var reservation=repository.reserve(7,11,"Rows.csv","Rows","CSV");long file=reservation.file().fileId();
        repository.original(7,11,file,"unused-original.csv",1);
        var sheet=repository.createSheet(7,11,file,1,"Sheet1",2,new LinkedHashMap<>(Map.of("column_1","ID","column_2","")));
        repository.sheetArtifact(7,11,file,sheet.sheetId(),"unused-sheet.csv");
        for(int offset=0;offset<1200;offset+=500){
            var rows=new ArrayList<Map<String,String>>();var numbers=new ArrayList<Long>();
            for(int index=offset;index<Math.min(offset+500,1200);index++){var row=new LinkedHashMap<String,String>();row.put("column_1",String.format(Locale.ROOT,"%05d",index));row.put("column_2","");rows.add(row);numbers.add(2L+index*2);}
            repository.insertRows(7,11,file,sheet.sheetId(),rows,numbers);
        }
        repository.complete(7,11,file,Map.of(sheet.sheetId(),1200L));
        var output=new ByteArrayOutputStream();service.data(7,file,sheet.sheetId()).writeTo(output);
        var result=JsonMapper.builder().build().readTree(output.toByteArray());
        assertThat(result.get("fileId").isIntegralNumber()).isTrue();assertThat(result.get("rows").size()).isEqualTo(1200);
        assertThat(result.at("/rows/0/rowNumber").asLong()).isEqualTo(2);assertThat(result.at("/rows/1199/rowNumber").asLong()).isEqualTo(2400);
        assertThat(result.at("/rows/0/payload/column_1").asString()).isEqualTo("00000");assertThat(result.at("/rows/0/payload/column_2").asString()).isEmpty();
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("unused-original","uploadPath","tenantId");
        var page=service.rows(7,file,sheet.sheetId(),1,100);assertThat(page.rows()).hasSize(100);assertThat(page.rowNumbers().getFirst()).isEqualTo(202);
    }
    @Test void anotherUploaderInTheSameVisibleTenantCanDownloadWithoutGainingCrossTenantAccess()throws Exception {
        var reserved=repository.reserve(7,11,"Shared.csv","Shared","CSV");long file=reserved.file().fileId();
        Path source=Files.writeString(temporary.resolve("Shared.csv"),"Header\nValue");
        Path stored=storage.write(7,11,"CSV","Shared","Shared.csv",source);
        repository.original(7,11,file,stored.toString(),1);
        var sheet=repository.createSheet(7,11,file,1,"Sheet1",1,Map.of("column_1","Header"));
        repository.sheetArtifact(7,11,file,sheet.sheetId(),stored.toString());
        repository.complete(7,11,file,Map.of(sheet.sheetId(),0L));
        try(var ignored=com.book.user.security.TenantContext.use(7,22L,false)) {
            assertThat(service.download(7,file,null).bytes()).isEqualTo(Files.readAllBytes(source));
            assertThatThrownBy(()->service.download(8,file,null)).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void incompleteUploadsAndInvalidIdentifiersAreNotReadable()throws Exception {
        var reserved=repository.reserve(7,11,"Pending.csv","Pending","CSV");long file=reserved.file().fileId();
        assertThat(service.list(7,0,20,"").content()).isEmpty();
        assertThatThrownBy(()->service.details(7,file)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.download(7,file,null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.details(7,0L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.list(7,-1,20,"")).isInstanceOf(ResponseStatusException.class);
    }
}
