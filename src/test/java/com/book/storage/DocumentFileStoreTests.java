package com.book.storage;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DocumentFileStoreTests {
    @TempDir Path temporary;
    @Test void identityPathsAreExclusiveTenantConfinedAndLegacyPathsRemainReadable()throws Exception {
        var paths=new TenantStoragePaths(temporary.resolve("root").toString());var store=new DocumentFileStore(paths);
        Path source=temporary.resolve("source");Files.writeString(source,"original bytes");
        Path first=store.write(7,11,"XLSX","Report","A/B.xlsx",source),collision=store.write(7,11,"XLSX","Report","A\\B.xlsx",source);
        assertThat(first).isNotEqualTo(collision);assertThat(first).endsWith(Path.of("7","XLSX","11","Report","A_B.xlsx"));
        assertThat(store.read(7,first.toString())).isEqualTo("original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->store.read(8,first.toString())).isInstanceOf(java.io.IOException.class);
        Path legacy=paths.provision(7).resolve("OldBook");Files.createDirectory(legacy);Files.writeString(legacy.resolve("topic.pdf"),"legacy");
        assertThat(new String(store.read(7,legacy.resolve("topic.pdf").toString()))).isEqualTo("legacy");
        store.delete(7,first.toString());assertThat(collision).exists();assertThat(legacy.resolve("topic.pdf")).exists();
    }
    @Test void originalsAndSheetsHaveDifferentIdentityLocationsAndDeletionLockBlocksWrites()throws Exception {
        var paths=new TenantStoragePaths(temporary.resolve("root").toString());var store=new DocumentFileStore(paths);
        Path source=temporary.resolve("source");Files.writeString(source,"data");
        for(String extension:List.of("pdf","csv","xlsx","xls")){
            Path original=store.write(7,11,extension.toUpperCase(java.util.Locale.ROOT),"Report","Report."+extension,source);
            Path sheet=store.write(7,11,"XLSX","Report","Report_Sheet.xlsx",source);
            assertThat(original).endsWith(Path.of("7",extension.toUpperCase(java.util.Locale.ROOT),"11","Report","Report."+extension));assertThat(sheet).isNotEqualTo(original);
        }
        paths.lockDeletion(7,"owned-job");
        assertThatThrownBy(()->store.write(7,11,"CSV","Report","blocked.csv",source)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void missingRootAndEveryRequiredParentAreCreatedAndNamesAreConfined()throws Exception{
        Path absent=temporary.resolve("missing").resolve("upload-root");
        var paths=new TenantStoragePaths(absent.toString());var store=new DocumentFileStore(paths);
        Path source=temporary.resolve("source");Files.writeString(source,"data");
        Path first=store.write(7,11,"CSV","Book: One/Two","Report.csv",source);
        assertThat(first).isRegularFile().hasParent(absent.resolve("7/CSV/11/Book_ One_Two"));
        Path differentUser=store.write(7,12,"CSV","Book: One/Two","Report.csv",source);
        assertThat(differentUser).hasParent(absent.resolve("7/CSV/12/Book_ One_Two"));
        assertThatThrownBy(()->paths.documentDirectory(7,0,"PDF","Book")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->paths.documentDirectory(7,11,"../PDF","Book")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->paths.documentDirectory(7,11,"PDF","..")).isInstanceOf(IllegalArgumentException.class);
    }
}
