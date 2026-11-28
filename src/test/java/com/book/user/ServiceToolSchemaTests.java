package com.book.user;

import com.book.user.service.*;
import com.book.spreadsheet.api.SpreadsheetReadService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ServiceToolSchemaTests {
    @Test
    void usesSupportedMetadataWithoutActorOrCredentialInputSchemas() {
        var objects = List.of(
                new OwnProfileService(null, null, null, null),
                new UserManagementService(null, null, null),
                new RoleManagementService(null, null, null),
                new SpreadsheetReadService(null, null),
                new SessionService(null, null, null, null, null, null));
        var names = new HashSet<String>();
        for (Object service : objects) {
            var tools = MethodToolCallbackProvider.builder().toolObjects(service).build().getToolCallbacks();
            assertThat(tools).isNotEmpty();
            for (var tool : tools) {
                assertThat(names.add(tool.getToolDefinition().name())).isTrue();
                assertThat(tool.getToolDefinition().description()).isNotBlank();
                assertThat(tool.getToolDefinition().inputSchema()).doesNotContain("authorizationFingerprint", "membershipId", "sessionId", "accessToken", "password", "actor");
            }
            for (var method : service.getClass().getDeclaredMethods()) {
                if (method.getAnnotation(Tool.class) == null) continue;
                for (var parameter : method.getParameters()) {
                    assertThat(parameter.getAnnotation(ToolParam.class)).as(method.getName()).isNotNull();
                    assertThat(parameter.getAnnotation(ToolParam.class).description()).isNotBlank();
                }
            }
        }
        assertThat(names).contains("currentSessionStatus", "currentPermissions", "inspectUserAccess", "inspectRoles", "readSpreadsheetRows");
    }
}
