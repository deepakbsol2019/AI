package com.book.user;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import com.book.storage.TenantStoragePaths;
import com.book.user.security.TenantContext;
import com.book.user.service.TenantProvisioningService;
import com.book.upload.topic.TopicPdfFileStore;
import com.book.download.pdf.TopicPdfSource;
import com.book.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class TenantStorageSecurityTests {
    @TempDir Path temporary;
    @Test void companyRegistrationCreatesExactTenantDirectoryAndRetryReusesIt() {
        var jdbc=AuthTestDatabase.create();var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var storage=new TenantStoragePaths(temporary.toString());var service=new TenantProvisioningService(jdbc,Clock.systemUTC(),storage);
        var account=tx.execute(s->service.create("ORGANIZATION","PASSWORD","storage@example.com","Storage","storage-company","Storage Company",true));
        Path expected=temporary.resolve(Long.toString(account.tenantId()));assertThat(expected).isDirectory();
        assertThat(storage.provision(account.tenantId())).isEqualTo(expected);
        assertThat(storage.provision(account.tenantId())).isEqualTo(expected);
        assertThat(expected.getParent()).isEqualTo(temporary);
        // A further membership/user in the same tenant never supplies a new storage identity.
        jdbc.update("INSERT INTO users(display_name,primary_email,email_verified_at,status,security_version,created_at,updated_at) VALUES('Another','another@example.com',CURRENT_TIMESTAMP,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        long user=jdbc.queryForObject("SELECT user_id FROM users WHERE primary_email='another@example.com'",Long.class);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,status,authz_version,joined_at,created_at,updated_at) VALUES(?,?,'ACTIVE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",account.tenantId(),user);
        assertThat(storage.provision(account.tenantId())).isEqualTo(expected);
        try(var stream=Files.list(temporary)){assertThat(stream.toList()).containsExactly(expected);}catch(Exception e){throw new AssertionError(e);}
    }
    @Test void storageFailureRollsBackRegistrationAndNeverClaimsReady() throws Exception {
        Path file=Files.writeString(temporary.resolve("blocked"),"preserve");
        var jdbc=AuthTestDatabase.create();var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var service=new TenantProvisioningService(jdbc,Clock.systemUTC(),new TenantStoragePaths(file.toString()));
        assertThatThrownBy(()->tx.execute(s->service.create("ORGANIZATION","PASSWORD","failed@example.com","Failed","failed-company","Company",true))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isZero();assertThat(Files.readString(file)).isEqualTo("preserve");
    }
    @Test void concurrentProvisioningIsIdempotent() throws Exception {
        var storage=new TenantStoragePaths(temporary.toString());
        try(var pool=Executors.newFixedThreadPool(6)) {
            var jobs=new ArrayList<Callable<Path>>();for(int i=0;i<12;i++)jobs.add(()->storage.provision(123));
            for(var result:pool.invokeAll(jobs))assertThat(result.get()).isEqualTo(temporary.resolve("123"));
        }
        try(var stream=Files.list(temporary.resolve("123"))){assertThat(stream.toList()).isEmpty();}
    }
    @Test void systemTemporaryDirectoryAliasesSupportProvisioningReadingAndDeletionManifests() throws Exception {
        // A normal Windows Maven launch supplies an 8.3 java.io.tmpdir (DEEPAK~1).
        // Keep that spelling rather than canonicalizing it away in the fixture.
        var storage=new TenantStoragePaths(temporary.toString());
        Path root=storage.provision(987);
        Path file=Files.writeString(root.resolve("owned.pdf"),"fixture");
        assertThat(storage.readableFile(987,"owned.pdf")).isEqualTo(file.toRealPath());
        assertThat(storage.requirePath(987,file)).isEqualTo(file);
        var cleanup=new com.book.user.service.UserDeletionFiles(storage);
        var manifest=cleanup.snapshot(987);
        assertThat(manifest.entries()).hasSize(2);
        cleanup.delete(987,manifest);
        assertThat(root).doesNotExist();
        assertThat(temporary).isDirectory();
    }
    @Test void uploadsHaveSeparateTenantParentsAndSuperUserCannotReadForeignFiles() throws Exception {
        var files=new TopicPdfFileStore(temporary.toString(),1);Path first,second;
        try(var scope=TenantContext.use(1,11L,false)){first=files.createOperationDirectory(1L,"Same Book");}
        try(var scope=TenantContext.use(2,22L,false)){second=files.createOperationDirectory(2L,"Same Book");}
        assertThat(first.getParent()).isEqualTo(temporary.resolve("1/PDF/11"));assertThat(second.getParent()).isEqualTo(temporary.resolve("2/PDF/22"));
        Path foreign=Files.writeString(second.resolve("foreign.pdf"),"foreign");
        try(var scope=TenantContext.use(1,11L,true)) {
            var topic=new TopicDetails();topic.setBookDetails(new BookDetails());topic.setPdfPath(foreign.toString());
            assertThatThrownBy(()->new TopicPdfSource(temporary.toString()).resolve(topic)).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(Files.readString(foreign)).isEqualTo("foreign");
    }
    @Test void missingContextUnsafeIdsNamesTraversalAndLegacySharedPathsFailClosed() throws Exception {
        var storage=new TenantStoragePaths(temporary.toString());storage.provision(1);Path shared=Files.writeString(temporary.resolve("legacy.pdf"),"preserve");
        assertThatThrownBy(storage::currentRoot).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        assertThatThrownBy(()->storage.provision(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->storage.provision(-1)).isInstanceOf(IllegalArgumentException.class);
        for(String name:List.of("../file","C:\\escape","/escape","a/b","NUL.pdf","..","bad:stream"))
            assertThatThrownBy(()->TenantStoragePaths.filename(name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->storage.readableFile(1,shared.toString())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->storage.readableFile(1,"../legacy.pdf")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(shared)).isEqualTo("preserve");
    }
    @Test void symlinkEscapeIsRejectedForProvisioningAndReading() throws Exception {
        var storage=new TenantStoragePaths(temporary.resolve("base").toString());storage.provision(1);
        Path outside=Files.createDirectory(temporary.resolve("outside"));Files.writeString(outside.resolve("private.pdf"),"private");
        linkDirectory(storage.tenantPath(2),outside);
        assertThatThrownBy(()->storage.provision(2)).isInstanceOf(ResponseStatusException.class);
        linkDirectory(storage.tenantPath(1).resolve("linked"),outside);
        assertThatThrownBy(()->storage.readableFile(1,"linked/private.pdf")).isInstanceOf(java.io.IOException.class);
    }
    private void linkDirectory(Path link,Path target) throws Exception {
        try { Files.createSymbolicLink(link,target); }
        catch(FileSystemException|UnsupportedOperationException unavailable) {
            if(java.io.File.separatorChar!='\\') { Assumptions.abort("Host does not permit directory links");return; }
            // A Windows junction exercises the same real-path escape without requiring symlink privileges.
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if(process.waitFor()!=0) Assumptions.abort("Host does not permit directory links");
        }
    }
}
