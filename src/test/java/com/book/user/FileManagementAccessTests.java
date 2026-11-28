package com.book.user;

import com.book.storage.TenantStoragePaths;
import com.book.tools.ServiceToolAccess;
import com.book.upload.controller.FileDeletionCapabilitiesController;
import com.book.upload.service.FileDeletionPolicy;
import com.book.upload.service.UploadedFileDeletionService;
import com.book.user.security.UserPrincipal;
import com.book.user.service.AuthorizationService;
import com.book.user.service.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real reserved-role and permission queries with isolated metadata and physical files. */
class FileManagementAccessTests {
    @TempDir Path temporary;
    JdbcTemplate jdbc;
    UserPrincipal actor;
    TenantStoragePaths storage;
    FileDeletionPolicy policy;
    FileDeletionCapabilitiesController controller;
    UploadedFileDeletionService deletion;
    long tenant, foreignTenant, user, other;
    final Map<Long,Path> physical = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        jdbc = AuthTestDatabase.create(); storage = new TenantStoragePaths(temporary.resolve("storage").toString());
        var provisioning = new TenantProvisioningService(jdbc, Clock.systemUTC(), storage);
        var first = provisioning.create("INDIVIDUAL", "PASSWORD", "file-owner@example.com", "Owner", null, null, true);
        var second = provisioning.create("INDIVIDUAL", "PASSWORD", "file-other@example.com", "Other", null, null, true);
        tenant = first.tenantId(); user = first.userId(); foreignTenant = second.tenantId(); other = second.userId();
        actor = new UserPrincipal(user, tenant, first.membershipId(), 1, 1, 1, 1, "trusted-test");
        jdbc.execute("CREATE TABLE pdf_file_status(book_upload_id BIGINT PRIMARY KEY,tenant_id BIGINT,uploader_user_id BIGINT,file_name VARCHAR(1024),book_name VARCHAR(255),created_at DATETIME(6),updated_at DATETIME(6),processing_state VARCHAR(16),status VARCHAR(16),message LONGTEXT,UNIQUE(tenant_id,book_upload_id))");
        jdbc.execute("CREATE TABLE pdf_file_author_details(aouthor_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE pdf_file_details(book_id BIGINT PRIMARY KEY,tenant_id BIGINT,book_name VARCHAR(255),book_upload_id BIGINT REFERENCES pdf_file_status(book_upload_id),author_id BIGINT REFERENCES pdf_file_author_details(aouthor_id))");
        jdbc.execute("CREATE TABLE pdf_file_topic_details(topic_id BIGINT PRIMARY KEY,book_id BIGINT REFERENCES pdf_file_details(book_id),pdf_path VARCHAR(2048))");
        String sheets = new ClassPathResource("db/manual/20261126_spreadsheets.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^SET time_zone[^;]*;", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", "");
        new ResourceDatabasePopulator(new ByteArrayResource(AuthTestDatabase.documentTableNames(sheets).getBytes(StandardCharsets.UTF_8))).execute(jdbc.getDataSource());
        pdf(10, 101, tenant, user, false); pdf(20, 102, tenant, other, false); pdf(30, 103, foreignTenant, other, false); pdf(40, 104, tenant, user, true);
        spreadsheet(50, 201, tenant, user, false); spreadsheet(60, 202, tenant, other, false); spreadsheet(70, 203, foreignTenant, other, false); spreadsheet(80, 204, tenant, user, true);
        var access = mock(ServiceToolAccess.class); when(access.require(anyString(), anyString())).thenAnswer(invocation -> actor);
        policy = new FileDeletionPolicy(jdbc, new AuthorizationService(jdbc));
        controller = new FileDeletionCapabilitiesController(jdbc, access, policy);
        deletion = new UploadedFileDeletionService(jdbc, storage, new DataSourceTransactionManager(jdbc.getDataSource()), access, policy);
    }
    private void receipt(long id, long scopeTenant, long uploader, boolean active) {
        jdbc.update("INSERT INTO pdf_file_status VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?,?)", id, scopeTenant, uploader,
                "same-filename.pdf", "Book " + id, active ? "PROCESSING" : "COMPLETED", active ? "inprogress" : "Success", "Stored upload " + id);
    }
    private void pdf(long upload, long id, long scopeTenant, long uploader, boolean active) throws Exception {
        receipt(upload, scopeTenant, uploader, active);
        Path file = Files.writeString(storage.tenantPath(scopeTenant).resolve("pdf-" + id + ".pdf"), "pdf " + id); physical.put(id, file);
        jdbc.update("INSERT INTO pdf_file_author_details VALUES(?)", id);
        jdbc.update("INSERT INTO pdf_file_details VALUES(?,?,?,?,?)", id, scopeTenant, "Book " + id, upload, id);
        jdbc.update("INSERT INTO pdf_file_topic_details VALUES(?,?,?)", id, id, file.toString());
    }
    private void spreadsheet(long upload, long id, long scopeTenant, long uploader, boolean active) throws Exception {
        receipt(upload, scopeTenant, uploader, active);
        Path original = Files.writeString(storage.tenantPath(scopeTenant).resolve("sheet-" + id + ".csv"), "id\n1"); physical.put(id, original);
        jdbc.update("INSERT INTO spreadsheet_file_details(file_id,upload_id,tenant_id,file_name,canonical_name,original_upload_path,original_format,sheet_count,uploaded_at,created_by) VALUES(?,?,?,?,?,?,'CSV',1,CURRENT_TIMESTAMP,?)",
                id, upload, scopeTenant, "same-filename.csv", "sheet-" + id, original.toString(), uploader);
        jdbc.update("INSERT INTO spreadsheet_sheet_details(sheet_id,file_id,sheet_index,sheet_name,header_row_number,`header`,column_count,data_row_count,upload_path,uploaded_at,created_by) VALUES(?,?,1,'Sheet1',1,'{}' FORMAT JSON,1,1,?,CURRENT_TIMESTAMP,?)", id, id, original.toString(), uploader);
        jdbc.update("INSERT INTO spreadsheet_sheet_rows(file_id,sheet_id,`row_number`,payload) VALUES(?,?,2,'{}' FORMAT JSON)", id, id);
    }
    private void role(String code, boolean reserved) { jdbc.update("UPDATE roles SET role_code=?,is_system=? WHERE tenant_id=?", code, reserved, tenant); }
    private List<Long> ids(FileDeletionCapabilitiesController.ManagedPage page) { return page.content().stream().map(row -> ((Number) row.get("book_upload_id")).longValue()).toList(); }
    @Test void capabilityHintsFollowActualRoleScopeAndExcludeActiveUploads() {
        List<Long> books = List.of(101L, 102L, 103L, 104L, 999L);
        List<Long> sheets = List.of(201L, 202L, 203L, 204L);
        assertThat(controller.capabilities("books", books)).containsEntry(101L,true).containsEntry(102L,false).containsEntry(103L,false).containsEntry(104L,false).containsEntry(999L,false);
        assertThat(controller.capabilities("spreadsheets", sheets)).containsEntry(201L,true).containsEntry(202L,false).containsEntry(203L,false).containsEntry(204L,false);
        role("SUPER_USER", false); assertThat(policy.scope(actor).global()).isFalse(); assertThat(controller.capabilities("books", books).get(103L)).isFalse();
        role("ADMIN", false); assertThat(policy.scope(actor).admin()).isFalse(); assertThat(controller.capabilities("books", books).get(102L)).isFalse();
        role("ADMIN", true);
        assertThat(controller.capabilities("books", books)).containsEntry(102L,true).containsEntry(103L,false).containsEntry(104L,false);
        assertThat(controller.capabilities("spreadsheets", sheets)).containsEntry(202L,true).containsEntry(203L,false);
        role("SUPER_USER", true);
        assertThat(controller.capabilities("books", books)).containsEntry(102L,true).containsEntry(103L,true).containsEntry(104L,false);
        assertThat(controller.capabilities("spreadsheets", sheets)).containsEntry(202L,true).containsEntry(203L,true).containsEntry(204L,false);
    }
    @Test void managedListAndDetailRemainScopedBeforeCountsAndPagination() throws Exception {
        assertThat(controller.manage(0,100).totalElements()).isEqualTo(4);
        assertThat(ids(controller.manage(0,100))).containsExactly(80L,50L,40L,10L);
        denied(() -> controller.managedStatus(20),404); denied(() -> controller.managedStatus(30),404);
        role("ADMIN", true);
        assertThat(controller.manage(0,2).totalElements()).isEqualTo(6); assertThat(controller.manage(0,2).totalPages()).isEqualTo(3);
        assertThat(ids(controller.manage(0,2))).containsExactly(80L,60L); assertThat(ids(controller.manage(1,2))).containsExactly(50L,40L);
        assertThat(controller.managedStatus(20).get("uploadedBy")).isEqualTo(other); denied(() -> controller.managedStatus(30),404);
        role("SUPER_USER", true);
        assertThat(controller.manage(0,3).totalElements()).isEqualTo(8); assertThat(controller.manage(0,3).totalPages()).isEqualTo(3);
        assertThat(controller.managedStatus(30).get("tenantId")).isEqualTo(foreignTenant);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/files/manage").param("page","1").param("size","3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(8)).andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].pdf_path").doesNotExist());
        mvc.perform(get("/api/files/manage/30")).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(foreignTenant));
    }
    @Test void permissionRevocationHidesCapabilitiesAndDeniesManagement() {
        jdbc.update("DELETE FROM role_permissions WHERE tenant_id=? AND permission_id=(SELECT permission_id FROM permissions WHERE permission_code='BOOKS.DELETE')", tenant);
        assertThat(controller.capabilities("uploads",List.of(10L,20L,30L))).containsOnlyKeys(10L,20L,30L).allSatisfy((id, allowed) -> assertThat(allowed).isFalse());
        denied(() -> controller.manage(0,20),403); denied(() -> controller.managedStatus(10),403);
        role("ADMIN", true); assertThat(controller.manage(0,100).totalElements()).isEqualTo(6);
        role("SUPER_USER", true); assertThat(controller.manage(0,100).totalElements()).isEqualTo(8);
        jdbc.update("UPDATE roles SET status='SUSPENDED' WHERE tenant_id=?",tenant);
        assertThat(policy.scope(actor).permitted()).isFalse(); denied(() -> controller.manage(0,20),403);
    }
    @Test void everyDeletePathUsesTheSameRealRolePolicyAndPreservesForeignFiles() {
        denied(() -> deletion.deletePdf(102),404); denied(() -> deletion.deletePdf(103),404);
        denied(() -> deletion.deleteSpreadsheet(202),404); denied(() -> deletion.deleteUpload(70),404);
        deletion.deletePdf(101); deletion.deleteSpreadsheet(201);
        assertThat(physical.get(101L)).doesNotExist(); assertThat(physical.get(201L)).doesNotExist();
        assertThat(physical.get(102L)).exists(); assertThat(physical.get(203L)).exists();
        role("ADMIN",true); deletion.deletePdf(102); deletion.deleteSpreadsheet(202);
        assertThat(physical.get(102L)).doesNotExist(); assertThat(physical.get(202L)).doesNotExist();
        denied(() -> deletion.deleteUpload(30),404); assertThat(physical.get(103L)).exists();
        role("SUPER_USER",true); deletion.deleteUpload(30); deletion.deleteSpreadsheet(203);
        assertThat(physical.get(103L)).doesNotExist(); assertThat(physical.get(203L)).doesNotExist();
        assertThat(physical.get(104L)).exists(); assertThat(physical.get(204L)).exists();
    }
    @Test void badCapabilityAndPaginationInputsFailClearly() {
        denied(() -> controller.capabilities("books",List.of()),400);
        denied(() -> controller.capabilities("books",List.of(-1L)),400);
        denied(() -> controller.capabilities("unknown",List.of(101L)),400);
        denied(() -> controller.manage(-1,20),400); denied(() -> controller.manage(0,101),400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_status",Long.class)).isEqualTo(8);
    }
    @Test void legacyMissingUploadersPermitOnlyPrivilegedTenantScopeAndUnassignedTenantsFailClearly() {
        jdbc.update("UPDATE pdf_file_status SET uploader_user_id=NULL WHERE book_upload_id=20");
        assertThat(controller.capabilities("books",List.of(102L)).get(102L)).isFalse();
        denied(() -> deletion.deletePdf(102),404);
        role("ADMIN",true);
        assertThat(controller.capabilities("books",List.of(102L)).get(102L)).isTrue();
        deletion.deletePdf(102); assertThat(physical.get(102L)).doesNotExist();
        jdbc.update("INSERT INTO pdf_file_status(book_upload_id,tenant_id,uploader_user_id,status,processing_state,file_name,message) VALUES(90,NULL,NULL,'Success','COMPLETED','legacy.pdf','unassigned legacy')");
        role("SUPER_USER",true);
        assertThat(controller.capabilities("uploads",List.of(90L))).containsEntry(90L,false);
        denied(() -> deletion.deleteUpload(90),409);
        assertThat(ids(controller.manage(0,100))).doesNotContain(90L);
    }
    @Test void evenGlobalDeletionHonorsTheTargetsTenantStorageCleanupLock() throws Exception {
        role("SUPER_USER",true); storage.lockDeletion(foreignTenant,"tenant-job");
        denied(() -> deletion.deletePdf(103),503);
        assertThat(physical.get(103L)).exists();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pdf_file_details WHERE book_id=103",Long.class)).isEqualTo(1);
        storage.unlockDeletion(foreignTenant,"tenant-job"); deletion.deletePdf(103); assertThat(physical.get(103L)).doesNotExist();
    }
    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,failure -> assertThat(failure.getStatusCode().value()).isEqualTo(status));
    }
}
