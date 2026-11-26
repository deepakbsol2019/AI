package com.book.user;

import com.book.user.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class TenantContextTests {
    @Test void nestedScopesRestoreAndFinallyClearTheThread() {
        assertThat(TenantContext.currentTenantId()).isNull();
        try (var outer = TenantContext.use(10)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(10);
            try (var inner = TenantContext.use(20)) { assertThat(TenantContext.requireTenantId()).isEqualTo(20); }
            assertThat(TenantContext.requireTenantId()).isEqualTo(10);
        }
        assertThat(TenantContext.currentTenantId()).isNull();
    }
    @Test void unassignedAndForeignRecordsAreInaccessibleAndNoContextFailsClosed() {
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        try (var ignored = TenantContext.use(1)) {
            assertThatCode(() -> TenantContext.requireOwned(1L)).doesNotThrowAnyException();
            assertThatThrownBy(() -> TenantContext.requireOwned(null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> TenantContext.requireOwned(2L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
    }
    @Test void contextsDoNotLeakIntoUnrelatedWorkerThreads() throws Exception {
        try (var ignored = TenantContext.use(1, true); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(TenantContext::currentTenantId).get()).isNull();
            assertThat(executor.submit(TenantContext::isSuperUser).get()).isFalse();
        }
    }
    @Test void superUserMustStillUseCurrentStorageTenant() {
        assertThat(TenantContext.isSuperUser()).isFalse();
        try (var ignored = TenantContext.use(1, true)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(1);
            assertThat(TenantContext.isSuperUser()).isTrue();
            assertThatThrownBy(() -> TenantContext.requireOwned(2L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> TenantContext.requireOwned(null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> TenantContext.requireOwned(0L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        }
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }
    @Test void ordinaryNestedScopesStripGlobalPrivilegeAndRestoreTheEntireOuterScope() {
        try (var outer = TenantContext.use(10, true)) {
            try (var inner = TenantContext.use(20)) {
                assertThat(TenantContext.requireTenantId()).isEqualTo(20);
                assertThat(TenantContext.isSuperUser()).isFalse();
                assertThatThrownBy(() -> TenantContext.requireOwned(10L)).isInstanceOf(ResponseStatusException.class);
            }
            assertThat(TenantContext.requireTenantId()).isEqualTo(10);
            assertThat(TenantContext.isSuperUser()).isTrue();
        }
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }
    @Test void anotherThreadCannotCloseOrChangeTheOwningThreadsScope() throws Exception {
        try (var outer = TenantContext.use(10, true); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> executor.submit(outer::close).get())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(TenantContext.requireTenantId()).isEqualTo(10);
            assertThat(TenantContext.isSuperUser()).isTrue();
            assertThat(executor.submit(TenantContext::currentTenantId).get()).isNull();
        }
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }
}
