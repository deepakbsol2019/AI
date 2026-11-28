package com.book.upload;

import com.book.spreadsheet.api.SpreadsheetExceptionHandler;
import com.book.upload.controller.UploadedFileDeletionController;
import com.book.upload.service.UploadedFileDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadedFileDeletionControllerTests {
    @Test void routesEachUniqueIdAndReturnsNoContentOnlyWhenServiceCompletes()throws Exception {
        var service=mock(UploadedFileDeletionService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new UploadedFileDeletionController(service)).setControllerAdvice(new SpreadsheetExceptionHandler()).build();
        mvc.perform(delete("/api/books/7")).andExpect(status().isNoContent());verify(service).deletePdf(7);
        mvc.perform(delete("/api/spreadsheets/8")).andExpect(status().isNoContent());verify(service).deleteSpreadsheet(8);
        mvc.perform(delete("/api/uploads/9")).andExpect(status().isNoContent());verify(service).deleteUpload(9);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Cleanup incomplete; retry this ID.")).when(service).deletePdf(7);
        mvc.perform(delete("/api/books/7")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value("Cleanup incomplete; retry this ID."));
    }
    @Test void migrationRegistersAllDeleteRoutesWithDeletePermission() {
        var jdbc=com.book.user.AuthTestDatabase.create();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_endpoints e JOIN endpoint_permissions ep ON ep.endpoint_id=e.endpoint_id JOIN permissions p ON p.permission_id=ep.permission_id WHERE e.http_method='DELETE' AND e.path_pattern IN('/api/books/{bookId}','/api/spreadsheets/{fileId}','/api/uploads/{uploadId}') AND p.permission_code='BOOKS.DELETE' AND e.is_public=FALSE",Long.class)).isEqualTo(3);
    }
}
